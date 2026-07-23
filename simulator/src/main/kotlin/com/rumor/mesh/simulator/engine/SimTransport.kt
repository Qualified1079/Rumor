package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.sync.MAX_RBSR_ROUNDS
import com.rumor.mesh.core.sync.Rbsr
import com.rumor.mesh.core.sync.RbsrItem
import com.rumor.mesh.core.sync.SortedListRbsrStorage
import com.rumor.mesh.core.sync.toWire
import com.rumor.mesh.core.transport.wifidirect.BloomFilterData
import com.rumor.mesh.core.wire.WireJson
import kotlinx.serialization.encodeToString
import kotlin.random.Random

/**
 * Mirrors the on-wire exchange: A serializes a Bloom of its known IDs, sends
 * it to B; B picks the messages A "might not have" and sends them back. False
 * positives in the bloom mean B never offers some messages — over many
 * exchanges they propagate via different paths, but a poorly-chosen
 * falsePositiveRate is exactly the kind of bug worth catching here.
 */
// Mirror production (BloomFilterData.DEFAULT_FALSE_POSITIVE_RATE) so sim
// bandwidth/crossover numbers reflect what actually ships.
private const val BLOOM_FALSE_POSITIVE_RATE = BloomFilterData.DEFAULT_FALSE_POSITIVE_RATE

/**
 * In-process transport between two [SimNode]s. Applies [NetworkConditioner]
 * per message instead of per-session, since there's no real socket layer.
 *
 * An exchange is a simple offer/request/deliver cycle:
 * 1. A offers its known message IDs to B.
 * 2. B computes the diff and requests what it doesn't have.
 * 3. A delivers the requested messages, conditioned by [conditioner].
 * 4. B delivers its own outbound messages back to A the same way.
 */
