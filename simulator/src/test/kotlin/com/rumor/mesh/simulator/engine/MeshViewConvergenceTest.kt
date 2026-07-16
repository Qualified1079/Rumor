package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.routing.MeshViewTracker
import com.rumor.mesh.core.routing.PersistencePlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * O98 step 3 — the MeshView substrate end-to-end in the simulator.
 *
 * Where [SmartPersistenceScenarioTest] (Phase 2) hand-builds a [MeshView] and
 * checks the planner, this drives the *assembler*: nodes emit real
 * SELF_PRESENCE beacons (carrying mode + `recentlyExchangedWith` adjacency),
 * the beacons propagate over the sim transport, each node's own
 * [MeshViewTracker] assembles a view from what it received, and we assert the
 * crucial coordinator-free property: once beacons settle, **every node's
 * assembled view yields the identical backbone plan**.
 */
class MeshViewConvergenceTest {

    /** Broadcast every node's SELF_PRESENCE to every other node (full gossip flood). */
    private suspend fun floodBeacons(nodes: List<SimNode>) {
        for (n in nodes) {
            val neighbours = nodes.filter { it !== n }.map { it.userId }
            n.gossipEngine.composeSelfPresence(n.mode, neighbours)
            n.flushSchedulerToRepo()
        }
        // Exchange every ordered pair a few rounds so every beacon reaches every node.
        repeat(3) {
            for (a in nodes) for (b in nodes) if (a !== b) {
                SimTransport(a, b).exchange(Random(1))
            }
        }
        // onExchange → processIncoming → handleSelfPresence runs async on each
        // engine's scope; wait for the trackers to absorb the delivered beacons.
        val ids = nodes.map { it.userId }.toSet()
        awaitUntil(timeoutMs = 5_000) {
            nodes.all { n -> (ids - n.userId).all { it in n.meshView.assembleView().modes.keys } }
        }
    }

    @Test
    fun `all nodes converge on the same backbone plan from gossiped beacons`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val clock = SimClock(1_000_000L)
        // 6 nodes: two FREE anchors, the rest MOBILE/STATIC.
        val modes = listOf(
            UserMode.FREE, UserMode.MOBILE, UserMode.STATIC,
            UserMode.FREE, UserMode.MOBILE, UserMode.STATIC,
        )
        val nodes = modes.mapIndexed { i, m -> SimNode(i, scope, clock = clock).also { it.mode = m } }

        floodBeacons(nodes)

        val ids = nodes.map { it.userId }.toSet()
        for (n in nodes) {
            val view = n.meshView.assembleView()
            // Each node learns every OTHER node from the beacons (it never
            // beacons about itself, so its own id may or may not be present).
            assertTrue(
                "node ${n.userId} should have learned the other ${ids.size - 1} peers, saw ${view.modes.keys}",
                (ids - n.userId).all { it in view.modes.keys },
            )
        }

        // Each node assembles its view INCLUDING itself (a node never beacons
        // about itself, so the planner-caller injects self). With every node
        // holding the full set, the coordinator-free property must hold:
        // identical plans across nodes from the same pure function.
        fun viewOf(n: SimNode) = n.meshView.assembleView(
            selfId = n.userId, selfMode = n.mode,
            selfNeighbors = nodes.filter { it !== n }.map { it.userId },
        )
        val fullViewNodes = nodes.filter { viewOf(it).modes.keys.containsAll(ids) }
        assertEquals("every node should hold the full view", nodes.size, fullViewNodes.size)
        val plan0 = PersistencePlanner.plan(viewOf(nodes[0])).links
        for (n in nodes.drop(1)) {
            val plan = PersistencePlanner.plan(viewOf(n)).links
            assertEquals("node ${n.userId} plan diverged", plan0, plan)
        }

        // The plan is a real covering backbone: connected + degree-bounded.
        val view0 = viewOf(nodes[0])
        val plan = PersistencePlanner.plan(view0)
        assertEquals("backbone must span all nodes", 1, plan.componentCount(view0.modes.keys))
        for (id in view0.modes.keys) {
            val degree = plan.persistentPeersOf(id).size
            val cap = PersistencePlanner.capacityFor(view0.modes.getValue(id))
            assertTrue("$id degree $degree exceeds cap $cap", degree <= cap)
        }
    }

    @Test
    fun `a node that stops beaconing drops out of the view after decay`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val clock = SimClock(1_000_000L)
        val nodes = (0..3).map { SimNode(it, scope, clock = clock).also { n -> n.mode = UserMode.STATIC } }
        floodBeacons(nodes)

        val observer = nodes[0]
        assertTrue(observer.meshView.assembleView().modes.size >= 3)

        // Advance the shared sim clock past the freshness window with no new
        // beacons — the tracker decays every entry it hasn't re-heard.
        clock.nowMs += MeshViewTracker.DEFAULT_FRESH_WINDOW_MS + 1
        assertTrue(
            "stale anchors must drop out of the assembled view",
            observer.meshView.assembleView().modes.isEmpty(),
        )
    }
}
