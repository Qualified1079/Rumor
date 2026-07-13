package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.routing.BackbonePlan
import com.rumor.mesh.core.routing.Link
import com.rumor.mesh.core.routing.MeshView
import com.rumor.mesh.core.routing.PersistencePlanner
import com.rumor.mesh.core.routing.PersistenceReconciler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O98 Phase 2 — the apartment-building scenario the feature exists for.
 *
 * 20 residents want to stay interconnected but Wi-Fi Direct only sustains a
 * handful of persistent links per phone. Nobody sees the whole building over
 * BLE — each phone only reaches its floor and the one above/below. The planner
 * has to pick a covering backbone through a few anchors so resident 1 can still
 * reach resident 20, and it has to do it with no coordinator: every phone runs
 * the same pure function on the shared view and independently agrees on which
 * links to hold.
 *
 * These tests drive the real [PersistencePlanner] / [PersistenceReconciler]
 * across evolving multi-node views — the convergence and churn behaviour the
 * single-shot core unit tests can't exercise.
 */
class SmartPersistenceScenarioTest {

    // 4 units per floor, 5 floors = 20 residents. A phone BLE-sees its own
    // floor and the immediately adjacent floors (orthogonal/diagonal grid
    // adjacency, range ~1 floor). Sparse and connected — a covering backbone
    // is non-trivial, unlike a full mesh.
    private val floors = 5
    private val units = 4
    private fun id(floor: Int, unit: Int) = "r%d-%d".format(floor, unit)
    private val everyone: List<String> =
        (0 until floors).flatMap { f -> (0 until units).map { u -> id(f, u) } }

    private fun buildingEdges(): Set<Pair<String, String>> = buildSet {
        for (f in 0 until floors) for (u in 0 until units) {
            // same floor, next unit
            if (u + 1 < units) add(id(f, u) to id(f, u + 1))
            // floor above: same unit + diagonals (stairwell/ceiling proximity)
            if (f + 1 < floors) {
                add(id(f, u) to id(f + 1, u))
                if (u + 1 < units) add(id(f, u) to id(f + 1, u + 1))
                if (u - 1 >= 0) add(id(f, u) to id(f + 1, u - 1))
            }
        }
    }

    // Two hallway-plugged phones per-ish region act as FREE anchors; the rest
    // are MOBILE handhelds.
    private val anchors = setOf(id(1, 0), id(3, 2))
    private fun modes(exclude: Set<String> = emptySet()): Map<String, UserMode> =
        everyone.filter { it !in exclude }
            .associateWith { if (it in anchors) UserMode.FREE else UserMode.MOBILE }

    private fun degreeOf(plan: BackbonePlan, node: String) =
        plan.links.count { it.a == node || it.b == node }

    /** Every node's view once adjacency ads have fully propagated = the global view. */
    private fun globalView(exclude: Set<String> = emptySet()): MeshView {
        val m = modes(exclude)
        val e = buildingEdges().filter { it.first in m && it.second in m }.toSet()
        return MeshView(m, e)
    }

    @Test
    fun `converged backbone spans all 20 residents within capacity`() {
        val plan = PersistencePlanner.plan(globalView(), redundancy = 1)

        assertEquals(
            "backbone must connect the whole building",
            1, plan.componentCount(everyone.toSet()),
        )
        for (node in everyone) {
            val cap = PersistencePlanner.capacityFor(modes().getValue(node))
            assertTrue("$node exceeds capacity", degreeOf(plan, node) <= cap)
        }
    }

    @Test
    fun `covering backbone is far smaller than a full mesh`() {
        val plan = PersistencePlanner.plan(globalView(), redundancy = 1)
        // Spanning tree over 20 nodes = 19 links. Full mesh would be 190.
        assertEquals(19, plan.links.size)
        assertTrue(plan.links.size < everyone.size * (everyone.size - 1) / 2)
    }

