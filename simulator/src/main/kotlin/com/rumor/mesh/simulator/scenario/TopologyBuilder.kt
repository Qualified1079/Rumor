package com.rumor.mesh.simulator.scenario

import com.rumor.mesh.simulator.engine.NetworkConditioner
import com.rumor.mesh.simulator.engine.SimNode
import com.rumor.mesh.simulator.engine.SimTransport
import com.rumor.mesh.simulator.params.SimParamRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.random.Random

/**
 * Translates a [Topology] spec into the concrete (nodes, edges) pair the
 * SimWorld consumes via its customTopology hook. Each builder also tags
 * edges and node groups so scenario events ("partition target=bridges")
 * and assertions ("cross-cluster left→right") can address them by name.
 *
 * Group memberships are recorded on a [TopologyMetadata] that scenarios
 * stash alongside the world for the assertion phase to consult.
 */
object TopologyBuilder {

    fun build(
        topology: Topology,
        params: SimParamRegistry,
        rng: Random,
        clock: com.rumor.mesh.core.Clock = com.rumor.mesh.core.SystemClock,
    ): BuildResult {
        // O12 escalation: single-threaded dispatcher so coroutine handler
        // execution order is deterministic across replays. Multi-threaded
        // Dispatchers.Default was producing 6–17% msgDelta on partition/heal
        // scenarios, well past the ±5% tolerance.
        val singleThread = java.util.concurrent.Executors
            .newSingleThreadExecutor { r -> Thread(r, "sim-scope").apply { isDaemon = true } }
            .asCoroutineDispatcher()
        val scope = CoroutineScope(singleThread + SupervisorJob())
        return when (topology) {
            is Topology.Line       -> buildLine(topology, params, rng, scope, clock)
            is Topology.Mesh       -> buildMesh(topology, params, rng, scope, clock)
            is Topology.TwoCluster -> buildTwoCluster(topology, params, rng, scope, clock)
            is Topology.HubSpoke   -> buildHubSpoke(topology, params, rng, scope, clock)
            is Topology.Custom     -> buildCustom(topology, params, rng, scope, clock)
        }
    }

    // ── Line: 0—1—2—…—N-1 ────────────────────────────────────────────────────
    private fun buildLine(t: Topology.Line, p: SimParamRegistry, rng: Random, scope: CoroutineScope, clock: com.rumor.mesh.core.Clock): BuildResult {
        val nodes = (0 until t.length).map { SimNode(it, scope, useBreadcrumbs = p.useBreadcrumbs.value == 1, clock = clock) }
        val edges = (0 until t.length - 1).map { i ->
            SimTransport(nodes[i], nodes[i + 1], conditioner(p, rng), useRbsr = p.useRbsr.value == 1)
        }
        return BuildResult(nodes, edges, TopologyMetadata(groups = mapOf("all" to nodes.map { it.index }.toSet())))
    }

    // ── Mesh: same as dashboard default ──────────────────────────────────────
    private fun buildMesh(t: Topology.Mesh, p: SimParamRegistry, rng: Random, scope: CoroutineScope, clock: com.rumor.mesh.core.Clock): BuildResult {
        val nodes = (0 until t.nodes).map { SimNode(it, scope, useBreadcrumbs = p.useBreadcrumbs.value == 1, clock = clock) }
        val edgeSet = mutableSetOf<String>()
        val edges = mutableListOf<SimTransport>()
        for (node in nodes) {
            val candidates = nodes.filter {
                it.index != node.index && SimTransport.edgeKey(node.index, it.index) !in edgeSet
            }
            for (peer in candidates.shuffled(rng).take(t.connectionsPerNode)) {
                val e = SimTransport(node, peer, conditioner(p, rng), useRbsr = p.useRbsr.value == 1)
                edges.add(e); edgeSet.add(e.edgeKey)
            }
        }
        return BuildResult(nodes, edges, TopologyMetadata(groups = mapOf("all" to nodes.map { it.index }.toSet())))
    }

