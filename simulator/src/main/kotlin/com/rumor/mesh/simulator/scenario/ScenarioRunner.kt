package com.rumor.mesh.simulator.scenario

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.simulator.engine.SimWorld
import com.rumor.mesh.simulator.engine.WorldMetrics
import com.rumor.mesh.simulator.params.SimParamRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.random.Random

private const val TAG = "ScenarioRunner"

/**
 * Headless scenario executor. Builds the topology, applies overrides, runs
 * the world for [Scenario.durationSec] sim seconds while firing events on
 * the sim-time clock, then evaluates assertions.
 *
 * Returns a [ScenarioResult] suitable for serializing into the bundle.
 */
class ScenarioRunner {

    fun run(
        scenario: Scenario,
        /**
         * Periodic in-scenario progress callback. Called from the tick loop
         * each polled second with a `0.0..1.0` fraction of `simTimeMs /
         * durationMs`. Default is a no-op for callers that don't care.
         */
        onProgress: (Float) -> Unit = {},
    ): ScenarioResult = runBlocking {
        val params = SimParamRegistry()
        // Seed deterministically per scenario so replays line up.
        params.seed.value = scenario.seed
        params.speedMultiplier.value = scenario.speedMult
        scenario.params.forEach { (k, v) ->
            params.byId(k)?.setFromString(v)
                ?: RumorLog.w(TAG, "scenario '${scenario.name}': unknown param '$k', ignoring")
        }
        params.enforceInvariants()

        val world = SimWorld(params)
        val build = TopologyBuilder.build(scenario.topology, params, Random(scenario.seed))
        world.customTopology = { _ -> build.nodes to build.edges }

        // Trace samples; we snapshot lightly each second.
        val trace = mutableListOf<TraceSample>()

        // Event timeline indexed by ascending atSec.
        val pendingEvents = scenario.events.sortedBy { it.atSec }.toMutableList()

        world.start()
        val deadlineMs = scenario.durationSec * 1000L
        val pollIntervalMs = 100L
        // Safety: cap wall-clock so a paused engine (heap pressure / hang) can't loop forever.
        // The cap was originally 5× expected, plus 30s grace; in practice heavy-traffic
        // scenarios (high broadcaster_mult, large topologies, bursty params) can exceed
        // that even when the engine is healthy. Bumped to 15× expected plus 60s grace
        // so a 120s sim at 10× has up to ~3 minutes of wall time before declaring
        // failure. A genuinely hung engine still gets caught — just less aggressively.
        val wallStartMs = System.currentTimeMillis()
        val maxWallMs = (deadlineMs / params.speedMultiplier.value).toLong() * 15L + 60_000L

        var lastTracedSec = -1L
        val errors = mutableListOf<String>()
        while (world.simTimeMs.value < deadlineMs) {
            if (!world.running.value) {
                errors.add("engine paused mid-run at simTimeMs=${world.simTimeMs.value} (heap pressure or stop event)")
                break
            }
            if (System.currentTimeMillis() - wallStartMs > maxWallMs) {
                errors.add("wall-clock timeout (${maxWallMs}ms) before simTime reached ${deadlineMs}ms")
                break
            }
            while (pendingEvents.isNotEmpty() && pendingEvents.first().atSec * 1000L <= world.simTimeMs.value) {
                applyEvent(world, params, pendingEvents.removeAt(0))
            }
            if (scenario.trace) {
                val curSec = world.simTimeMs.value / 1000L
                // Sample per-node every 5 sim-seconds; aggregate metrics every
                // 1 sim-second. Per-node snapshots allocate O(nodes) per call
                // and dominate trace overhead on 50+ node scenarios.
                if (curSec != lastTracedSec) {
                    val perNode = if (curSec % 5L == 0L) {
                        world.nodes.value.map { n ->
                            NodeTraceSample(
                                index = n.index,
                                queueDepth = n.schedulerQueueDepth,
                                messagesProcessed = n.messagesProcessed.value,
                                dupDrops = n.dupDrops.value,
                                bloomSkips = n.bloomSkips.value,
                            )
                        }
                    } else emptyList()
                    trace.add(world.metrics.value.toSample().copy(nodes = perNode))
                    lastTracedSec = curSec
                }
            }
            onProgress((world.simTimeMs.value.toFloat() / deadlineMs.coerceAtLeast(1L)).coerceIn(0f, 1f))
            delay(pollIntervalMs)
        }
        world.stop()

        // Snapshot final metrics + per-node message data for assertion evaluation.
        val finalMetrics = world.metrics.value
        val nodes = world.nodes.value
        val nodeUserId = nodes.associate { it.index to it.userId }
        // Per node: (msgId -> RumorMessage) snapshot. Enables filtering by senderId/trustLevel.
        val nodeMessages = nodes.associate { n -> n.index to n.knownMessages().associateBy { it.id } }
        // userId -> nodeIndex; used to map a message's senderId back to its origin cluster.
        val userIdToIndex = nodeUserId.entries.associate { (idx, uid) -> uid to idx }

        val reassembledTotal = nodes.sumOf { it.reassembledTransfers.value }
        val assertionResults = scenario.assertions.map { a ->
            evaluateAssertion(a, scenario, build.metadata, finalMetrics, nodeMessages, nodeUserId, userIdToIndex, reassembledTotal)
        }

        ScenarioResult(
            name = scenario.name,
            passed = assertionResults.all { it.passed } && errors.isEmpty(),
            finalMetrics = finalMetrics.toSample(),
            assertions = assertionResults,
            trace = trace,
            errors = errors,
        )
    }

