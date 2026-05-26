package com.rumor.mesh.simulator.scenario

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level scenario shape. Each JSON file in the scenarios directory
 * deserializes to one of these. The scenario runner then builds the
 * topology, applies params, fires events on the sim clock, and finally
 * evaluates assertions.
 *
 * Durations are in sim seconds (virtual time). Wall-clock cost depends on
 * the `speedMult` param; default 10× makes the bundled set finish in
 * about a coffee break.
 */
@Serializable
data class Scenario(
    val name: String,
    val durationSec: Int,
    val seed: Long = 42L,
    val speedMult: Double = 10.0,
    val topology: Topology,
    val params: Map<String, String> = emptyMap(),
    val events: List<ScenarioEvent> = emptyList(),
    val assertions: List<Assertion> = emptyList(),
    /** When true, ScenarioRunner records a per-tick metrics trace even on success. */
    val trace: Boolean = false,
)

@Serializable
sealed class Topology {
    @Serializable @SerialName("line")
    data class Line(val length: Int) : Topology()

    @Serializable @SerialName("mesh")
    data class Mesh(val nodes: Int, val connectionsPerNode: Int) : Topology()

    @Serializable @SerialName("two-cluster")
    data class TwoCluster(val left: Int, val right: Int, val bridges: Int = 1) : Topology()

    @Serializable @SerialName("hub-spoke")
    data class HubSpoke(val spokes: Int) : Topology()

    @Serializable @SerialName("custom")
    data class Custom(val nodeCount: Int, val edges: List<EdgeSpec>) : Topology()
}

@Serializable
data class EdgeSpec(val from: Int, val to: Int, val tags: List<String> = emptyList())

@Serializable
sealed class ScenarioEvent {
    abstract val atSec: Int

    @Serializable @SerialName("partition")
    data class Partition(override val atSec: Int, val target: String) : ScenarioEvent()

    @Serializable @SerialName("heal")
    data class Heal(override val atSec: Int, val target: String) : ScenarioEvent()

    @Serializable @SerialName("kill-node")
    data class KillNode(override val atSec: Int, val nodeIndex: Int) : ScenarioEvent()

    @Serializable @SerialName("set-param")
    data class SetParam(override val atSec: Int, val paramId: String, val value: String) : ScenarioEvent()
}

@Serializable
sealed class Assertion {
    @Serializable @SerialName("deterministic-replay")
    object DeterministicReplay : Assertion()

    @Serializable @SerialName("min-total-messages")
    data class MinTotalMessages(val min: Long) : Assertion()

    @Serializable @SerialName("cross-cluster-delivery")
    data class CrossClusterDelivery(
        val fromCluster: String,
        val toCluster: String,
        val minRatio: Double,
    ) : Assertion()

    @Serializable @SerialName("heap-bounded")
    data class HeapBounded(val maxMb: Long) : Assertion()

    /**
     * Invariant: no BRIDGED-trust message should ever appear on a node that
     * isn't its addressed recipient. Implemented as a post-hoc snapshot scan
     * (walk every node's stored messages, flag any BRIDGED whose recipientId
     * doesn't match this node's userId).
     *
     * Limitation: only catches violations whose evidence still exists in
     * the stored set at the end of the scenario. A message that was relayed
     * and then evicted would slip through. Stored messages are not evicted
     * during the in-memory sim's lifetime so this is reliable for now, but
     * may need to escalate to a live relay observer (added to GossipEngine)
     * if message stores ever start trimming during a run. See CLAUDE.md.
     */
    @Serializable @SerialName("no-bridged-rerelay")
    object NoBridgedRerelay : Assertion()
}
