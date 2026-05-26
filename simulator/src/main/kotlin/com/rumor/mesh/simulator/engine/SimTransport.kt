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

        // A → B
        val outboundA = nodeA.takeOutbound(nodeB.userId)
        val knownB    = nodeB.knownIds()
        val toSendA   = outboundA.filter { it.id !in knownB }
        val deliveredA = mutableListOf<RumorMessage>()
        for (msg in toSendA) {
            if (conditioner.simulate(estimatedBytes(msg), rng) == null) { dropped++; continue }
            deliveredA.add(msg)
            messagesAtoB++
        }
        if (deliveredA.isNotEmpty()) {
            nodeB.deliverExchange(PeerExchangeResult(
                peerUserId      = nodeA.userId,
                messagesReceived = deliveredA,
                ackedByPeer     = emptyList(),
                peerOnlineUsers = mapOf(nodeA.userId to System.currentTimeMillis()),
                durationMs      = System.currentTimeMillis() - start,
            ))
        }

        // B → A
        val outboundB = nodeB.takeOutbound(nodeA.userId)
        val knownA    = nodeA.knownIds()
        val toSendB   = outboundB.filter { it.id !in knownA }
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

    private fun estimatedBytes(msg: RumorMessage): Int =
        256 + (msg.payload?.content?.length ?: 0) + (msg.encryptedPayload?.length ?: 0)

    val edgeKey: String = edgeKey(nodeA.index, nodeB.index)

    companion object {
        fun edgeKey(a: Int, b: Int) = "${minOf(a, b)}-${maxOf(a, b)}"
    }
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
