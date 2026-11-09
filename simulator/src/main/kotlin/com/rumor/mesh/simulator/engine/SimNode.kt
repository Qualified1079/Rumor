package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.policy.PermissiveInboxFilter
import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.transfer.AssembledTransfer
import com.rumor.mesh.core.data.memory.InMemoryBlockEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryBlocklistEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryBreadcrumbRepository
import com.rumor.mesh.core.data.memory.InMemoryChunkRepository
import com.rumor.mesh.core.data.memory.InMemoryContactRepository
import com.rumor.mesh.core.data.memory.InMemoryMessageRepository
import com.rumor.mesh.core.data.memory.InMemoryRouteRepository
import com.rumor.mesh.core.data.memory.InMemorySubscribedBlocklistRepository
import com.rumor.mesh.core.data.memory.InMemoryTransferRepository
import com.rumor.mesh.core.data.memory.InMemoryRoomRepository
import com.rumor.mesh.core.data.memory.InMemoryRoomSubscriptionRepository
import com.rumor.mesh.core.runtime.MeshGraph
import com.rumor.mesh.core.runtime.MeshRepositories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One simulated Rumor node. Wires together the real protocol stack with
 * in-memory repositories and a generated identity. No Android dependencies.
 */
class SimNode(
    val index: Int,
    parentScope: CoroutineScope,
    /**
     * O29 A/B toggle. When false, GossipEngine is constructed with
     * breadcrumbs=null and the routing-bias + per-peer filter become no-ops —
     * baseline pure-flood behaviour for comparison runs.
     */
    private val useBreadcrumbs: Boolean = true,
    /**
     * O12: shared sim-time clock. All SimNodes built by the same scenario
     * share one clock so the entire mesh sees the same `now()` value — what
     * a real-world wall-clock would offer if it were perfectly synced. Sim
     * advances this from SimWorld.tick(). Default to wall-clock for ad-hoc
     * one-off tests.
     */
    val clock: com.rumor.mesh.core.Clock = com.rumor.mesh.core.SystemClock,
    /**
     * O135(1): the inbox filter. Defaults to permissive; scenarios testing the
     * "known peers only" defense pass a [com.rumor.mesh.core.policy.KnownSendersInboxFilter].
     */
    private val inboxFilterOverride: com.rumor.mesh.core.policy.InboxFilter? = null,
) {
    /**
     * O12/O114 escalation — per-node serialized execution. GossipEngine's
     * internal handlers race under a parallel dispatcher, making handler order
     * (and thus totalMessages / drop counts) vary run-to-run; that variance
     * caused the first real CI failure (PerPeerRoutingTest). A parallelism-1
     * view of the parent dispatcher serializes each node's handlers without
     * spawning a thread per node (500-node sims stay cheap). Nodes still run
     * concurrently with each other — only intra-node order is pinned.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(
        parentScope.coroutineContext + kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)
    )

    /**
     * O98 — this node's declared [UserMode]. Scenarios set it before composing
     * self-presence beacons (`composeSelfPresence(mode, …)`); it feeds the
     * planner's degree budget via the MeshView the beacons assemble into.
     */
    var mode: com.rumor.mesh.core.model.UserMode
        get() = modeFlow.value
        set(value) { modeFlow.value = value }

    private val modeFlow = MutableStateFlow(com.rumor.mesh.core.model.UserMode.MOBILE)

    /**
     * O205 — the engine reads the live mode for its cadence-derived liveness window,
     * so [mode] is backed by a flow rather than a plain field. Scenarios still just
     * assign `node.mode = FREE`.
     */
    private val modeState = object : com.rumor.mesh.core.mode.ModeState {
        override val mode: StateFlow<com.rumor.mesh.core.model.UserMode> = modeFlow.asStateFlow()
    }

    val identityProvider = SimIdentityProvider(index)
    val userId: String get() = identityProvider.identity.value!!.userId

    val contactRepo             = InMemoryContactRepository()
    internal val messageRepo    = InMemoryMessageRepository()
    /** Internal (not private) so transfer-layer scenarios can assert on receiver state. */
    internal val transferRepo   = InMemoryTransferRepository()
    internal val chunkRepo      = InMemoryChunkRepository()
    /** O79 — per-node room-subscription store, in-memory. Exposed so scenarios can add subscriptions. */
    val roomSubscriptionRepo    = InMemoryRoomSubscriptionRepository()
    /** O187 — room metadata + roster, as on the phone (O252: same graph everywhere). */
    val roomRepo                = InMemoryRoomRepository()

    // O252: the graph is built by :core's MeshGraph, exactly as on the phone and :node.
    private val graph = MeshGraph(
        repos = MeshRepositories(
            messages = messageRepo,
            contacts = contactRepo,
            routes = InMemoryRouteRepository(),
            breadcrumbs = InMemoryBreadcrumbRepository(),
            transfers = transferRepo,
            chunks = chunkRepo,
            blockEntries = InMemoryBlockEntryRepository(),
            subscribedBlocklists = InMemorySubscribedBlocklistRepository(),
            blocklistEntries = InMemoryBlocklistEntryRepository(),
            roomSubscriptions = roomSubscriptionRepo,
            rooms = roomRepo,
        ),
        identityProvider = identityProvider,
        modeState = modeState,
        inboxFilter = inboxFilterOverride ?: PermissiveInboxFilter(),
        scope = scope,
        clock = clock,
        breadcrumbRouting = useBreadcrumbs,
        // Flush relayed messages immediately — real wall-clock delay (100–500 ms)
        // would equal 1–5 sim-seconds at speedMult=10, breaking multi-hop propagation.
        relayBatchMinWindowMs = 1L,
        relayBatchSpreadMs = 1L,
    )

    private val duplicateFilter = graph.duplicateFilter
    internal val onlineTracker  = graph.onlineStatusTracker
    internal val breadcrumbs    = graph.breadcrumbs
    /** O98 MeshView substrate — populated from inbound SELF_PRESENCE beacons. */
    val meshView                = graph.meshView
    private val scheduler       = graph.scheduler

    val gossipEngine = graph.gossipEngine
    val transferSender = graph.transferSender
    val transferAssembler = graph.transferAssembler

    /** Count of fully-reassembled inbound transfers, for chunked-delivery assertions. */
    private val _reassembledTransfers = MutableStateFlow(0L)
    val reassembledTransfers: StateFlow<Long> = _reassembledTransfers.asStateFlow()

    /**
     * O135(1) test hook: sender ids of every message that passed the inbox
     * filter (i.e. reached [GossipEngine.incomingMessages] — what the UI sees).
     * Distinct from [messageRepo] (the store, which holds everything relayed
     * regardless of filter). A sybil-flood scenario compares the two:
     * store = all (relay untouched), inbox = only friended senders.
     */
    val inboxSenderIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        // Track successful reassembly events for the chunked-delivery assertion.
        scope.launch {
            transferAssembler.assembledTransfers.collect { _: AssembledTransfer ->
                _reassembledTransfers.value++
            }
        }
        scope.launch {
            gossipEngine.incomingMessages.collect { inboxSenderIds.add(it.senderId) }
        }
        // Wire dup-on-ingest from CanaryMetrics into the per-node counter.
        // Without this the per-node `dupDrops` field stays 0 even when the
        // engine is rejecting duplicates — exactly the "dupDrops always 0"
        // pattern that showed up in every prior report. Done via flow
        // diff because canaryMetrics.recordIncoming publishes a snapshot,
        // not an event stream.
        scope.launch {
            var lastDedupHits = 0L
            gossipEngine.canaryMetrics.flow.collect { snap ->
                val delta = snap.dedupHits - lastDedupHits
                if (delta > 0) repeat(delta.toInt().coerceAtLeast(0)) { _dupDrops.value++ }
                lastDedupHits = snap.dedupHits
            }
        }
    }

    // ── Metrics state exposed to SimWorld / dashboard ─────────────────────────

    /** Number of messages this node has originated and received. */
    private val _messagesProcessed = MutableStateFlow(0L)
    val messagesProcessed: StateFlow<Long> = _messagesProcessed.asStateFlow()

    /** Number of messages dropped by dedup (already seen) at receiver-side ingest. */
    private val _dupDrops = MutableStateFlow(0L)
    val dupDrops: StateFlow<Long> = _dupDrops.asStateFlow()

    /**
     * Cumulative messages NOT offered across this node's outbound edges because
     * the peer's bloom filter indicated they already had them. The actual
     * measure of bloom-filter bandwidth saving — see SimTransport.ExchangeMetrics.
     */
    private val _bloomSkips = MutableStateFlow(0L)
    val bloomSkips: StateFlow<Long> = _bloomSkips.asStateFlow()
    fun recordBloomSkips(n: Int) { if (n > 0) _bloomSkips.value += n }

    /** Current scheduler queue depth. */
    val schedulerQueueDepth: Int get() = scheduler.queueDepth

    fun recordProcessed() { _messagesProcessed.value++ }
    fun recordDupDrop()   { _dupDrops.value++ }

    fun deliverExchange(result: PeerExchangeResult) {
        gossipEngine.onExchange(result)
    }

    suspend fun takeOutbound(peerUserId: String, max: Int = 50) =
        gossipEngine.messagesForExchange(peerUserId).take(max)
    fun knownIds(): Set<String> = gossipEngine.knownMessageIds()

    /** All messages currently stored on this node, with full metadata. Sim assertions only.
     *  Sorted by id so ConcurrentHashMap iteration order doesn't seed the network
     *  conditioner's RNG differently across replays (O12 escalation). */
    fun knownMessages(): List<com.rumor.mesh.core.model.RumorMessage> =
        messageRepo.snapshot().sortedBy { it.id }

    /**
     * Drain the scheduler (outbound queue for locally composed messages: DMs, chunks)
     * into messageRepo so SimTransport's bloom-filter exchange can find them.
     *
     * In the real app the transport drains the scheduler during each session. In the
     * sim, SimTransport reads messageRepo directly (to avoid the destructive-take problem
     * for broadcast relay). This bridge makes locally composed messages visible to the
     * exchange mechanism without disturbing the relay-path duplicate-filter logic.
     */
    /** Test seed: populate both the repo and the duplicate filter, mirroring
     * what MessageStore.ingest would do for an incoming message. Without
     * the duplicate-filter populate, knownIds() returns empty and SimTransport
     * falls into the "no bloom needed, send everything" branch. */
    suspend fun seedKnown(msg: com.rumor.mesh.core.model.RumorMessage) {
        messageRepo.insert(msg)
        duplicateFilter.recordAndCheck(msg.id)
    }

    suspend fun flushSchedulerToRepo() {
        val msgs = scheduler.take(500)
        for (msg in msgs) {
            if (duplicateFilter.recordAndCheck(msg.id)) {
                messageRepo.insert(msg)
            }
        }
    }

    /**
     * Test/scenario seeding. Inserting into [messageRepo] directly leaves the
     * duplicate filter empty, so the bloom/idlist summary advertises "knows
     * nothing" while RBSR (which reads the repo) advertises the truth — the two
     * sync paths then legitimately deliver different counts. Seed through this
     * so every knowledge view agrees.
     */
    suspend fun seedMessage(msg: com.rumor.mesh.core.model.RumorMessage) {
        duplicateFilter.recordAndCheck(msg.id)
        messageRepo.insert(msg)
    }
}
