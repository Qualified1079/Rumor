package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
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

        // Sort so TRANSFER_METADATA precedes CHUNK — TransferAssembler drops chunks
        // that arrive before their metadata record is registered.
        val deliveredA = exchangeOneDirection(nodeA, nodeB, rng) { dropped++ }
            .sortedBy { if (it.type == MessageType.TRANSFER_METADATA) 0 else 1 }
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

        val deliveredB = exchangeOneDirection(nodeB, nodeA, rng) { dropped++ }
            .sortedBy { if (it.type == MessageType.TRANSFER_METADATA) 0 else 1 }
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

        return ExchangeMetrics(messagesAtoB, messagesBtoA, dropped, System.currentTimeMillis() - start)
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

        return src.knownMessages()
            .asSequence()
            .filter { !onWire.mightContain(it.id) }
            .take(MAX_OFFER_PER_EXCHANGE)
            .filter { msg ->
                conditioner.simulate(estimatedBytes(msg), rng).also { if (it == null) onDrop() } != null
            }
            .toList()
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
) {
    val totalMessages get() = messagesAtoB + messagesBtoA
    val hasTraffic get() = totalMessages > 0
}