    private fun applyEvent(world: SimWorld, params: SimParamRegistry, event: ScenarioEvent) {
        when (event) {
            is ScenarioEvent.Partition -> {
                val n = world.setPartitionByTag(event.target, true)
                RumorLog.i(TAG, "partition '${event.target}': $n edges affected")
            }
            is ScenarioEvent.Heal -> {
                val n = world.setPartitionByTag(event.target, false)
                RumorLog.i(TAG, "heal '${event.target}': $n edges restored")
            }
            is ScenarioEvent.KillNode -> {
                world.killNode(event.nodeIndex)
                RumorLog.i(TAG, "killed node ${event.nodeIndex}")
            }
            is ScenarioEvent.SetParam -> {
                params.byId(event.paramId)?.setFromString(event.value)
                params.enforceInvariants()
                RumorLog.i(TAG, "set ${event.paramId} = ${event.value}")
            }
        }
    }

    private fun evaluateAssertion(
        assertion: Assertion,
        scenario: Scenario,
        meta: TopologyMetadata,
        finalMetrics: WorldMetrics,
        nodeMessages: Map<Int, Map<String, com.rumor.mesh.core.model.RumorMessage>>,
        nodeUserId: Map<Int, String>,
        userIdToIndex: Map<String, Int>,
        reassembledTotal: Long,
    ): AssertionResult = when (assertion) {
        is Assertion.DeterministicReplay -> {
            // Re-run a fresh copy from scratch and compare cumulative metrics.
            val replay = ScenarioRunner().run(
                // Strip the DeterministicReplay assertion to avoid infinite recursion.
                scenario.copy(assertions = scenario.assertions.filterNot { it is Assertion.DeterministicReplay })
            )
            val a = finalMetrics.totalMessages
            val b = replay.finalMetrics.totalMessages
            val c = finalMetrics.totalDropped
            val d = replay.finalMetrics.totalDropped
            // Allow ±5% variance: GossipEngine runs handlers on Dispatchers.Default so
            // coroutine scheduling order is not guaranteed across runs (O12). Drops also
            // vary slightly because ConcurrentHashMap iteration order affects which RNG
            // values are consumed by the conditioner; empirically <0.1% but not zero.
            val tolerance = 0.05
            val msgMatch  = a == 0L || kotlin.math.abs(a - b).toDouble() / a <= tolerance
            val dropMatch = c == 0L || kotlin.math.abs(c - d).toDouble() / c <= tolerance
            val match = msgMatch && dropMatch
            val dropDelta = if (c > 0) "%.1f%%".format(kotlin.math.abs(c-d).toDouble()/c*100) else "n/a"
            AssertionResult(
                type = "deterministic-replay",
                passed = match,
                detail = "first=(msgs=$a, dropped=$c) replay=(msgs=$b, dropped=$d) " +
                    "msgDelta=${if (a > 0) "%.1f%%".format(kotlin.math.abs(a-b).toDouble()/a*100) else "n/a"} " +
                    "dropDelta=$dropDelta",
            )
        }

        is Assertion.MinTotalMessages -> AssertionResult(
            type = "min-total-messages",
            passed = finalMetrics.totalMessages >= assertion.min,
            detail = "actual=${finalMetrics.totalMessages} min=${assertion.min}",
        )

        is Assertion.HeapBounded -> AssertionResult(
            type = "heap-bounded",
            passed = finalMetrics.heapUsedMb <= assertion.maxMb,
            detail = "heapUsedMb=${finalMetrics.heapUsedMb} maxMb=${assertion.maxMb}",
        )

        is Assertion.CrossClusterDelivery -> {
            val from = meta.groups[assertion.fromCluster]
            val to   = meta.groups[assertion.toCluster]
            if (from == null || to == null) {
                AssertionResult(
                    type = "cross-cluster-delivery",
                    passed = false,
                    detail = "unknown cluster name(s): ${assertion.fromCluster} → ${assertion.toCluster}. " +
                        "Available: ${meta.groups.keys}",
                )
            } else {
                val fromUserIds = from.mapNotNull { nodeUserId[it] }.toSet()
                // 1. Find every message ID anyone in `from` ever originated. A message is
                //    "from `from`" iff its senderId belongs to a node in that cluster.
                val originated = mutableSetOf<String>()
                for (idx in from) {
                    val msgs = nodeMessages[idx] ?: continue
                    for ((id, m) in msgs) if (m.senderId in fromUserIds) originated.add(id)
                }
                // 2. Of those, how many are known to at least one node in `to`?
                val delivered = mutableSetOf<String>()
                for (idx in to) {
                    val msgs = nodeMessages[idx] ?: continue
                    for (id in originated) if (id in msgs) delivered.add(id)
                }
                val ratio = if (originated.isEmpty()) 0.0 else delivered.size.toDouble() / originated.size
                AssertionResult(
                    type = "cross-cluster-delivery",
                    passed = ratio >= assertion.minRatio,
                    detail = "from=${assertion.fromCluster}(originated=${originated.size}) " +
                        "to=${assertion.toCluster}(delivered=${delivered.size}) " +
                        "ratio=${"%.2f".format(ratio)} min=${assertion.minRatio}",
                )
            }
        }

        is Assertion.NoBridgedRerelay -> {
            // Walk every node's stored messages; any BRIDGED-trust message whose
            // recipientId doesn't match this node's own userId is a violation.
            // Bridged DMs should only ever live on the addressed recipient.
            val violations = mutableListOf<String>()
            for ((idx, msgs) in nodeMessages) {
                val myUserId = nodeUserId[idx] ?: continue
                for ((id, m) in msgs) {
                    if (m.trustLevel != com.rumor.mesh.core.model.TrustLevel.BRIDGED) continue
                    if (m.recipientId != myUserId) {
                        val originIdx = userIdToIndex[m.senderId] ?: -1
                        violations.add("node=$idx msg=$id sender=node$originIdx recipient=${m.recipientId}")
                        if (violations.size >= 20) break
                    }
                }
                if (violations.size >= 20) break
            }
            AssertionResult(
                type = "no-bridged-rerelay",
                passed = violations.isEmpty(),
                detail = if (violations.isEmpty()) "0 violations" else "${violations.size}+ violations: ${violations.joinToString("; ")}",
            )
        }

        is Assertion.DmDelivery -> {
            // Originated DMs: any DIRECT message present on its sender's own node.
            // Delivered: that same id present on the addressed recipient's node.
            val originated = mutableMapOf<String, String>()  // msgId -> recipientUserId
            for ((idx, msgs) in nodeMessages) {
                val myUserId = nodeUserId[idx] ?: continue
                for ((id, m) in msgs) {
                    if (m.type != com.rumor.mesh.core.model.MessageType.DIRECT) continue
                    if (m.senderId != myUserId) continue
                    val recipient = m.recipientId ?: continue
                    originated[id] = recipient
                }
            }
            val delivered = originated.count { (id, recipientUserId) ->
                val recipientIdx = userIdToIndex[recipientUserId] ?: return@count false
                nodeMessages[recipientIdx]?.containsKey(id) == true
            }
            val ratio = if (originated.isEmpty()) 0.0 else delivered.toDouble() / originated.size
            AssertionResult(
                type = "dm-delivery",
                passed = originated.isNotEmpty() && ratio >= assertion.minRatio,
                detail = "originated=${originated.size} delivered=$delivered ratio=${"%.2f".format(ratio)} min=${assertion.minRatio}",
            )
        }

        is Assertion.ChunkReassembly -> AssertionResult(
            type = "chunk-reassembly",
            passed = reassembledTotal >= assertion.minReassembled,
            detail = "reassembled=$reassembledTotal min=${assertion.minReassembled}",
        )
    }

