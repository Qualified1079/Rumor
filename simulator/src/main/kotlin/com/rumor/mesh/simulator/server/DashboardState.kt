package com.rumor.mesh.simulator.server

import com.rumor.mesh.simulator.engine.SimWorld
import com.rumor.mesh.simulator.params.ParamDescriptor
import kotlinx.serialization.Serializable

/**
 * Serializable snapshot of the whole simulation, pushed to the browser over
 * the WebSocket on every tick. Kept separate from the engine's own data
 * classes so the engine never has to depend on the serialization layer.
 */
@Serializable
data class DashboardState(
    val running: Boolean,
    val simTimeMs: Long,
    val metrics: MetricsDto,
    val nodes: List<NodeDto>,
    val edges: List<EdgeDto>,
)

@Serializable
data class MetricsDto(
    val nodeCount: Int,
    val edgeCount: Int,
    val totalMsgsThisTick: Long,
    val totalMessages: Long,
    val totalDropped: Long,
    val simTimeMs: Long,
    val heapUsedMb: Long,
    val heapMaxMb: Long,
)

@Serializable
data class NodeDto(
    val index: Int,
    val userId: String,
    val queueDepth: Int,
    val messagesProcessed: Long,
    val dupDrops: Long,
)

@Serializable
data class EdgeDto(
    val from: Int,
    val to: Int,
    val latencyMs: Long,
    val lossRate: Double,
    val partitioned: Boolean,
    /** True when this edge is carrying heavy traffic that isn't draining — drawn red. */
    val congested: Boolean,
    /** Sim-time ms when this edge last carried any message. -1 if never. Used for flash hints. */
    val lastActiveAtMs: Long,
)

/** Initial payload sent once on connect: the param descriptors for slider generation. */
@Serializable
data class DashboardInit(
    val params: List<ParamDescriptor>,
)

/** Build a [DashboardState] from the live [world]. */
fun SimWorld.dashboardState(): DashboardState {
    val nodes = nodeSnapshots()
    val edges = edgeSnapshots()
    // An edge is "congested" if either endpoint has a deep scheduler queue.
    val deepQueue = nodes.associate { it.index to (it.queueDepth > 50) }
    return DashboardState(
        running   = running.value,
        simTimeMs = simTimeMs.value,
        metrics   = metrics.value.let {
            MetricsDto(
                it.nodeCount, it.edgeCount, it.totalMsgsThisTick, it.totalMessages,
                it.totalDropped, it.simTimeMs, it.heapUsedMb, it.heapMaxMb,
            )
        },
        nodes = nodes.map { NodeDto(it.index, it.userId, it.queueDepth, it.messagesProcessed, it.dupDrops) },
        edges = edges.map {
            EdgeDto(
                from = it.from, to = it.to,
                latencyMs = it.latencyMs, lossRate = it.lossRate, partitioned = it.partitioned,
                congested = (deepQueue[it.from] == true || deepQueue[it.to] == true),
                lastActiveAtMs = it.lastActiveAtMs,
            )
        },
    )
}
