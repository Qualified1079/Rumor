package com.rumor.mesh.simulator.params

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlin.random.Random

enum class ParamCategory { NETWORK, TOPOLOGY, TRAFFIC, PROTOCOL, SIM_CONTROL }

/**
 * A single tunable simulation parameter with bounds, a current value, and
 * a randomization function. The dashboard generates a slider for each one
 * automatically via the registry — no per-param UI code needed.
 */
class SimParam<T : Comparable<T>>(
    val id: String,
    val label: String,
    val category: ParamCategory,
    val min: T,
    val max: T,
    default: T,
    val step: Double = 1.0,
    private val randomFn: (Random) -> T,
) {
    private val _value = MutableStateFlow(default)
    val valueFlow: StateFlow<T> = _value.asStateFlow()
    var value: T
        get() = _value.value
        set(v) { _value.value = v.coerceIn(min, max) }

    fun randomize(rng: Random = Random.Default) { value = randomFn(rng) }

    /** Parse [raw] into this param's type and assign it. Used by the dashboard. */
    @Suppress("UNCHECKED_CAST")
    fun setFromString(raw: String) {
        value = when (min) {
            is Long   -> raw.trim().toLong()   as T
            is Int    -> raw.trim().toInt()    as T
            is Double -> raw.trim().toDouble() as T
            else      -> throw IllegalStateException("Unsupported param type for $id")
        }
    }

    fun toDescriptor() = ParamDescriptor(id, label, category.name, min.toString(), max.toString(), value.toString(), step)
}

@Serializable
data class ParamDescriptor(
    val id: String,
    val label: String,
    val category: String,
    val min: String,
    val max: String,
    val current: String,
    val step: Double,
)

/**
 * All simulation parameters in one place. Mutable live — changes take effect
 * on the next tick.
 */
class SimParamRegistry {

    // ── Network ──────────────────────────────────────────────────────────────
    // Randomization ranges are deliberately narrower than slider bounds: the
    // bounds let you push to extremes manually, but Randomize-all picks values
    // that produce a watchable mesh (some traffic, some loss, mostly connected).
    val linkLatencyMs = SimParam("link_latency_ms", "Link latency (ms)",
        ParamCategory.NETWORK, 1L, 500L, 50L,
        randomFn = { rng -> rng.nextLong(20, 151) })

    val linkJitterMs = SimParam("link_jitter_ms", "Latency jitter (ms)",
        ParamCategory.NETWORK, 0L, 200L, 20L,
        randomFn = { rng -> rng.nextLong(0, 51) })

    val lossRate = SimParam("loss_rate", "Packet loss rate",
        ParamCategory.NETWORK, 0.0, 0.5, 0.0, step = 0.01,
        randomFn = { rng -> rng.nextDouble(0.0, 0.08) })

    val bandwidthKbps = SimParam("bandwidth_kbps", "Link bandwidth (KB/s)",
        ParamCategory.NETWORK, 1L, 10_000L, 1_000L,
        randomFn = { rng -> rng.nextLong(256, 3001) })

    val partitionProbability = SimParam("partition_prob", "Partition probability/tick",
        ParamCategory.NETWORK, 0.0, 0.1, 0.0, step = 0.001,
        randomFn = { rng -> rng.nextDouble(0.0, 0.01) })

    val partitionDurationSec = SimParam("partition_duration_sec", "Partition duration (s)",
        ParamCategory.NETWORK, 1L, 300L, 30L,
        randomFn = { rng -> rng.nextLong(5, 61) })

    // ── Topology ─────────────────────────────────────────────────────────────
    val nodeCount = SimParam("node_count", "Node count",
        ParamCategory.TOPOLOGY, 5, 500, 30,
        randomFn = { rng -> rng.nextInt(15, 61) })

    val connectionsPerNode = SimParam("connections_per_node", "Avg connections/node",
        ParamCategory.TOPOLOGY, 1, 20, 4,
        randomFn = { rng -> rng.nextInt(3, 7) })

    val churnRatePerMinute = SimParam("churn_rate", "Churn rate (%/min)",
        ParamCategory.TOPOLOGY, 0.0, 0.3, 0.0, step = 0.01,
        randomFn = { rng -> rng.nextDouble(0.0, 0.05) })

    // ── Traffic ──────────────────────────────────────────────────────────────
    val msgPerSecondPerNode = SimParam("msg_per_sec", "Msg/sec per node",
        ParamCategory.TRAFFIC, 0.0, 10.0, 0.5, step = 0.1,
        randomFn = { rng -> rng.nextDouble(0.5, 2.5) })

    val minPayloadBytes = SimParam("min_payload", "Min payload (bytes)",
        ParamCategory.TRAFFIC, 10, 50_000, 50,
        randomFn = { rng -> rng.nextInt(20, 201) })

    val maxPayloadBytes = SimParam("max_payload", "Max payload (bytes)",
        ParamCategory.TRAFFIC, 10, 5_000_000, 500,
        randomFn = { rng -> rng.nextInt(400, 2001) })

