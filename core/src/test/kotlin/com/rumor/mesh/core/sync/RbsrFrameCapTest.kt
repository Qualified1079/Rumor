package com.rumor.mesh.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O172 — a hostile peer can pack ~25–30k Fingerprint frames into one ~4 MB
 * packet, each forcing an O(N) store scan in [Rbsr.respond]. The per-round
 * frame cap bounds that work regardless of how many frames arrive.
 */
class RbsrFrameCapTest {

    // Counts store scans so we can observe how many frames actually got processed.
    private class CountingStorage(private val delegate: RbsrStorage) : RbsrStorage {
        var scans = 0
        override fun items(lower: RbsrBound, upper: RbsrBound): List<RbsrItem> {
            scans++
            return delegate.items(lower, upper)
        }
        override fun fingerprint(lower: RbsrBound, upper: RbsrBound): ByteArray =
            delegate.fingerprint(lower, upper)
    }

    private fun fingerprintFrames(n: Int): List<RbsrFrame> =
        List(n) { RbsrFrame.Fingerprint(RbsrBound.MIN, RbsrBound.MAX, byteArrayOf(0x7F, it.toByte())) }

    @Test
    fun `respond processes at most the per-round frame cap`() {
        val backing = SortedListRbsrStorage((0 until 100).map { RbsrItem(it.toLong(), "id%04d".format(it)) })
        val counting = CountingStorage(backing)
        val rbsr = Rbsr(counting)

        // Flood: far more frames than the cap. Each differing Fingerprint would
        // scan the store, so `scans` is our proxy for frames actually processed.
        rbsr.respond(fingerprintFrames(MAX_RBSR_FRAMES_PER_ROUND * 3))

        assertTrue(
            counting.scans <= MAX_RBSR_FRAMES_PER_ROUND,
            "processed ${counting.scans} frames, cap is $MAX_RBSR_FRAMES_PER_ROUND",
        )
        assertEquals(MAX_RBSR_FRAMES_PER_ROUND, counting.scans, "should process exactly up to the cap")
    }

    @Test
    fun `a legitimate small batch is fully processed`() {
        val backing = SortedListRbsrStorage((0 until 100).map { RbsrItem(it.toLong(), "id%04d".format(it)) })
        val counting = CountingStorage(backing)
        val rbsr = Rbsr(counting)

        val n = 50 // well under the cap — a normal round
        rbsr.respond(fingerprintFrames(n))

        assertEquals(n, counting.scans, "every frame in an under-cap batch must be processed")
    }
}