    @Test
    fun `all residents independently compute the identical backbone`() {
        // The election property at building scale: 20 phones, each computing the
        // plan from the same converged view, must land on the same link set —
        // otherwise two phones would disagree about who owns a link.
        val reference = PersistencePlanner.plan(globalView())
        repeat(20) { trial ->
            // Re-derive with shuffled iteration order to stand in for each
            // phone's independently-assembled (but logically identical) view.
            val v = globalView()
            val shuffled = MeshView(
                modes = LinkedHashMap<String, UserMode>().apply {
                    v.modes.entries.shuffled().forEach { put(it.key, it.value) }
                },
                edges = v.edges.map { (x, y) -> if (trial % 2 == 0) x to y else y to x }
                    .shuffled().toSet(),
            )
            assertEquals("resident $trial disagreed", reference.links, PersistencePlanner.plan(shuffled).links)
        }
    }

    @Test
    fun `during partial-view convergence no phone ever exceeds its own capacity`() {
        // Model each phone learning the building hop-by-hop. Even mid-convergence
        // — when views disagree — a phone must never decide to hold more links
        // than its capacity for the links incident to itself.
        val adj: Map<String, Set<String>> = everyone.associateWith { n ->
            globalView().edges.mapNotNull { (a, b) -> when (n) { a -> b; b -> a; else -> null } }.toSet()
        }
        for (radius in 1..6) {
            for (self in everyone) {
                val visible = bfsWithin(self, adj, radius)
                val m = modes().filterKeys { it in visible }
                val e = globalView().edges.filter { it.first in visible && it.second in visible }.toSet()
                val localPlan = PersistencePlanner.plan(MeshView(m, e))
                val cap = PersistencePlanner.capacityFor(modes().getValue(self))
                assertTrue(
                    "$self exceeded cap $cap at radius $radius",
                    degreeOf(localPlan, self) <= cap,
                )
            }
        }
    }

    @Test
    fun `losing an anchor re-spans through the reconciler with bounded churn`() {
        val reconciler = PersistenceReconciler(holdRounds = 3)

        // Steady state: everyone up, backbone established.
        val before = PersistencePlanner.plan(globalView())
        reconciler.reconcile(before)
        assertEquals("should be established", before.links, reconciler.activeLinks())

        // Anchor r3-2 goes offline (unplugged and pocketed). Re-plan without it.
        val fallen = id(3, 2)
        val after = PersistencePlanner.plan(globalView(exclude = setOf(fallen)))

        // Hysteresis: links to the fallen anchor are not dropped on the first
        // reconcile — they age through the hold window.
        val d1 = reconciler.reconcile(after)
        assertTrue("no teardown before hold window expires", d1.toRemove.isEmpty())

        // New links to re-span around the gap are added immediately.
        val newlyAdded = after.links - before.links
        assertTrue("expected re-spanning links to be added at once", newlyAdded.all { it in reconciler.activeLinks() })

        // Age out the stale links.
        val d2 = reconciler.reconcile(after)
        val d3 = reconciler.reconcile(after)
        val eventuallyRemoved = d1.toRemove + d2.toRemove + d3.toRemove

        // Only links touching the fallen anchor should ever be torn down —
        // residents nowhere near the change keep their links untouched.
        assertTrue(
            "teardown must be local to the fallen anchor: $eventuallyRemoved",
            eventuallyRemoved.all { it.a == fallen || it.b == fallen },
        )

        // And the survivors still form one connected backbone.
        val survivors = everyone.filter { it != fallen }.toSet()
        assertEquals(1, after.componentCount(survivors))
    }

    private fun bfsWithin(start: String, adj: Map<String, Set<String>>, radius: Int): Set<String> {
        val seen = linkedSetOf(start)
        var frontier = setOf(start)
        repeat(radius) {
            val next = frontier.flatMap { adj[it].orEmpty() }.toSet() - seen
            seen += next
            frontier = next
        }
        return seen
    }
}
