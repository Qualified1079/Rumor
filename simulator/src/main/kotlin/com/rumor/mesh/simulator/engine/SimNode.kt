package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.policy.PermissiveInboxFilter
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.routing.BreadcrumbCache
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.scheduler.Scheduler
import com.rumor.mesh.core.transfer.AssembledTransfer
import com.rumor.mesh.core.transfer.TransferAssembler
import com.rumor.mesh.core.transfer.TransferSender
import com.rumor.mesh.simulator.data.InMemoryBlockEntryRepository
import com.rumor.mesh.simulator.data.InMemoryBlocklistEntryRepository
import com.rumor.mesh.simulator.data.InMemoryBreadcrumbRepository
import com.rumor.mesh.simulator.data.InMemoryChunkRepository
import com.rumor.mesh.simulator.data.InMemoryContactRepository
import com.rumor.mesh.simulator.data.InMemoryMessageRepository
import com.rumor.mesh.simulator.data.InMemoryRouteRepository
import com.rumor.mesh.simulator.data.InMemorySubscribedBlocklistRepository
import com.rumor.mesh.simulator.data.InMemoryTransferRepository
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
    private val scope: CoroutineScope,
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
) {
    /**
     * O98 — this node's declared [UserMode]. Scenarios set it before composing
     * self-presence beacons (`composeSelfPresence(mode, …)`); it feeds the
     * planner's degree budget via the MeshView the beacons assemble into.
     */
    var mode: com.rumor.mesh.core.model.UserMode = com.rumor.mesh.core.model.UserMode.MOBILE

    val identityProvider = SimIdentityProvider(index)
    val userId: String get() = identityProvider.identity.value!!.userId

    private val contactRepo     = InMemoryContactRepository()
    internal val messageRepo    = InMemoryMessageRepository()
    private val routeRepo       = InMemoryRouteRepository()
    private val breadcrumbRepo  = InMemoryBreadcrumbRepository()
    private val transferRepo    = InMemoryTransferRepository()
    private val chunkRepo       = InMemoryChunkRepository()
    private val blockEntryRepo  = InMemoryBlockEntryRepository()
    /** O79 — per-node room-subscription store, in-memory. Exposed so scenarios can add subscriptions. */
    val roomSubscriptionRepo    = com.rumor.mesh.simulator.data.InMemoryRoomSubscriptionRepository()
    private val subscribedRepo  = InMemorySubscribedBlocklistRepository()
    private val blocklistRepo   = InMemoryBlocklistEntryRepository()

    private val duplicateFilter = DuplicateFilter()
    private val messageStore    = MessageStore(messageRepo, contactRepo, duplicateFilter, clock = clock)
    internal val onlineTracker  = OnlineStatusTracker()
    private val topoTracker     = TopologyTracker(routeRepo)
    internal val breadcrumbs    = BreadcrumbCache(breadcrumbRepo)
    /** O98 MeshView substrate — populated from inbound SELF_PRESENCE beacons. */
    val meshView               = com.rumor.mesh.core.routing.MeshViewTracker(clock = clock)
    private val scheduler       = Scheduler()
    private val blockManager    = BlockManager(blockEntryRepo, subscribedRepo, blocklistRepo)
    private val inboxFilter     = PermissiveInboxFilter()

    /**
     * O79 receive-side subscription provider for ROOM_MESSAGE dispatch.
     * Reads from [roomSubscriptionRepo] on every inbound room message;
     * O91 (closed): localX25519StaticPrivate now derives the X25519 static
     * from the sim identity's Ed25519 seed so ENCRYPTED rooms decrypt
     * end-to-end in scenarios. Engine zeros the returned bytes after use.
     */
    private val roomSubscriptionProvider = object : GossipEngine.RoomSubscriptionProvider {
        override fun openRoomIds(): List<String> = kotlinx.coroutines.runBlocking {
            roomSubscriptionRepo.getAll()
                .filter { it.mode == com.rumor.mesh.core.data.RoomSubscriptionMode.OPEN }
                .map { it.roomId }
        }
        override fun encryptedRoomSubscriptions(): List<com.rumor.mesh.core.protocol.RoomTagMatcher.EncryptedRoomSubscription> = kotlinx.coroutines.runBlocking {
            roomSubscriptionRepo.getAll()
                .filter { it.mode == com.rumor.mesh.core.data.RoomSubscriptionMode.ENCRYPTED }
                .map { com.rumor.mesh.core.protocol.RoomTagMatcher.EncryptedRoomSubscription(it.roomId, it.routingKey) }
        }
        override fun localX25519StaticPrivate(): ByteArray? {
            val seed = identityProvider.identity.value?.privateKeyBytes ?: return null
            return com.rumor.mesh.core.crypto.CryptoManager.ed25519ToX25519PrivateSeed(seed)
        }
    }

    val gossipEngine = GossipEngine(
        messageStore    = messageStore,
        duplicateFilter = duplicateFilter,
        identityProvider = identityProvider,
        onlineStatusTracker = onlineTracker,
        topologyTracker = topoTracker,
        contactRepo     = contactRepo,
        blockManager    = blockManager,
        scheduler       = scheduler,
        inboxFilter     = inboxFilter,
        breadcrumbs     = if (useBreadcrumbs) breadcrumbs else null,
        scope           = scope,
        // Flush relayed messages immediately — real wall-clock delay (100–500 ms)
        // would equal 1–5 sim-seconds at speedMult=10, breaking multi-hop propagation.
        relayBatchMinWindowMs = 1L,
        relayBatchSpreadMs    = 1L,
        clock           = clock,
        roomSubscriptionProvider = roomSubscriptionProvider,
        meshView        = meshView,
    )

    val transferSender = TransferSender(gossipEngine, identityProvider, transferRepo, chunkRepo)
    val transferAssembler = TransferAssembler(gossipEngine, transferRepo, chunkRepo)

    /** Count of fully-reassembled inbound transfers, for chunked-delivery assertions. */
    private val _reassembledTransfers = MutableStateFlow(0L)
    val reassembledTransfers: StateFlow<Long> = _reassembledTransfers.asStateFlow()

    init {
        // Track successful reassembly events for the chunked-delivery assertion.
        scope.launch {
            transferAssembler.assembledTransfers.collect { _: AssembledTransfer ->
                _reassembledTransfers.value++
            }
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