class SimTransport(
    val nodeA: SimNode,
    val nodeB: SimNode,
    val conditioner: NetworkConditioner = NetworkConditioner(),
    /**
     * Optional tag set so scenarios can address edges symbolically
     * (e.g. partition every edge tagged "bridge"). The default-graph code
     * path leaves this empty; topology builders attach tags explicitly.
     */
    val tags: Set<String> = emptySet(),
    /**
     * If true, the summary phase uses Range-Based Set Reconciliation (O42)
     * instead of the bloom filter. Both sides run the same algorithm in
     * lock-step (single-process, no wire serialization needed at this layer).
     * Scenarios flip this to compare bloom-vs-RBSR bandwidth and convergence.
     */
    val useRbsr: Boolean = false,
    /**
     * O42 adaptive selection: when true, ignore [useRbsr] and pick the method
     * per exchange via `shouldUseRbsr` on the two nodes' set sizes — exactly the
     * production decision (both peers deterministically agree). Lets a scenario
     * exercise the real bloom↔RBSR switch as stores grow.
     */
    val adaptiveRbsr: Boolean = false,
) {
    /**
     * Perform a bidirectional exchange between [nodeA] and [nodeB].
     *
     * [rng] must be the tick's seeded RNG so that packet-loss decisions are
     * deterministic across replay runs. Real wall-clock delays are NOT applied
     * — this is a tick-based sim; latency is implicitly modelled by the tick
     * granularity, not by actual sleeps which would defeat speedMultiplier.
     */
    suspend fun exchange(rng: Random): ExchangeMetrics {
        val start = System.currentTimeMillis()
        var messagesAtoB = 0
        var messagesBtoA = 0
        var dropped = 0

        // Bloom-filter offer/request, mirroring GossipSession on the wire.
        // Each side builds a bloom of its known IDs, the peer offers messages
        // the bloom says "mightContain == false". Bloom false positives mean
        // some messages are silently skipped this exchange — over many edges
        // they reach via other paths, but a too-loose falsePositiveRate or a
        // too-small expectedItems will starve propagation. That's exactly the
        // class of bug worth catching here.

        var bloomSkipped = 0
        var rbsrRounds = 0
        // Summary-phase wire bytes — the cost of *computing* the diff, before any
        // message payloads move. This is where O42's win shows: bloom scales with
        // the known-set size (a full filter each way), RBSR with the symmetric
        // difference. Payload bytes after the diff are identical either way, so
        // this is the honest bandwidth axis to compare.
        var summaryBytes = 0L
        val effectiveRbsr = if (adaptiveRbsr) {
            com.rumor.mesh.core.sync.shouldUseRbsr(
                bothSupportRbsr = true,
                localSetSize = nodeA.knownMessages().size,
                peerSetSize = nodeB.knownMessages().size,
            )
        } else useRbsr
        val (aToB, bToA) = if (effectiveRbsr) {
            rbsrExchange(rng, onDrop = { dropped++ }, onRounds = { rbsrRounds = it },
                onSummaryBytes = { summaryBytes += it })
        } else {
            // src side gets credit for the skip — it's the offerer who saved the bandwidth.
            val a = exchangeOneDirection(nodeA, nodeB, rng, onDrop = { dropped++ },
                onBloomSkip = { bloomSkipped += it; nodeA.recordBloomSkips(it) },
                onSummaryBytes = { summaryBytes += it })
            val b = exchangeOneDirection(nodeB, nodeA, rng, onDrop = { dropped++ },
                onBloomSkip = { bloomSkipped += it; nodeB.recordBloomSkips(it) },
                onSummaryBytes = { summaryBytes += it })
            a to b
        }

        // Sort so TRANSFER_METADATA precedes CHUNK — TransferAssembler drops chunks
        // that arrive before their metadata record is registered.
        val deliveredA = aToB.sortedBy { if (it.type == MessageType.TRANSFER_METADATA) 0 else 1 }
        messagesAtoB = deliveredA.size
        if (deliveredA.isNotEmpty()) {
            nodeB.deliverExchange(PeerExchangeResult(
                peerUserId       = nodeA.userId,
                messagesReceived = deliveredA,
                ackedByPeer      = emptyList(),
                // The real transport shares the sender's WHOLE online snapshot
                // (onlineStatusTracker.currentSnapshot()), which includes everyone
                // it has seen — the peer itself included. Model that faithfully so
                // the receiver's self-filter is actually exercised.
                peerOnlineUsers  = nodeA.onlineTracker.currentSnapshot()
                    .ifEmpty { mapOf(nodeA.userId to System.currentTimeMillis()) },
                durationMs       = System.currentTimeMillis() - start,
            ))
        }

        val deliveredB = bToA.sortedBy { if (it.type == MessageType.TRANSFER_METADATA) 0 else 1 }
        messagesBtoA = deliveredB.size
        if (deliveredB.isNotEmpty()) {
            nodeA.deliverExchange(PeerExchangeResult(
                peerUserId       = nodeB.userId,
                messagesReceived = deliveredB,
                ackedByPeer      = emptyList(),
                peerOnlineUsers  = nodeB.onlineTracker.currentSnapshot()
                    .ifEmpty { mapOf(nodeB.userId to System.currentTimeMillis()) },
                durationMs       = System.currentTimeMillis() - start,
            ))
        }

        return ExchangeMetrics(messagesAtoB, messagesBtoA, dropped,
            System.currentTimeMillis() - start,
            bloomOffersSkipped = bloomSkipped, rbsrRoundsUsed = rbsrRounds,
            summaryBytes = summaryBytes)
    }

    /**
     * Bidirectional RBSR exchange. Runs the algorithm in lock-step in-process —
     * each side's [Rbsr.respond] consumes the other's outgoing frames. Bounded
     * by [MAX_RBSR_ROUNDS]; leftover diffs after the bound are dropped (will
     * resolve in a subsequent exchange). After convergence, A sends to B
     * exactly the message IDs B didn't have, and vice versa.
     */
    private fun rbsrExchange(
        rng: Random,
        onDrop: () -> Unit,
        onRounds: (Int) -> Unit = {},
        onSummaryBytes: (Int) -> Unit = {},
    ): Pair<List<RumorMessage>, List<RumorMessage>> {
        val itemsA = nodeA.knownMessages().map { RbsrItem(it.sentAtMs, it.id) }
        val itemsB = nodeB.knownMessages().map { RbsrItem(it.sentAtMs, it.id) }
        val rbsrA = Rbsr(SortedListRbsrStorage(itemsA))
        val rbsrB = Rbsr(SortedListRbsrStorage(itemsB))

        val aPeerNeeds = HashSet<String>()  // messages A has that B doesn't
        val bPeerNeeds = HashSet<String>()  // messages B has that A doesn't

        var framesA = rbsrA.initiate()
        var framesB = rbsrB.initiate()
        var roundsUsed = 0
        onSummaryBytes(frameBytes(framesA) + frameBytes(framesB))  // the initiate() frames go on the wire too
        for (round in 0 until MAX_RBSR_ROUNDS) {
            roundsUsed = round + 1
            val responseA = rbsrA.respond(framesB)
            aPeerNeeds.addAll(responseA.peerNeeds)
            val responseB = rbsrB.respond(framesA)
            bPeerNeeds.addAll(responseB.peerNeeds)
            // O117 parity with GossipSession: same per-session id ceiling, so
            // sim scenarios exercise the same partial-sync behavior real
            // devices now have.
            if (aPeerNeeds.size > com.rumor.mesh.core.sync.MAX_RBSR_SESSION_IDS ||
                bPeerNeeds.size > com.rumor.mesh.core.sync.MAX_RBSR_SESSION_IDS
            ) break
            if (framesA.isEmpty() && framesB.isEmpty()) break
            framesA = responseA.outgoing
            framesB = responseB.outgoing
            onSummaryBytes(frameBytes(framesA) + frameBytes(framesB))
        }
        onRounds(roundsUsed)

        val aToB = nodeA.knownMessages()
            .asSequence()
            .filter { it.id in aPeerNeeds }
            .take(MAX_OFFER_PER_EXCHANGE)
            .filter { conditioner.simulate(estimatedBytes(it), rng).also { if (it == null) onDrop() } != null }
            .toList()
        val bToA = nodeB.knownMessages()
            .asSequence()
            .filter { it.id in bPeerNeeds }
            .take(MAX_OFFER_PER_EXCHANGE)
            .filter { conditioner.simulate(estimatedBytes(it), rng).also { if (it == null) onDrop() } != null }
            .toList()
        return aToB to bToA
    }

    /**
     * Sender [src] offers messages [dst] doesn't (per [dst]'s bloom filter)
     * up to [MAX_OFFER_PER_EXCHANGE]. Returns the messages that survived the
     * network conditioner; calls [onDrop] once per dropped message.
     */
    private fun exchangeOneDirection(
        src: SimNode,
        dst: SimNode,
        rng: Random,
        onDrop: () -> Unit,
        onBloomSkip: (Int) -> Unit = {},
        onSummaryBytes: (Int) -> Unit = {},
    ): List<RumorMessage> {
        val dstKnownIds = dst.knownIds()
        if (dstKnownIds.isEmpty()) {
            // First exchange — no bloom needed, send everything (capped).
            return src.knownMessages().take(MAX_OFFER_PER_EXCHANGE).filter { msg ->
                conditioner.simulate(estimatedBytes(msg), rng).also { if (it == null) onDrop() } != null
            }
        }
        // Build a real Bloom from dst's known IDs, serialize+deserialize so
        // the same code path runs as on the wire (catches encoding bugs).
        val bloom = BloomFilterData(
            expectedItems = dstKnownIds.size.coerceAtLeast(64),
            falsePositiveRate = BLOOM_FALSE_POSITIVE_RATE,
        )
        for (id in dstKnownIds) bloom.add(id)
        val serialized = bloom.serialize()
        onSummaryBytes(serialized.length)  // dst's bloom crosses the wire so src can compute its offer
        val onWire = BloomFilterData.deserialize(serialized, dstKnownIds.size.coerceAtLeast(64))

        val all = src.knownMessages()
        val candidates = all.asSequence().filter { !onWire.mightContain(it.id) }
            .take(MAX_OFFER_PER_EXCHANGE).toList()
        // Bloom efficiency measure: how many messages we DIDN'T offer because
        // the peer's bloom indicated they already had them. This is the real
        // bandwidth saving the bloom buys. Counts both true-positives (peer
        // really has it) and false-positives (bloom said yes but peer doesn't).
        onBloomSkip(all.size - candidates.size)

        return candidates.filter { msg ->
            conditioner.simulate(estimatedBytes(msg), rng).also { if (it == null) onDrop() } != null
        }
    }

    companion object {
        /** Per-exchange offer cap. Limits snapshot scanning on large scenarios. */
        const val MAX_OFFER_PER_EXCHANGE = 200

        fun edgeKey(a: Int, b: Int) = "${minOf(a, b)}-${maxOf(a, b)}"
    }

    private fun estimatedBytes(msg: RumorMessage): Int =
        256 + (msg.payload?.content?.length ?: 0) + (msg.encryptedPayload?.length ?: 0)

    /** Serialized wire size of a batch of RBSR frames, via the real wire codec. */
    private fun frameBytes(frames: List<com.rumor.mesh.core.sync.RbsrFrame>): Int =
        if (frames.isEmpty()) 0 else WireJson.encodeToString(frames.map { it.toWire() }).length

    val edgeKey: String = edgeKey(nodeA.index, nodeB.index)
}