    private fun WorldMetrics.toSample() = TraceSample(
        simTimeMs       = simTimeMs,
        nodeCount       = nodeCount,
        edgeCount       = edgeCount,
        totalMessages   = totalMessages,
        totalDropped    = totalDropped,
        heapUsedMb      = heapUsedMb,
        rbsrRoundsAvg   = rbsrRoundsAvgThisTick,
        rbsrRoundsMax   = rbsrRoundsMaxThisTick,
        rbsrExchanges   = rbsrExchangeCountThisTick,
    )
}

// ── Result shape ────────────────────────────────────────────────────────────

@Serializable
data class ScenarioResult(
    val name: String,
    val passed: Boolean,
    val finalMetrics: TraceSample,
    val assertions: List<AssertionResult>,
    val trace: List<TraceSample>,
    val errors: List<String>,
)

@Serializable
data class AssertionResult(
    val type: String,
    val passed: Boolean,
    val detail: String,
)

@Serializable
data class TraceSample(
    val simTimeMs: Long,
    val nodeCount: Int,
    val edgeCount: Int,
    val totalMessages: Long,
    val totalDropped: Long,
    val heapUsedMb: Long,
    /** O42/G17: avg RBSR rounds-per-exchange this tick (0.0 when bloom mode). */
    val rbsrRoundsAvg: Double = 0.0,
    /** O42/G17: worst single-edge RBSR rounds this tick. Approaching 12 = convergence at the cap. */
    val rbsrRoundsMax: Int = 0,
    /** O42/G17: how many edges ran RBSR this tick (denominator). */
    val rbsrExchanges: Int = 0,
    /**
     * Per-node breakdown at this tick. Empty for samples taken before nodes
     * exist (e.g. t=0) and for the final-metrics snapshot in the result. When
     * present, lets the bundle viewer plot per-node curves: queue-depth
     * hotspots, asymmetric broadcaster load, dup-on-ingest, bloom efficiency,
     * breadcrumb-cache growth.
     */
    val nodes: List<NodeTraceSample> = emptyList(),
)

@Serializable
data class NodeTraceSample(
    val index: Int,
    val queueDepth: Int,
    val messagesProcessed: Long,
    val dupDrops: Long,
    val bloomSkips: Long,
)
