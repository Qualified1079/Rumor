package com.rumor.mesh.core.routing

import com.rumor.mesh.core.Clock
import com.rumor.mesh.core.model.UserMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PersistenceCoordinatorTest {

    private class FakeClock(var now: Long = 1_000_000L) : Clock {
        override fun now(): Long = now
    }

    private fun coord(
        self: String,
        tracker: MeshViewTracker,
        mode: UserMode = UserMode.MOBILE,
    ) = PersistenceCoordinator(self, tracker, selfMode = { mode })

    @Test
    fun `empty view yields no backbone`() {
        val c = FakeClock()
        val t = MeshViewTracker(clock = c)
        val co = coord("self", t)
        val s = co.recompute()
        assertEquals(0, s.plannedLinks)
        assertTrue(co.backbonePeers.isEmpty())
    }

    @Test
    fun `three mobile nodes span into a backbone the self node participates in`() {
        val c = FakeClock()
        // From self=a's perspective: it heard beacons from b and c, and exchanged
        // with both. a(MOBILE cap2) can hold both; the plan spans all three.
        val t = MeshViewTracker(clock = c)
        t.record("b", UserMode.MOBILE, listOf("a", "c"), c.now)
        t.record("c", UserMode.MOBILE, listOf("a", "b"), c.now)
        val co = coord("a", t)
        co.onExchanged("b")
        co.onExchanged("c")
        val s = co.recompute()

        assertEquals(3, s.viewNodes)                       // a (self) + b + c
        // Backbone must span the group (2 links over 3 nodes) and include a.
        assertEquals(2, s.plannedLinks)
        assertTrue(co.backbonePeers.isNotEmpty(), "a should hold at least one backbone link")
        assertTrue(co.backbonePeers.all { it == "b" || it == "c" })
    }

    @Test
    fun `two nodes computing the same view agree on the shared edge`() {
        val c = FakeClock()
        // a and b each assemble the identical set of beacons → identical plan →
        // both independently decide to hold the a—b link (coordinator-free).
        fun trackerFor(): MeshViewTracker = MeshViewTracker(clock = c).apply {
            record("a", UserMode.STATIC, listOf("b"), c.now)
            record("b", UserMode.STATIC, listOf("a"), c.now)
        }
        val ca = PersistenceCoordinator("a", trackerFor(), selfMode = { UserMode.STATIC })
            .also { it.onExchanged("b") }
        val cb = PersistenceCoordinator("b", trackerFor(), selfMode = { UserMode.STATIC })
            .also { it.onExchanged("a") }
        ca.recompute(); cb.recompute()
        assertEquals(setOf("b"), ca.backbonePeers)
        assertEquals(setOf("a"), cb.backbonePeers)
    }

    @Test
    fun `hysteresis holds a dropped link until the streak expires`() {
        val c = FakeClock()
        val t = MeshViewTracker(freshWindowMs = 10_000, clock = c)
        t.record("b", UserMode.STATIC, listOf("a"), c.now)
        val co = coord("a", t, mode = UserMode.STATIC)
        co.onExchanged("b")
        co.recompute()
        assertEquals(setOf("b"), co.backbonePeers)

        // b goes stale — drops out of the view, but the reconciler holds the link
        // through the 3-round window before tearing it down.
        c.now += 10_001
        val s1 = co.recompute()
        assertTrue(s1.removed.isEmpty(), "teardown must not fire on the first absent round")
        assertEquals(setOf("b"), co.backbonePeers)
        co.recompute()
        val s3 = co.recompute() // third absent reconcile → streak reached
        assertTrue(s3.removed.isNotEmpty(), "link should tear down after holdRounds")
        assertTrue(co.backbonePeers.isEmpty())
    }

    @Test
    fun `recent-peer set is MRU-bounded`() {
        val c = FakeClock()
        val t = MeshViewTracker(clock = c)
        val co = PersistenceCoordinator("self", t, selfMode = { UserMode.MOBILE }, recentPeerLimit = 3)
        listOf("a", "b", "c", "d").forEach { co.onExchanged(it) }
        val n = co.beaconNeighbors()
        assertEquals(3, n.size)
        assertFalse("a" in n, "eldest should be evicted")
        assertTrue("d" in n)
    }
}
