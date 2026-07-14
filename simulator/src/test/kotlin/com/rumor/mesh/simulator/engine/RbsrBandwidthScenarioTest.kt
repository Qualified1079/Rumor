package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.sync.MAX_RBSR_ROUNDS
import com.rumor.mesh.core.sync.shouldUseRbsr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * O42 validation: RBSR vs bloom on the summary phase (the cost of discovering
 * what to send, before any payload moves).
 *
 * Finding (measured here): Rumor's RBSR frames are verbose JSON (~130 B each —
 * field names + base64 fingerprint), so the summary-byte crossover with bloom
 * sits at a few-thousand-item set, not the tiny `d log N` the negentropy paper
 * implies (that assumes compact binary framing). Below the crossover bloom is
 * actually cheaper; above it — the O55 long-term-collapse case of months of
 * accumulated traffic — RBSR wins and keeps winning as the set grows, because
 * bloom scales linearly with the set while RBSR scales with the difference.
 *
 * Two properties asserted:
 *   1. At a large accumulated set with a small delta, RBSR moves fewer summary
 *      bytes than a full bloom each way. (Crossover documented in the constant.)
 *   2. RBSR discovers the *exact* symmetric difference — no bloom false positives
 *      silently skipping messages, the failure mode O13/O42 exist to kill. This
 *      one holds at every size and is RBSR's unconditional win.
 */
class RbsrBandwidthScenarioTest {

    private fun msg(seq: Int, tag: String) = RumorMessage(
        id = "$tag-$seq",
        senderId = "author-$tag",
        senderPublicKey = "pk",
        sequenceNumber = seq.toLong(),
        sentAtMs = 1_000L + seq,
        type = MessageType.BROADCAST,
        hopsToLive = 5,
        payload = MessagePayload(ContentType.TEXT, "content-$tag-$seq"),
        signature = "sig",
    )

    /** Two fresh nodes seeded with a shared set plus per-side unique deltas. */
    private fun seededPair(
        scope: CoroutineScope,
        shared: Int,
        uniqueA: Int,
        uniqueB: Int,
        useRbsr: Boolean = false,
        adaptive: Boolean = false,
    ): SimTransport = runBlocking {
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)
        for (i in 0 until shared) {
            val m = msg(i, "shared")
            a.seedMessage(m); b.seedMessage(m)
        }
        for (i in 0 until uniqueA) a.seedMessage(msg(i, "a-only"))
        for (i in 0 until uniqueB) b.seedMessage(msg(i, "b-only"))
        SimTransport(a, b, useRbsr = useRbsr, adaptiveRbsr = adaptive)
    }

    @Test
    fun `rbsr moves fewer summary bytes than bloom on a large accumulated set`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val rng = Random(42)

        // Small-set control (below crossover): bloom is expected to be cheaper —
        // recorded so the crossover is visible, not asserted as an RBSR win.
        val nSmall = 1_000
        val bloomS = seededPair(scope, nSmall, uniqueA = 5, uniqueB = 5, useRbsr = false).exchange(rng)
        val rbsrS = seededPair(scope, nSmall, uniqueA = 5, uniqueB = 5, useRbsr = true).exchange(rng)
        println("O42 summary bytes @N=$nSmall — bloom=${bloomS.summaryBytes} rbsr=${rbsrS.summaryBytes}")

        // Large accumulated set (above crossover — the O55 case): RBSR wins.
        val nLarge = 8_000
        val bloomL = seededPair(scope, nLarge, uniqueA = 5, uniqueB = 5, useRbsr = false).exchange(rng)
        val rbsrL = seededPair(scope, nLarge, uniqueA = 5, uniqueB = 5, useRbsr = true).exchange(rng)
        println("O42 summary bytes @N=$nLarge — bloom=${bloomL.summaryBytes} rbsr=${rbsrL.summaryBytes} " +
            "(rbsr rounds=${rbsrL.rbsrRoundsUsed})")

        assertTrue(
            "at N=$nLarge RBSR summary (${rbsrL.summaryBytes}B) should beat bloom (${bloomL.summaryBytes}B)",
            rbsrL.summaryBytes < bloomL.summaryBytes,
        )
        // Bloom grows with the set; RBSR barely moves for the same small delta.
        assertTrue(
            "bloom cost must grow with the set (linear in N)",
            bloomL.summaryBytes > bloomS.summaryBytes * 4,
        )
        assertTrue("RBSR must converge inside the round cap", rbsrL.rbsrRoundsUsed < MAX_RBSR_ROUNDS)
    }

    @Test
    fun `crossover sweep — informational, picks the adaptive threshold`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val rng = Random(99)
        println("O42 crossover sweep (small delta, bloom@0.01% FP):")
        for (n in listOf(1_000, 2_000, 3_000, 4_000, 5_000, 6_000)) {
            val bloom = seededPair(scope, n, uniqueA = 5, uniqueB = 5, useRbsr = false).exchange(rng)
            val rbsr = seededPair(scope, n, uniqueA = 5, uniqueB = 5, useRbsr = true).exchange(rng)
            val winner = if (rbsr.summaryBytes < bloom.summaryBytes) "RBSR" else "bloom"
            println("  N=%5d bloom=%,7dB rbsr=%,7dB → %s".format(n, bloom.summaryBytes, rbsr.summaryBytes, winner))
        }
    }

    @Test
    fun `adaptive selection uses bloom below the threshold and rbsr above it`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val rng = Random(5)

        // Below RBSR_MIN_SET_SIZE (3000): bloom. rbsrRoundsUsed==0 marks the bloom path.
        val small = seededPair(scope, shared = 1_000, uniqueA = 3, uniqueB = 3, adaptive = true).exchange(rng)
        assertEquals("small store must use bloom", 0, small.rbsrRoundsUsed)
        assertTrue("bloom still moves the delta", small.messagesAtoB > 0 && small.messagesBtoA > 0)

        // Above threshold: RBSR engages (rounds>0) and is exact.
        val large = seededPair(scope, shared = 4_000, uniqueA = 3, uniqueB = 3, adaptive = true).exchange(rng)
        assertTrue("large store must use RBSR", large.rbsrRoundsUsed > 0)
        assertEquals("RBSR exact A→B", 3, large.messagesAtoB)
        assertEquals("RBSR exact B→A", 3, large.messagesBtoA)
    }

    @Test
    fun `shouldUseRbsr is symmetric and gated on capability and larger set`() {
        assertFalse("below threshold → bloom", shouldUseRbsr(true, 2_999, 2_999))
        assertTrue("larger side over threshold triggers", shouldUseRbsr(true, 3_000, 10))
        assertTrue("order-independent", shouldUseRbsr(true, 10, 3_000))
        assertFalse("capability required", shouldUseRbsr(false, 10_000, 10_000))
    }

    @Test
    fun `rbsr discovers the exact symmetric difference`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val rng = Random(7)

        // 100 unique each way — under MAX_OFFER_PER_EXCHANGE so one exchange can
        // carry the whole delta. RBSR is exact: it should surface all 100/100.
        val rbsr = seededPair(scope, shared = 2_000, uniqueA = 100, uniqueB = 100, useRbsr = true).exchange(rng)

        assertEquals("RBSR surfaces every message B lacks", 100, rbsr.messagesAtoB)
        assertEquals("RBSR surfaces every message A lacks", 100, rbsr.messagesBtoA)
        assertTrue("converged within the round cap", rbsr.rbsrRoundsUsed < MAX_RBSR_ROUNDS)
    }
}
