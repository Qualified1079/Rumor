package com.rumor.mesh.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tier 0 (exact LRU) + Tier 1 (bloom) integration test.
 *
 * Tier 0 alone has the property "evicted id can re-flood once cache turns
 * over." The Tier 1 bloom closes that window — once an id is in the bloom,
 * it stays "seen" essentially forever (modulo the tiny FP rate). These tests
 * exercise exactly that boundary.
 */
class TwoTierDuplicateFilterTest {

    @Test
    fun `genuine novelty accepted and immediate duplicate rejected`() {
        val df = DuplicateFilter(capacity = 10, longTailCapacity = 100)
        assertTrue(df.recordAndCheck("a"), "first sight of 'a' is novel")
        assertFalse(df.recordAndCheck("a"), "immediate duplicate of 'a' is rejected")
    }

    @Test
    fun `id evicted from Tier 0 still rejected via Tier 1 bloom`() {
        // Tier 0 holds 4 entries. Add the target, then 10 more — target is
        // long evicted from Tier 0 but should still hit in Tier 1.
        val df = DuplicateFilter(capacity = 4, longTailCapacity = 1000)
        assertTrue(df.recordAndCheck("target"))
        // Push 10 fresh ids through, evicting "target" from Tier 0.
        for (i in 1..10) assertTrue(df.recordAndCheck("filler-$i"))
        // Tier 0 should no longer hold "target" (capacity exceeded long ago);
        // Tier 1 bloom should still catch it.
        assertFalse(
            df.recordAndCheck("target"),
            "target id should still be rejected via Tier 1 bloom after Tier 0 eviction"
        )
    }

    @Test
    fun `Tier 0 LRU touch keeps hot id from being evicted`() {
        val df = DuplicateFilter(capacity = 4, longTailCapacity = 100)
        assertTrue(df.recordAndCheck("hot"))
        // Add a few fillers, but keep touching "hot" so its LRU position is
        // refreshed each round. After many fills, "hot" should still be in
        // Tier 0 (not evicted to Tier 1) because it was the most-recently-
        // accessed entry each time.
        for (i in 1..20) {
            df.recordAndCheck("filler-$i")
            assertFalse(
                df.recordAndCheck("hot"),
                "touching 'hot' between fillers keeps it in Tier 0 — should be a duplicate hit, not new"
            )
        }
        // 'hot' should be in the live Tier 0 set
        assertTrue("hot" in df.knownIds(), "frequently-touched id stays hot in Tier 0")
    }

    @Test
    fun `single-tier mode when longTailCapacity is 0 reproduces old behavior`() {
        // With Tier 1 disabled, evicted ids CAN re-flood — same as the original
        // single-tier behavior. This test guards the "set to 0 to disable"
        // contract for MCU-class targets per O75.
        val df = DuplicateFilter(capacity = 4, longTailCapacity = 0)
        assertTrue(df.recordAndCheck("evictable"))
        for (i in 1..10) assertTrue(df.recordAndCheck("filler-$i"))
        // Tier 0 evicted; no Tier 1 to catch it → counted as novel again.
        assertTrue(
            df.recordAndCheck("evictable"),
            "with Tier 1 disabled, evicted id is re-accepted (the pre-O85 behavior)"
        )
    }

    @Test
    fun `Tier 0 capacity is the only invariant cap on cache size`() {
        val df = DuplicateFilter(capacity = 10, longTailCapacity = 1000)
        for (i in 1..1000) df.recordAndCheck("id-$i")
        assertEquals(10, df.size(), "Tier 0 size never exceeds capacity even under heavy flow")
    }

    @Test
    fun `many evicted ids all remain rejected via Tier 1`() {
        // Tier 0 holds 4. Insert 200 distinct ids; first 196 are evicted to
        // Tier 1. Verify essentially all evicted ids stay rejected on re-probe.
        // Allow up to 2 misses for bloom-FP slop; the bloom is sized 1000 for
        // ~196 entries so true positives should dominate.
        val df = DuplicateFilter(capacity = 4, longTailCapacity = 1000)
        val all = (1..200).map { "id-$it" }
        for (id in all) assertTrue(df.recordAndCheck(id), "$id should be novel on first sight")
        val evicted = all.dropLast(4)
        val stillRejected = evicted.count { !df.recordAndCheck(it) }
        assertTrue(
            stillRejected >= evicted.size - 2,
            "Tier 1 should reject essentially all evicted ids; only $stillRejected of ${evicted.size}"
        )
    }
}
