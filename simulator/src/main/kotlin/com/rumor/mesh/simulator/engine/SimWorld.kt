package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.simulator.params.SimParamRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

private const val TAG = "SimWorld"
private const val TICK_MS = 100L   // wall-clock ms per sim tick

/**
 * Top-level simulation orchestrator. Owns all [SimNode]s and [SimTransport]
 * edges, drives the gossip tick loop, collects metrics, and exposes state for
 * the Ktor dashboard server.
 *
 * Speed control: [params.speedMultiplier] > 1 advances sim time faster than
 * wall time by reducing the inter-tick sleep. At 0.1× the tick loop sleeps
 * 10× longer, reducing CPU/memory pressure and allowing more nodes to be run.
 */
class SimWorld(val params: SimParamRegistry) {

    private val mutex  = Mutex()
    private val scope  = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickJob: Job? = null

    private val _nodes  = MutableStateFlow<List<SimNode>>(emptyList())
    val nodes: StateFlow<List<SimNode>> = _nodes.asStateFlow()

    private val _edges  = MutableStateFlow<List<SimTransport>>(emptyList())
    val edges: StateFlow<List<SimTransport>> = _edges.asStateFlow()

    private val _metrics = MutableStateFlow(WorldMetrics())
    val metrics: StateFlow<WorldMetrics> = _metrics.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _simTimeMs = MutableStateFlow(0L)
    val simTimeMs: StateFlow<Long> = _simTimeMs.asStateFlow()

    // heap pressure monitor
    private val runtime = Runtime.getRuntime()

    /**
     * Optional pre-built topology. When set, [rebuild] uses it verbatim and
     * ignores the random-graph params. Scenarios set this before [start].
     * Clearing it (setting to null) returns to the random-graph behaviour.
     */
    @Volatile var customTopology: ((rng: Random) -> Pair<List<SimNode>, List<SimTransport>>)? = null

    /** Indices of nodes that have been killed by a scenario event. They generate no traffic. */
    private val killedNodes = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /**
     * Per-tick trace recorder for interactive dashboard runs (separate from
     * the headless ScenarioRunner's trace). Captured at most once per sim
     * second; latest 600 samples kept (10 minutes of sim-time at 1 sample/s,
     * regardless of speedMult). The dashboard's "Download run history" hits
     * a /api/trace endpoint to bundle these into a zip with the same shape
     * scenarios produce — so the same downstream analysis tools work for both.
     */
    private val traceRing = java.util.concurrent.ConcurrentLinkedDeque<com.rumor.mesh.simulator.scenario.TraceSample>()
    private val maxTraceSamples = 600
    private var lastTracedSec: Long = -1

    fun snapshotTrace(): List<com.rumor.mesh.simulator.scenario.TraceSample> = traceRing.toList()
    fun clearTrace() { traceRing.clear(); lastTracedSec = -1 }

