package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.sync.MAX_RBSR_ROUNDS
import com.rumor.mesh.core.sync.Rbsr
import com.rumor.mesh.core.sync.RbsrItem
import com.rumor.mesh.core.sync.SortedListRbsrStorage
import com.rumor.mesh.core.transport.wifidirect.BloomFilterData
import kotlin.random.Random

/**
 * Mirrors the on-wire exchange: A serializes a Bloom of its known IDs, sends
 * it to B; B picks the messages A "might not have" and sends them back. False
 * positives in the bloom mean B never offers some messages — over many
 * exchanges they propagate via different paths, but a poorly-chosen
 * falsePositiveRate is exactly the kind of bug worth catching here.
 */
private const val BLOOM_FALSE_POSITIVE_RATE = 0.01

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
        val (aToB, bToA) = if (useRbsr) {
            rbsrExchange(rng) { dropped++ }
        } else {
            // src side gets credit for the skip — it's the offerer who saved the bandwidth.
            val a = exchangeOneDirection(nodeA, nodeB, rng, onDrop = { dropped++ },
                onBloomSkip = { bloomSkipped += it; nodeA.recordBloomSkips(it) })
            val b = exchangeOneDirection(nodeB, nodeA, rng, onDrop = { dropped++ },
                onBloomSkip = { bloomSkipped += it; nodeB.recordBloomSkips(it) })
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
                peerOnlineUsers  = mapOf(nodeA.userId to System.currentTimeMillis()),
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
                peerOnlineUsers  = mapOf(nodeB.userId to System.currentTimeMillis()),
                durationMs       = System.currentTimeMillis() - start,
            ))
        }

        return ExchangeMetrics(messagesAtoB, messagesBtoA, dropped,
            System.currentTimeMillis() - start, bloomOffersSkipped = bloomSkipped)
    }

    /**
     * Bidirectional RBSR exchange. Runs the algorithm in lock-step in-process —
     * each side's [Rbsr.respond] consumes the other's outgoing frames. Bounded
     * by [MAX_RBSR_ROUNDS]; leftover diffs after the bound are dropped (will
     * resolve in a subsequent exchange). After convergence, A sends to B
     * exactly the message IDs B didn't have, and vice versa.
     */
    private fun rbsrExchange(rng: Random, onDrop: () -> Unit): Pair<List<RumorMessage>, List<RumorMessage>> {
        val itemsA = nodeA.knownMessages().map { RbsrItem(it.sentAtMs, it.id) }
        val itemsB = nodeB.knownMessages().map { RbsrItem(it.sentAtMs, it.id) }
        val rbsrA = Rbsr(SortedListRbsrStorage(itemsA))
        val rbsrB = Rbsr(SortedListRbsrStorage(itemsB))

        val aPeerNeeds = HashSet<String>()  // messages A has that B doesn't
        val bPeerNeeds = HashSet<String>()  // messages B has that A doesn't

        var framesA = rbsrA.initiate()
        var framesB = rbsrB.initiate()
        for (round in 0 until MAX_RBSR_ROUNDS) {
            val responseA = rbsrA.respond(framesB)
            aPeerNeeds.addAll(responseA.peerNeeds)
            val responseB = rbsrB.respond(framesA)
            bPeerNeeds.addAll(responseB.peerNeeds)
            if (framesA.isEmpty() && framesB.isEmpty()) break
            framesA = responseA.outgoing
            framesB = responseB.outgoing
        }

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
        val onWire = BloomFilterData.deserialize(bloom.serialize(), dstKnownIds.size.coerceAtLeast(64))

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

    val edgeKey: String = edgeKey(nodeA.index, nodeB.index)
}

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
) {
    val totalMessages get() = messagesAtoB + messagesBtoA
    val hasTraffic get() = totalMessages > 0
}
