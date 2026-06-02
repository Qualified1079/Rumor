package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Drives SimTransport with and without RBSR over the same starting state and
 * verifies both modes converge on the same delivered set. Confirms the O42
 * algorithm correctness end-to-end (algorithm + lock-step exchange + diff
 * application) in the same harness scenarios use for partition/heal.
 */
class RbsrSimTransportTest {

    @Test
    fun `bloom and RBSR deliver the same set on identical starting state`() = runBlocking {
        val (aBloom, bBloom) = twoNodes()
        seedDifferingSets(aBloom, bBloom)
        val bloomMetrics = SimTransport(aBloom, bBloom, useRbsr = false)
            .exchange(Random(42))

        val (aRbsr, bRbsr) = twoNodes()
        seedDifferingSets(aRbsr, bRbsr)
        val rbsrMetrics = SimTransport(aRbsr, bRbsr, useRbsr = true)
            .exchange(Random(42))

        assertEquals(
            "RBSR must deliver the same A→B count as bloom on the same delta",
            bloomMetrics.messagesAtoB, rbsrMetrics.messagesAtoB,
        )
        assertEquals(
            "RBSR must deliver the same B→A count as bloom on the same delta",
            bloomMetrics.messagesBtoA, rbsrMetrics.messagesBtoA,
        )

        // After one exchange in either mode, both nodes should hold the union.
        val aFinal = aRbsr.knownMessages().mapTo(HashSet()) { it.id }
        val bFinal = bRbsr.knownMessages().mapTo(HashSet()) { it.id }
        val expected = (aRbsr.knownMessages() + bRbsr.knownMessages()).mapTo(HashSet()) { it.id }
        assertEquals("A holds the union after RBSR exchange", expected, aFinal)
        assertEquals("B holds the union after RBSR exchange", expected, bFinal)
    }

    @Test
    fun `RBSR converges with zero exchange when both sides have identical sets`() = runBlocking {
        val (a, b) = twoNodes()
        val shared = (0 until 50).map { synthMessage(it, "shared-$it") }
        for (m in shared) {
            a.messageRepo.insert(m)
            b.messageRepo.insert(m)
        }

        val metrics = SimTransport(a, b, useRbsr = true).exchange(Random(7))
        assertEquals("No delivery needed when sets match", 0, metrics.totalMessages)
    }

    @Test
    fun `RBSR resolves a large symmetric difference within bounded rounds`() = runBlocking {
        // 80 shared + 20 unique each side = 40 total messages to exchange.
        val (a, b) = twoNodes()
        val shared = (0 until 80).map { synthMessage(it, "shared-$it") }
        val aOnly = (0 until 20).map { synthMessage(1000 + it, "a-only-$it") }
        val bOnly = (0 until 20).map { synthMessage(2000 + it, "b-only-$it") }
        for (m in shared) { a.messageRepo.insert(m); b.messageRepo.insert(m) }
        for (m in aOnly) a.messageRepo.insert(m)
        for (m in bOnly) b.messageRepo.insert(m)

        val metrics = SimTransport(a, b, useRbsr = true).exchange(Random(99))
        assertEquals(20, metrics.messagesAtoB)
        assertEquals(20, metrics.messagesBtoA)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun twoNodes(): Pair<SimNode, SimNode> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        return SimNode(0, scope) to SimNode(1, scope)
    }

    private suspend fun seedDifferingSets(a: SimNode, b: SimNode) {
        // 10 shared, 5 unique to A, 5 unique to B.
        val shared = (0 until 10).map { synthMessage(it, "s$it") }
        val aOnly = (0 until 5).map { synthMessage(100 + it, "a$it") }
        val bOnly = (0 until 5).map { synthMessage(200 + it, "b$it") }
        for (m in shared) { a.messageRepo.insert(m); b.messageRepo.insert(m) }
        for (m in aOnly) a.messageRepo.insert(m)
        for (m in bOnly) b.messageRepo.insert(m)
        assertTrue(a.knownMessages().size >= 15)
        assertTrue(b.knownMessages().size >= 15)
    }

    private fun synthMessage(seq: Int, idTag: String): RumorMessage = RumorMessage(
        id = "msg-$idTag",
        senderId = "synthetic-sender",
        senderPublicKey = "",
        sequenceNumber = seq.toLong(),
        sentAtMs = 1_000_000L + seq,
        type = MessageType.BROADCAST,
        hopsToLive = 5,
        payload = MessagePayload(ContentType.TEXT, "content-$idTag"),
        signature = "",
    )
}
