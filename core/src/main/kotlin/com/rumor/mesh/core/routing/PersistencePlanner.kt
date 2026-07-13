package com.rumor.mesh.core.routing

import com.rumor.mesh.core.model.UserMode

/**
 * O98 smart persistence — the backbone-selection algorithm.
 *
 * Wi-Fi Direct holds only a bounded number of persistent links per device, so
 * a mesh where everyone wants to stay interconnected cannot be a full graph.
 * Instead we pick a *covering backbone*: a degree-bounded connected subgraph
 * such that every node is reachable over persistent links through a minimal set
 * of relays (the apartment-building case — person 1 reaches person 20 as long
 * as a few nodes hold minimally-overlapping links that span the group).
 *
 * The one hard property this must have is **agreement without a coordinator**.
 * Earlier naive attempts elected a "host" by name, which fragmented the moment
 * two nodes had different discovery views and both self-elected. This planner
 * sidesteps election entirely: [plan] is a pure deterministic function of the
 * shared [MeshView]. Given identical inputs every node computes the identical
 * link set, so when the plan contains edge A—B, *both* A and B independently
 * arrive at "I should hold a persistent link to the other." The deterministic
 * function is the election. Transient disagreement (a node hasn't yet heard
 * everyone's adjacency advertisement) just delays a link until both sides see
 * it — never produces conflicting roles.
 *
 * This layer is deliberately radio-agnostic. It emits an undirected backbone;
 * mapping an edge to an actual Wi-Fi Direct group (who is group-owner, which
 * operating channel) is the transport layer's job. Capacity here is an abstract
 * "how many persistent neighbours will this device sustain," derived from the
 * node's [UserMode] — anchors (FREE/STATIC) become the natural hubs.
 */
object PersistencePlanner {

    /**
     * Persistent-link capacity for a mode. FREE dedicates resources and is the
     * intended infrastructure anchor; STATIC is a willing-but-modest relay;
     * MOBILE is assumed transient and holds only enough to stay attached plus a
     * little redundancy. Deliberately conservative — Wi-Fi Direct group-client
     * counts vary wildly by chipset, and the transport layer clamps further to
     * what a given radio actually sustains.
     */
    fun capacityFor(mode: UserMode): Int = when (mode) {
        UserMode.MOBILE -> 2
        UserMode.STATIC -> 4
        UserMode.FREE   -> 8
    }

    /**
     * Compute the backbone for [view].
     *
     * [redundancy] is the minimum number of persistent links the planner tries
     * to give each node beyond the spanning tree, capped by capacity. 1 leaves
     * a pure tree (minimal links, but a single drop partitions the far side); 2
     * adds a second link per node where capacity allows so no single link is a
     * cut edge for a leaf. Tie-breaking is fully deterministic so redundancy
     * edges are agreed on identically by both endpoints too.
     */
    fun plan(view: MeshView, redundancy: Int = 1): BackbonePlan {
        val nodes = view.modes.keys
        if (nodes.isEmpty()) return BackbonePlan(emptySet())

        val cap = view.modes.mapValues { capacityFor(it.value) }
        val degree = HashMap<String, Int>().apply { nodes.forEach { put(it, 0) } }
        val chosen = LinkedHashSet<Link>()

        // Canonicalise and rank candidate edges once. Higher-capacity endpoints
        // sort first so anchors accrete as hubs; lexicographic (a,b) is the
        // deterministic tie-break that makes the whole computation reproducible.
        val ranked = view.edges
            .map { Link.of(it.first, it.second) }
            .filter { it.a != it.b && it.a in cap && it.b in cap }
            .distinct()
            .sortedWith(
                compareByDescending<Link> { maxOf(cap.getValue(it.a), cap.getValue(it.b)) }
                    .thenByDescending { minOf(cap.getValue(it.a), cap.getValue(it.b)) }
                    .thenBy { it.a }
                    .thenBy { it.b }
            )

        val uf = UnionFind(nodes)

        fun tryAdd(link: Link): Boolean {
            if (link in chosen) return false
            if (degree.getValue(link.a) >= cap.getValue(link.a)) return false
            if (degree.getValue(link.b) >= cap.getValue(link.b)) return false
            chosen += link
            degree[link.a] = degree.getValue(link.a) + 1
            degree[link.b] = degree.getValue(link.b) + 1
            return true
        }

        // Pass 1 — degree-bounded spanning forest (Kruskal, cross-component only).
        for (link in ranked) {
            if (uf.find(link.a) != uf.find(link.b) && tryAdd(link)) {
                uf.union(link.a, link.b)
            }
        }

        // Pass 2 — redundancy. Add intra-component edges so low-degree nodes get
        // a second path, still respecting caps. Skipped for redundancy <= 1.
        if (redundancy > 1) {
            for (link in ranked) {
                val need = degree.getValue(link.a) < minOf(redundancy, cap.getValue(link.a)) ||
                    degree.getValue(link.b) < minOf(redundancy, cap.getValue(link.b))
                if (need) tryAdd(link)
            }
        }

        return BackbonePlan(chosen)
    }
}

/**
 * A snapshot of the mesh as one node currently understands it, assembled from
 * gossiped adjacency/mode advertisements. [modes] is every known node's
 * declared [UserMode]; [edges] is the set of undirected pairs that can hold a
 * persistent link (BLE-reachable / previously-exchanged). Direction is
 * irrelevant here — [PersistencePlanner] canonicalises.
 */
data class MeshView(
    val modes: Map<String, UserMode>,
    val edges: Set<Pair<String, String>>,
)

/** An undirected backbone edge, stored canonically with [a] < [b]. */
data class Link(val a: String, val b: String) {
    companion object {
        fun of(x: String, y: String): Link = if (x <= y) Link(x, y) else Link(y, x)
    }
}

/** The chosen persistent-link set plus per-node lookups. */
data class BackbonePlan(val links: Set<Link>) {
    /** Backbone neighbours of [userId] — the peers it should hold links to. */
    fun persistentPeersOf(userId: String): Set<String> =
        links.mapNotNull {
            when (userId) {
                it.a -> it.b
                it.b -> it.a
                else -> null
            }
        }.toSet()

    /** Number of connected components in the backbone over [nodes]. 1 = fully spanned. */
    fun componentCount(nodes: Set<String>): Int {
        if (nodes.isEmpty()) return 0
        val uf = UnionFind(nodes)
        links.forEach { if (it.a in nodes && it.b in nodes) uf.union(it.a, it.b) }
        return nodes.map { uf.find(it) }.toSet().size
    }
}

private class UnionFind(nodes: Set<String>) {
    private val parent = HashMap<String, String>().apply { nodes.forEach { put(it, it) } }
    private val rank = HashMap<String, Int>().apply { nodes.forEach { put(it, 0) } }

    fun find(x: String): String {
        var root = x
        while (parent.getValue(root) != root) root = parent.getValue(root)
        var cur = x
        while (parent.getValue(cur) != cur) {
            val next = parent.getValue(cur)
            parent[cur] = root
            cur = next
        }
        return root
    }

    fun union(x: String, y: String) {
        val rx = find(x); val ry = find(y)
        if (rx == ry) return
        val rankX = rank.getValue(rx); val rankY = rank.getValue(ry)
        when {
            rankX < rankY -> parent[rx] = ry
            rankX > rankY -> parent[ry] = rx
            else -> { parent[ry] = rx; rank[rx] = rankX + 1 }
        }
    }
}
