package com.rumor.mesh.core.routing

import com.rumor.mesh.core.Clock
import com.rumor.mesh.core.model.UserMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeshViewTrackerTest {

    private class FakeClock(var now: Long = 1_000_000L) : Clock {
        override fun now(): Long = now
    }

    private fun tracker(clock: Clock, fresh: Long = 10_000, max: Int = 100) =
        MeshViewTracker(freshWindowMs = fresh, maxPeers = max, clock = clock)

    @Test
    fun `newer beacon wins, older ignored`() {
        val c = FakeClock()
        val t = tracker(c)
        assertTrue(t.record("a", UserMode.STATIC, emptyList(), c.now - 100))
        assertFalse(t.record("a", UserMode.MOBILE, emptyList(), c.now - 200)) // older → ignored
        assertEquals(UserMode.STATIC, t.modeFor("a"))
        assertTrue(t.record("a", UserMode.FREE, emptyList(), c.now)) // newer → wins
        assertEquals(UserMode.FREE, t.modeFor("a"))
    }

    @Test
    fun `future timestamp is clamped so it cannot pin forever`() {
        val c = FakeClock()
        val t = tracker(c, fresh = 10_000)
        t.record("a", UserMode.FREE, emptyList(), c.now + 1_000_000) // far future
        // Clamped to now, so after freshWindow it decays like any honest beacon.
        c.now += 10_001
        assertEquals(UserMode.MOBILE, t.modeFor("a"))
    }

    @Test
    fun `stale beacon decays to MOBILE and drops from the view`() {
        val c = FakeClock()
        val t = tracker(c, fresh = 10_000)
        t.record("a", UserMode.FREE, listOf("b"), c.now)
        t.record("b", UserMode.STATIC, listOf("a"), c.now)
        assertEquals(2, t.assembleView().modes.size)
        c.now += 10_001 // both go stale
        assertEquals(UserMode.MOBILE, t.modeFor("a"))
        assertTrue(t.assembleView().modes.isEmpty())
    }

    @Test
    fun `assembleView keeps only edges where both endpoints are known and fresh`() {
        val c = FakeClock()
        val t = tracker(c)
        t.record("a", UserMode.FREE, listOf("b", "ghost"), c.now)   // ghost never beacons
        t.record("b", UserMode.STATIC, listOf("a"), c.now)
        val view = t.assembleView()
        assertEquals(setOf("a", "b"), view.modes.keys)
        // a—b kept (both known); a—ghost dropped (ghost unknown).
        assertEquals(setOf("a" to "b"), view.edges)
    }

    @Test
    fun `edges are canonical and deduped across both directions`() {
        val c = FakeClock()
        val t = tracker(c)
        t.record("z", UserMode.STATIC, listOf("a"), c.now)
        t.record("a", UserMode.STATIC, listOf("z"), c.now)
        assertEquals(setOf("a" to "z"), t.assembleView().edges) // one canonical edge, not two
    }

    @Test
    fun `map is bounded by maxPeers with LRU-by-beacon eviction`() {
        val c = FakeClock()
        val t = tracker(c, max = 3)
        t.record("a", UserMode.STATIC, emptyList(), c.now); c.now += 1
        t.record("b", UserMode.STATIC, emptyList(), c.now); c.now += 1
        t.record("c", UserMode.STATIC, emptyList(), c.now); c.now += 1
        t.record("d", UserMode.STATIC, emptyList(), c.now) // evicts eldest (a)
        assertEquals(UserMode.MOBILE, t.modeFor("a")) // gone → default
        assertEquals(UserMode.STATIC, t.modeFor("d"))
        assertEquals(3, t.assembleView().modes.size)
    }

    @Test
    fun `feeds a planner-consumable MeshView`() {
        val c = FakeClock()
        val t = tracker(c)
        // Small line: a(FREE) — b(STATIC) — c(MOBILE)
        t.record("a", UserMode.FREE, listOf("b"), c.now)
        t.record("b", UserMode.STATIC, listOf("a", "c"), c.now)
        t.record("c", UserMode.MOBILE, listOf("b"), c.now)
        val plan = PersistencePlanner.plan(t.assembleView())
        assertEquals(1, plan.componentCount(setOf("a", "b", "c"))) // fully spanned
    }
}