    // ── TwoCluster: left fully meshed ↔ bridges ↔ right fully meshed ─────────
    private fun buildTwoCluster(t: Topology.TwoCluster, p: SimParamRegistry, rng: Random, scope: CoroutineScope, clock: com.rumor.mesh.core.Clock): BuildResult {
        val total = t.left + t.right + t.bridges
        val nodes = (0 until total).map { SimNode(it, scope, useBreadcrumbs = p.useBreadcrumbs.value == 1, clock = clock) }
        val leftIdx    = (0 until t.left).toSet()
        val rightIdx   = (t.left until t.left + t.right).toSet()
        val bridgeIdx  = (t.left + t.right until total).toSet()
        val edges = mutableListOf<SimTransport>()

        // Full mesh inside each cluster.
        fun fullyMesh(group: Set<Int>) {
            val g = group.toList()
            for (i in g.indices) for (j in i + 1 until g.size) {
                edges.add(SimTransport(nodes[g[i]], nodes[g[j]], conditioner(p, rng), useRbsr = p.useRbsr.value == 1))
            }
        }
        fullyMesh(leftIdx); fullyMesh(rightIdx)

        // Each bridge connects to every node in both clusters, tagged "bridge".
        for (bIdx in bridgeIdx) {
            for (lIdx in leftIdx) {
                edges.add(SimTransport(nodes[bIdx], nodes[lIdx], conditioner(p, rng), tags = setOf("bridge", "left-bridge"), useRbsr = p.useRbsr.value == 1))
            }
            for (rIdx in rightIdx) {
                edges.add(SimTransport(nodes[bIdx], nodes[rIdx], conditioner(p, rng), tags = setOf("bridge", "right-bridge"), useRbsr = p.useRbsr.value == 1))
            }
        }
        return BuildResult(
            nodes, edges,
            TopologyMetadata(groups = mapOf("left" to leftIdx, "right" to rightIdx, "bridges" to bridgeIdx)),
        )
    }

    // ── HubSpoke: center=0, spokes=1..N ──────────────────────────────────────
    private fun buildHubSpoke(t: Topology.HubSpoke, p: SimParamRegistry, rng: Random, scope: CoroutineScope, clock: com.rumor.mesh.core.Clock): BuildResult {
        val nodes = (0..t.spokes).map { SimNode(it, scope, useBreadcrumbs = p.useBreadcrumbs.value == 1, clock = clock) }
        val edges = (1..t.spokes).map { i ->
            SimTransport(nodes[0], nodes[i], conditioner(p, rng), tags = setOf("spoke"), useRbsr = p.useRbsr.value == 1)
        }
        return BuildResult(
            nodes, edges,
            TopologyMetadata(groups = mapOf("center" to setOf(0), "spokes" to (1..t.spokes).toSet())),
        )
    }

    // ── Custom: explicit adjacency list ──────────────────────────────────────
    private fun buildCustom(t: Topology.Custom, p: SimParamRegistry, rng: Random, scope: CoroutineScope, clock: com.rumor.mesh.core.Clock): BuildResult {
        val nodes = (0 until t.nodeCount).map { SimNode(it, scope, useBreadcrumbs = p.useBreadcrumbs.value == 1, clock = clock) }
        val edges = t.edges.map { spec ->
            require(spec.from in 0 until t.nodeCount && spec.to in 0 until t.nodeCount) {
                "Custom topology edge $spec references out-of-range node (nodeCount=${t.nodeCount})"
            }
            SimTransport(nodes[spec.from], nodes[spec.to], conditioner(p, rng), tags = spec.tags.toSet(), useRbsr = p.useRbsr.value == 1)
        }
        // For Custom topology, scenarios using cross-cluster assertions are expected
        // to use named tags + tag-derived groups themselves.
        return BuildResult(nodes, edges, TopologyMetadata(groups = emptyMap()))
    }

    /** Mirror of SimWorld.buildConditioner — same ±25% wobble formula. */
    private fun conditioner(p: SimParamRegistry, rng: Random) = NetworkConditioner().also {
        fun wobble(mean: Double) = mean * (0.75 + rng.nextDouble() * 0.5)
        it.latencyMs            = wobble(p.linkLatencyMs.value.toDouble()).toLong().coerceAtLeast(1L)
        it.jitterMs             = p.linkJitterMs.value
        it.lossRate             = wobble(p.lossRate.value).coerceIn(0.0, 1.0)
        it.bandwidthBytesPerSec = wobble(p.bandwidthKbps.value * 1024.0).toLong().coerceAtLeast(1024L)
    }
}

data class BuildResult(
    val nodes: List<SimNode>,
    val edges: List<SimTransport>,
    val metadata: TopologyMetadata,
)

/** Named groups of node indices for assertions to address. */
data class TopologyMetadata(val groups: Map<String, Set<Int>>)