    val burstProbability = SimParam("burst_prob", "Burst probability",
        ParamCategory.TRAFFIC, 0.0, 0.2, 0.01, step = 0.005,
        randomFn = { rng -> rng.nextDouble(0.0, 0.03) })

    val burstMultiplier = SimParam("burst_mult", "Burst multiplier",
        ParamCategory.TRAFFIC, 1, 100, 5,
        randomFn = { rng -> rng.nextInt(2, 7) })

    val dmFraction = SimParam("dm_fraction", "DM fraction of traffic",
        ParamCategory.TRAFFIC, 0.0, 1.0, 0.0, step = 0.05,
        randomFn = { rng -> rng.nextDouble(0.0, 0.4) })

    val largeMessageFraction = SimParam("large_msg_fraction", "Large-payload fraction",
        ParamCategory.TRAFFIC, 0.0, 1.0, 0.0, step = 0.05,
        randomFn = { rng -> rng.nextDouble(0.0, 0.1) })

    /**
     * Number of "broadcaster" nodes (the first N by index) that send traffic
     * at [broadcasterMultiplier] times the baseline rate. Models the realistic
     * disaster-comms pattern where a small fraction of nodes (organizers,
     * news-spreaders) originates most of the traffic. Set to 0 to keep traffic
     * symmetric. Default 0 — preserves prior scenario behaviour.
     */
    val broadcasterCount = SimParam("broadcaster_count", "Broadcaster nodes",
        ParamCategory.TRAFFIC, 0, 100, 0,
        randomFn = { rng -> rng.nextInt(0, 6) })

    val broadcasterMultiplier = SimParam("broadcaster_mult", "Broadcaster rate ×",
        ParamCategory.TRAFFIC, 1.0, 20.0, 5.0, step = 0.5,
        randomFn = { rng -> rng.nextDouble(2.0, 8.0) })

    // ── Protocol ─────────────────────────────────────────────────────────────
    val hopsToLive = SimParam("hops_to_live", "Hops to live",
        ParamCategory.PROTOCOL, 1, 15, 7,
        randomFn = { rng -> rng.nextInt(6, 11) })

    val schedulerQuantumKb = SimParam("scheduler_quantum_kb", "Scheduler quantum (KB)",
        ParamCategory.PROTOCOL, 10, 500, 60,
        randomFn = { rng -> rng.nextInt(10, 181) })

    val gossipIntervalMs = SimParam("gossip_interval_ms", "Gossip interval (ms)",
        ParamCategory.PROTOCOL, 500L, 60_000L, 5_000L,
        randomFn = { rng -> rng.nextLong(1_000, 15_001) })

    // ── Sim control ──────────────────────────────────────────────────────────
    val speedMultiplier = SimParam("speed_mult", "Sim speed multiplier",
        ParamCategory.SIM_CONTROL, 0.01, 100.0, 1.0, step = 0.01,
        randomFn = { rng -> rng.nextDouble(0.1, 10.0) })

    val seed = SimParam("seed", "Random seed",
        ParamCategory.SIM_CONTROL, 0L, Long.MAX_VALUE, 42L,
        randomFn = { rng -> rng.nextLong(0, Long.MAX_VALUE) })

    /** All params in order for serialization and dashboard rendering. */
    val all: List<SimParam<*>> = listOf(
        linkLatencyMs, linkJitterMs, lossRate, bandwidthKbps, partitionProbability, partitionDurationSec,
        nodeCount, connectionsPerNode, churnRatePerMinute,
        msgPerSecondPerNode, minPayloadBytes, maxPayloadBytes, burstProbability, burstMultiplier,
        dmFraction, largeMessageFraction,
        hopsToLive, schedulerQuantumKb, gossipIntervalMs,
        speedMultiplier, seed,
    )

    /** Randomize every param at once from a shared RNG. */
    fun randomizeAll(rng: Random = Random.Default) {
        all.forEach { it.randomize(rng) }
        enforceInvariants()
    }

    fun byId(id: String): SimParam<*>? = all.firstOrNull { it.id == id }

    /** Randomize a single param by id. Returns false if no such param. */
    fun randomizeOne(id: String, rng: Random = Random.Default): Boolean {
        val p = byId(id) ?: return false
        p.randomize(rng)
        enforceInvariants()
        return true
    }

    /**
     * Cross-parameter coherence. Run after any change that could violate a
     * relationship (e.g. min_payload > max_payload would crash MessageGenerator).
     * Public so KtorServer can call it after a slider edit too.
     */
    fun enforceInvariants() {
        if (minPayloadBytes.value > maxPayloadBytes.value) {
            // Swap rather than clamp — clamping silently throws away whichever
            // value the user just typed, swapping preserves both endpoints.
            val (lo, hi) = minPayloadBytes.value to maxPayloadBytes.value
            minPayloadBytes.value = hi
            maxPayloadBytes.value = lo
        }
    }

    fun descriptors(): List<ParamDescriptor> = all.map { it.toDescriptor() }
}