    private fun recordTraceIfDue() {
        val curSec = _simTimeMs.value / 1000L
        if (curSec == lastTracedSec) return
        lastTracedSec = curSec
        val m = _metrics.value
        val perNode = _nodes.value.map { n ->
            com.rumor.mesh.simulator.scenario.NodeTraceSample(
                index = n.index,
                queueDepth = n.schedulerQueueDepth,
                messagesProcessed = n.messagesProcessed.value,
                dupDrops = n.dupDrops.value,
                bloomSkips = n.bloomSkips.value,
            )
        }
        val sample = com.rumor.mesh.simulator.scenario.TraceSample(
            simTimeMs = m.simTimeMs,
            nodeCount = m.nodeCount,
            edgeCount = m.edgeCount,
            totalMessages = m.totalMessages,
            totalDropped = m.totalDropped,
            heapUsedMb = m.heapUsedMb,
            nodes = perNode,
        )
        traceRing.addLast(sample)
        while (traceRing.size > maxTraceSamples) traceRing.pollFirst()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    suspend fun start() = mutex.withLock {
        if (_running.value) return
        rebuild()
        _running.value = true
        tickJob = scope.launch { tickLoop() }
        RumorLog.i(TAG, "Sim started: ${_nodes.value.size} nodes, ${_edges.value.size} edges")
    }

    suspend fun stop() = mutex.withLock {
        _running.value = false
        tickJob?.cancelAndJoin()
        tickJob = null
        RumorLog.i(TAG, "Sim stopped")
    }

    suspend fun reset() {
        stop()
        _simTimeMs.value = 0
        _metrics.value = WorldMetrics()
        clearTrace()
        start()
    }

    /** Step exactly one tick regardless of speed. */
    suspend fun step() {
        if (!_running.value) tick()
    }

    // ── Topology ──────────────────────────────────────────────────────────────

    private fun rebuild() {
        val rng = Random(params.seed.value)
        killedNodes.clear()

        // Scenario-injected topology wins; otherwise build a random k-regular graph.
        val ct = customTopology
        if (ct != null) {
            val (newNodes, newEdges) = ct(rng)
            _nodes.value = newNodes
            _edges.value = newEdges
            _edgeActivity.clear()
            return
        }

        val count = params.nodeCount.value
        val useBreadcrumbs = params.useBreadcrumbs.value == 1
        val useRbsr = params.useRbsr.value == 1
        val nodeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val newNodes = (0 until count).map { SimNode(it, nodeScope, useBreadcrumbs = useBreadcrumbs) }
        _nodes.value = newNodes

        val k = params.connectionsPerNode.value
        val edgeSet = mutableSetOf<String>()
        val newEdges = mutableListOf<SimTransport>()
        for (node in newNodes) {
            val candidates = newNodes.filter { it.index != node.index && SimTransport.edgeKey(node.index, it.index) !in edgeSet }
            val pick = candidates.shuffled(rng).take(k)
            for (peer in pick) {
                val edge = SimTransport(node, peer, buildConditioner(rng), useRbsr = useRbsr)
                newEdges.add(edge)
                edgeSet.add(edge.edgeKey)
            }
        }
        _edges.value = newEdges
        _edgeActivity.clear()
    }

    // ── Scenario-control surface ──────────────────────────────────────────────

    fun killNode(index: Int) { killedNodes.add(index) }
    fun isKilled(index: Int): Boolean = index in killedNodes

    /** Set partitioned=[on] on every edge whose tag set contains [tag]. */
    fun setPartitionByTag(tag: String, on: Boolean): Int {
        val matching = _edges.value.filter { tag in it.tags }
        matching.forEach { it.conditioner.partitioned = on }
        return matching.size
    }

    /**
     * Originate a synthetic DM from [src] to a random other alive node, using the
     * real [com.rumor.mesh.core.protocol.GossipEngine.composeDirect] code path
     * (X25519 ECDH + AES-GCM). Recipient pubkey is looked up from the target
     * node's identity — in real Rumor this comes from ContactRepository after
     * a HELLO exchange; the sim has the luxury of direct access.
     */
    private fun emitDm(src: SimNode, aliveNodes: List<SimNode>, rng: Random) {
        val candidates = aliveNodes.filter { it.index != src.index }
        if (candidates.isEmpty()) return
        val recipient = candidates[rng.nextInt(candidates.size)]
        val recipientIdentity = recipient.identityProvider.identity.value ?: return
        src.gossipEngine.composeDirect(
            recipientId         = recipient.userId,
            recipientPublicKey  = recipientIdentity.publicKeyBytes,
            text                = "sim-dm-${rng.nextInt(100_000)}",
        ) ?: return
        src.recordProcessed()
    }

    /**
     * Originate a chunked transfer from [src] (broadcast — no recipient). Payload
     * is sized to force >1 chunk through [com.rumor.mesh.core.transfer.Chunker]
     * and reassembled on every receiver via their [SimNode.transferAssembler].
     */
    private suspend fun emitLarge(src: SimNode, profile: TrafficProfile, rng: Random) {
        // 1.5–2× the default 60 KB chunk size so each transfer is 2–3 chunks.
        val sizeBytes = com.rumor.mesh.core.transfer.DEFAULT_CHUNK_SIZE * (3 + rng.nextInt(2)) / 2
        val data = ByteArray(sizeBytes) { (rng.nextInt(256)).toByte() }
        // Called inline (not scope.launch) so chunks land in the scheduler before
        // flushSchedulerToRepo() runs later in the same tick.
        src.transferSender.sendFile(
            recipientId = null,
            contentType = com.rumor.mesh.core.model.ContentType.FILE,
            data        = data,
            mimeType    = "application/octet-stream",
            title       = "sim-transfer",
        )
        src.recordProcessed()
    }

    /** Per-edge conditioner: ±25% jitter around the global mean so edges aren't all identical. */
    private fun buildConditioner(rng: Random) = NetworkConditioner().also {
        fun wobble(mean: Double) = mean * (0.75 + rng.nextDouble() * 0.5)
        it.latencyMs            = wobble(params.linkLatencyMs.value.toDouble()).toLong().coerceAtLeast(1L)
        it.jitterMs             = params.linkJitterMs.value
        it.lossRate             = wobble(params.lossRate.value).coerceIn(0.0, 1.0)
        it.bandwidthBytesPerSec = wobble(params.bandwidthKbps.value * 1024.0).toLong().coerceAtLeast(1024L)
    }

    /** Edge index → sim time of last non-empty exchange. Used by the dashboard for flash hints. */
    private val _edgeActivity = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ── Tick loop ─────────────────────────────────────────────────────────────

    private suspend fun tickLoop() {
        while (_running.value) {
            checkHeapPressure()
            tick()
            val sleepMs = (TICK_MS / params.speedMultiplier.value).toLong().coerceAtLeast(1L)
            delay(sleepMs)
        }
    }

    private suspend fun tick() {
        val tickDurationMs = (TICK_MS * params.speedMultiplier.value).toLong()
        _simTimeMs.value += tickDurationMs

        val nodes = _nodes.value
        val edges = _edges.value
        val rng   = Random(_simTimeMs.value xor params.seed.value)

        // 1. Generate traffic from each node (skip killed ones — scenario events).
        val simSecondsPerTick = tickDurationMs / 1000.0
        val aliveNodes = nodes.filter { it.index !in killedNodes }
        val dmFraction = params.dmFraction.value
        val largeFraction = params.largeMessageFraction.value
        // O58 efficiency-testing knobs: first N nodes act as "broadcasters"
        // (organizers, news-spreaders) at a multiplier of the baseline rate.
        // Models the realistic skewed-load case where a small fraction of
        // nodes originates most traffic. broadcasterCount=0 keeps symmetry.
        val broadcasterCount = params.broadcasterCount.value
        val broadcasterMult = params.broadcasterMultiplier.value
        val baseRate = params.msgPerSecondPerNode.value
        for (node in aliveNodes) {
            val rate = if (broadcasterCount > 0 && node.index < broadcasterCount) {
                baseRate * broadcasterMult
            } else {
                baseRate
            }
            val profile = TrafficProfile(
                msgPerSecond         = rate,
                minPayloadBytes      = params.minPayloadBytes.value,
                maxPayloadBytes      = params.maxPayloadBytes.value,
                hopsToLive           = params.hopsToLive.value,
                dmFraction           = dmFraction,
                largeMessageFraction = largeFraction,
                burstProbability     = params.burstProbability.value,
                burstMultiplier      = params.burstMultiplier.value,
            )
            val identity = node.identityProvider.identity.value ?: continue
            val gen = MessageGenerator(identity, profile)
            val base = gen.messagesThisTick(rng, simSecondsPerTick)
            val burstMult = if (rng.nextDouble() < profile.burstProbability) profile.burstMultiplier else 1
            for (i in 0 until base * burstMult) {
                when {
                    dmFraction > 0 && rng.nextDouble() < dmFraction -> emitDm(node, aliveNodes, rng)
                    largeFraction > 0 && rng.nextDouble() < largeFraction -> emitLarge(node, profile, rng)
                    else -> {
                        val msg = gen.generate(rng)
                        node.gossipEngine.injectFromPlugin(msg, "sim-generator")
                        node.recordProcessed()
                    }
                }
            }
        }

        // 1b. Flush scheduler (DMs, chunks) into messageRepo so exchange can find them.
        // composeDirect and TransferSender.sendFile enqueue into the scheduler; the sim
        // reads messageRepo for exchange offers (avoids destructive-take for broadcasts).
        for (node in aliveNodes) node.flushSchedulerToRepo()

        // 2. Run gossip exchanges on each edge (skip if either endpoint is killed).
        var totalMsgs = 0L
        var totalDropped = 0L
        for (edge in edges) {
            if (edge.nodeA.index in killedNodes || edge.nodeB.index in killedNodes) continue
            // Probabilistic partition.
            if (rng.nextDouble() < params.partitionProbability.value) {
                edge.conditioner.partitioned = true
                scope.launch {
                    delay(params.partitionDurationSec.value * 1000L)
                    edge.conditioner.partitioned = false
                }
            }
            val m = edge.exchange(rng)
            totalMsgs    += m.totalMessages
            totalDropped += m.dropped
            if (m.hasTraffic) _edgeActivity[edge.edgeKey] = _simTimeMs.value
        }

        // 3. Update metrics.
        val prev = _metrics.value
        _metrics.value = WorldMetrics(
            nodeCount         = nodes.size,
            edgeCount         = edges.size,
            totalMsgsThisTick = totalMsgs,
            totalMessages     = prev.totalMessages + totalMsgs,
            totalDropped      = prev.totalDropped + totalDropped,
            simTimeMs         = _simTimeMs.value,
            heapUsedMb        = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576,
            heapMaxMb         = runtime.maxMemory() / 1_048_576,
        )
        recordTraceIfDue()
    }

    // ── Memory guard ─────────────────────────────────────────────────────────

    private fun checkHeapPressure() {
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max  = runtime.maxMemory()
        if (used.toDouble() / max > 0.85) {
            _running.value = false
            RumorLog.w(TAG, "Heap at ${used * 100 / max}% — sim paused. Reduce node count or slow speed.")
        }
    }

    // ── Dashboard helpers ─────────────────────────────────────────────────────

    fun edgeSnapshots(): List<EdgeSnapshot> = _edges.value.map { e ->
        EdgeSnapshot(
            from           = e.nodeA.index,
            to             = e.nodeB.index,
            latencyMs      = e.conditioner.latencyMs,
            lossRate       = e.conditioner.lossRate,
            partitioned    = e.conditioner.partitioned,
            lastActiveAtMs = _edgeActivity[e.edgeKey] ?: -1L,
        )
    }

    fun nodeSnapshots(): List<NodeSnapshot> = _nodes.value.map { n ->
        NodeSnapshot(
            index           = n.index,
            userId          = n.userId.take(12),
            queueDepth      = n.schedulerQueueDepth,
            messagesProcessed = n.messagesProcessed.value,
            dupDrops        = n.dupDrops.value,
        )
    }
}

data class WorldMetrics(
    val nodeCount: Int          = 0,
    val edgeCount: Int          = 0,
    val totalMsgsThisTick: Long = 0,
    val totalMessages: Long     = 0,
    val totalDropped: Long      = 0,
    val simTimeMs: Long         = 0,
    val heapUsedMb: Long        = 0,
    val heapMaxMb: Long         = 0,
)

data class EdgeSnapshot(
    val from: Int,
    val to: Int,
    val latencyMs: Long,
    val lossRate: Double,
    val partitioned: Boolean,
    val lastActiveAtMs: Long = -1L,
)

data class NodeSnapshot(
    val index: Int,
    val userId: String,
    val queueDepth: Int,
    val messagesProcessed: Long,
    val dupDrops: Long,
)
