package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.model.RumorMessage
import kotlin.random.Random

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

        // Offer from messageRepo, not from the scheduler.
        //
        // The scheduler's destructive take (remove-on-read) means messages offered
        // to peer A are gone before peer B can receive them. For a sim where every
        // edge exchanges every tick, that breaks multi-hop propagation entirely.
        // Using the repo snapshot instead mirrors the actual gossip intent: "offer
        // what I know; you filter what you already have." The scheduler remains in
        // use by the real GossipEngine relay pipeline, just not for sim exchange offers.

        // A → B
        val knownB  = nodeB.knownIds()
        val toSendA = nodeA.knownMessages()
            .filter { it.id !in knownB }
            .take(MAX_OFFER_PER_EXCHANGE)
        val deliveredA = mutableListOf<RumorMessage>()
        for (msg in toSendA) {
            if (conditioner.simulate(estimatedBytes(msg), rng) == null) { dropped++; continue }
            deliveredA.add(msg)
            messagesAtoB++
        }
        if (deliveredA.isNotEmpty()) {
            nodeB.deliverExchange(PeerExchangeResult(
                peerUserId       = nodeA.userId,
                messagesReceived = deliveredA,
                ackedByPeer      = emptyList(),
                peerOnlineUsers  = mapOf(nodeA.userId to System.currentTimeMillis()),
                durationMs       = System.currentTimeMillis() - start,
            ))
        }

        // B → A
        val knownA  = nodeA.knownIds()
        val toSendB = nodeB.knownMessages()
            .filter { it.id !in knownA }
            .take(MAX_OFFER_PER_EXCHANGE)
        val deliveredB = mutableListOf<RumorMessage>()
        for (msg in toSendB) {
            if (conditioner.simulate(estimatedBytes(msg), rng) == null) { dropped++; continue }
            deliveredB.add(msg)
            messagesBtoA++
        }
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