/**
 * Per-exchange diagnostics emitted by [SimTransport.exchange]. Captures
 * the bytes and counts moved in each direction plus the bloom or RBSR
 * bandwidth-saving signal a scenario asserts against. Used by both
 * simulator scenarios (correctness regressions) and the perf
 * characterization in CLAUDE.md O42 / O66 (round-count distributions
 * across realistic heal traces).
 */
data class ExchangeMetrics(
    val messagesAtoB: Int,
    val messagesBtoA: Int,
    val dropped: Int,
    val durationMs: Long,
    /**
     * Number of messages NOT offered across this exchange because the peer's
     * bloom filter indicated they already had them. The real measure of
     * bloom-filter bandwidth saving. Always 0 in the RBSR path because RBSR
     * doesn't speculate — it exchanges fingerprints to know exactly what's
     * missing, so there's no "we would have offered this but…" measure.
     */
    val bloomOffersSkipped: Int = 0,
    /**
     * Bisection rounds RBSR actually used to converge for this exchange (0
     * for bloom-path exchanges). If this consistently equals MAX_RBSR_ROUNDS
     * the cap is the bottleneck, not the algorithm — see O61.
     */
    val rbsrRoundsUsed: Int = 0,
    /**
     * Summary-phase wire bytes: the cost of *discovering* the diff before any
     * payload moves. Bloom path = serialized filter size each direction (scales
     * with known-set size); RBSR path = all frames across all rounds (scales
     * with the symmetric difference). The O42 bandwidth axis — see the
     * `RbsrBandwidthScenarioTest` comparison.
     */
    val summaryBytes: Long = 0,
) {
    val totalMessages get() = messagesAtoB + messagesBtoA
    val hasTraffic get() = totalMessages > 0
}
