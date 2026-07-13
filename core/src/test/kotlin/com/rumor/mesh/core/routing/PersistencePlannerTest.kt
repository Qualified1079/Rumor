package com.rumor.mesh.core.routing

import com.rumor.mesh.core.model.UserMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistencePlannerTest {

    private fun view(
        modes: Map<String, UserMode>,
        edges: Set<Pair<String, String>>,
    ) = MeshView(modes, edges)

    /** Fully-meshed adjacency (everyone BLE-sees everyone) over [ids]. */
    private fun fullMesh(ids: List<String>): Set<Pair<String, String>> =
        buildSet { for (i in ids.indices) for (j in i + 1 until ids.size) add(ids[i] to ids[j]) }

    private fun degreeOf(plan: BackbonePlan, id: String) =
        plan.links.count { it.a == id || it.b == id }

    // --- the load-bearing property: agreement without a coordinator ----------

    @Test
    fun `plan is deterministic across shuffled input order`() {
        val ids = (1..12).map { "u%02d".format(it) }
        val modes = ids.associateWith { if (it == "u01") UserMode.FREE else UserMode.MOBILE }
        val edges = fullMesh(ids)

        val a = PersistencePlanner.plan(view(modes, edges))
        // Same logical view, different Set/Map iteration and edge orientation.
        val shuffledEdges = edges.map { (x, y) -> y to x }.shuffled().toSet()
        val shuffledModes = LinkedHashMap<String, UserMode>().apply {
            modes.entries.shuffled().forEach { put(it.key, it.value) }
        }
        val b = PersistencePlanner.plan(view(shuffledModes, shuffledEdges))

        assertEquals("Backbone must not depend on input ordering", a.links, b.links)
    }

    @Test
    fun `both endpoints of every link independently agree they hold it`() {
        // The election property: because the plan is a pure function of the
        // shared view, node X's persistentPeers and node Y's persistentPeers
        // are mutually consistent for every chosen edge — no coordinator.
        val ids = (1..8).map { "n$it" }
        val modes = ids.associateWith { UserMode.STATIC }
        val plan = PersistencePlanner.plan(view(modes, fullMesh(ids)))

        for (link in plan.links) {
            assertTrue(link.b in plan.persistentPeersOf(link.a))
            assertTrue(link.a in plan.persistentPeersOf(link.b))
        }
    }

    // --- correctness constraints ---------------------------------------------

    @Test
    fun `no node exceeds its capacity`() {
        val ids = (1..20).map { "u%02d".format(it) }
        // One FREE hub, a few STATIC, rest MOBILE.
        val modes = ids.associateWith {
            when (it) {
                "u01" -> UserMode.FREE
                "u02", "u03" -> UserMode.STATIC
                else -> UserMode.MOBILE
            }
        }
        val plan = PersistencePlanner.plan(view(modes, fullMesh(ids)), redundancy = 2)

        for (id in ids) {
            val cap = PersistencePlanner.capacityFor(modes.getValue(id))
            assertTrue(
                "$id degree ${degreeOf(plan, id)} exceeds cap $cap",
                degreeOf(plan, id) <= cap,
            )
        }
    }

    @Test
    fun `backbone spans everyone when capacity allows`() {
        val ids = (1..15).map { "u%02d".format(it) }
        // A couple of FREE anchors give enough hub capacity to span 15 nodes.
        val modes = ids.associateWith {
            when (it) { "u01", "u08" -> UserMode.FREE; else -> UserMode.MOBILE }
        }
        val plan = PersistencePlanner.plan(view(modes, fullMesh(ids)))

        assertEquals(
            "Backbone must be a single connected component",
            1, plan.componentCount(ids.toSet()),
        )
        // A spanning tree over N nodes has exactly N-1 edges.
        assertEquals(ids.size - 1, plan.links.size)
    }

    @Test
    fun `redundancy 2 gives interior leaves a second link`() {
        val ids = (1..10).map { "u%02d".format(it) }
        val modes = ids.associateWith {
            when (it) { "u01", "u02" -> UserMode.FREE; else -> UserMode.MOBILE }
        }
        val tree = PersistencePlanner.plan(view(modes, fullMesh(ids)), redundancy = 1)
        val redundant = PersistencePlanner.plan(view(modes, fullMesh(ids)), redundancy = 2)

        assertTrue(
            "Redundancy pass must add links beyond the spanning tree",
            redundant.links.size > tree.links.size,
        )
        // Every MOBILE node has cap 2, so redundancy=2 should leave none at degree 1.
        val stranded = ids.filter {
            modes.getValue(it) == UserMode.MOBILE && degreeOf(redundant, it) < 2
        }
        assertTrue("MOBILE nodes should reach degree 2 under redundancy=2: $stranded", stranded.isEmpty())
    }

    @Test
    fun `sparse adjacency yields a forest, not a false claim of connectivity`() {
        // Two disjoint pairs that cannot see each other — honest partition.
        val modes = mapOf(
            "a" to UserMode.FREE, "b" to UserMode.MOBILE,
            "c" to UserMode.FREE, "d" to UserMode.MOBILE,
        )
        val edges = setOf("a" to "b", "c" to "d")
        val plan = PersistencePlanner.plan(view(modes, edges))

        assertEquals(2, plan.componentCount(modes.keys))
        assertEquals(setOf(Link.of("a", "b"), Link.of("c", "d")), plan.links)
    }

    @Test
    fun `anchors are preferred as hubs`() {
        // Star-capable: one FREE node adjacent to many MOBILE leaves. The FREE
        // node should carry the high degree, not an arbitrary MOBILE node.
        val ids = listOf("hub") + (1..6).map { "leaf$it" }
        val modes = ids.associateWith { if (it == "hub") UserMode.FREE else UserMode.MOBILE }
        val plan = PersistencePlanner.plan(view(modes, fullMesh(ids)))

        assertTrue("FREE anchor should hold the most links", degreeOf(plan, "hub") >= 4)
    }

    @Test
    fun `empty and single-node views are handled`() {
        assertTrue(PersistencePlanner.plan(view(emptyMap(), emptySet())).links.isEmpty())
        val solo = PersistencePlanner.plan(view(mapOf("only" to UserMode.MOBILE), emptySet()))
        assertTrue(solo.links.isEmpty())
        assertEquals(1, solo.componentCount(setOf("only")))
    }
}
