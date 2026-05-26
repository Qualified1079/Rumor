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

    fun run(scenario: Scenario): ScenarioResult = runBlocking {
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
        val wallStartMs = System.currentTimeMillis()
        val maxWallMs = (deadlineMs / params.speedMultiplier.value).toLong() * 5L + 30_000L

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
                if (curSec != lastTracedSec) {
                    trace.add(world.metrics.value.toSample())
                    lastTracedSec = curSec
                }
            }
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

        val assertionResults = scenario.assertions.map { a ->
            evaluateAssertion(a, scenario, build.metadata, finalMetrics, nodeMessages, nodeUserId, userIdToIndex)
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
            val match = a == b && c == d
            AssertionResult(
                type = "deterministic-replay",
                passed = match,
                detail = "first=(msgs=$a, dropped=$c) replay=(msgs=$b, dropped=$d)",
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
    }

    private fun WorldMetrics.toSample() = TraceSample(
        simTimeMs       = simTimeMs,
        nodeCount       = nodeCount,
        edgeCount       = edgeCount,
        totalMessages   = totalMessages,
        totalDropped    = totalDropped,
        heapUsedMb      = heapUsedMb,
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
)
