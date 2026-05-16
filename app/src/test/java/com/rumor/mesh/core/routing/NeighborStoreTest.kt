package com.rumor.mesh.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeighborStoreTest {

    @Test
    fun `unknown peer returns neutral overlap`() {
        assertEquals(0.5f, NeighborStore().overlapFor("nobody"), 0.001f)
    }

    @Test
    fun `first update sets overlap`() {
        val s = NeighborStore()
        s.update("alice", 1.0f)
        // EMA: prev=1.0 (same as new on first call), smoothed = 1.0*0.75 + 1.0*0.25 = 1.0
        assertEquals(1.0f, s.overlapFor("alice"), 0.001f)
    }

    @Test
    fun `repeated low-overlap updates trend downward`() {
        val s = NeighborStore()
        s.update("alice", 1.0f)
        repeat(20) { s.update("alice", 0.0f) }
        assertTrue("overlap should approach 0 after many low updates", s.overlapFor("alice") < 0.1f)
    }

    @Test
    fun `selectDiverse returns all candidates when below limit`() {
        val s = NeighborStore()
        val result = s.selectDiverse(listOf("a", "b", "c"), limit = 5)
        assertEquals(setOf("a", "b", "c"), result.toSet())
    }

    @Test
    fun `selectDiverse returns exactly limit peers`() {
        val s = NeighborStore()
        (1..10).forEach { s.update("peer$it", it / 10f) }
        val result = s.selectDiverse((1..10).map { "peer$it" }, limit = 4)
        assertEquals(4, result.size)
    }

    @Test
    fun `selectDiverse prefers low-overlap peers`() {
        val s = NeighborStore()
        s.update("high", 0.9f)
        s.update("low",  0.1f)
        // Ask for 1 — should prefer the low-overlap peer (most novel reach)
        val result = s.selectDiverse(listOf("high", "low"), limit = 1)
        assertEquals("low", result.first())
    }

    @Test
    fun `pruneStale with large window keeps recent entries`() {
        val s = NeighborStore()
        s.update("alice", 0.3f)
        // Prune anything older than a week — alice was just added, survives.
        s.pruneStale(7 * 24 * 60 * 60 * 1000L)
        // overlapFor returns the stored value, not neutral, so alice is still present.
        assertTrue("alice should survive a 7-day prune window", s.overlapFor("alice") < 0.5f)
    }
}
