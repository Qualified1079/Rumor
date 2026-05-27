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
) {
    val identityProvider = SimIdentityProvider(index)
    val userId: String get() = identityProvider.identity.value!!.userId

    private val contactRepo     = InMemoryContactRepository()
    internal val messageRepo    = InMemoryMessageRepository()
    private val routeRepo       = InMemoryRouteRepository()
    private val breadcrumbRepo  = InMemoryBreadcrumbRepository()
    private val transferRepo    = InMemoryTransferRepository()
    private val chunkRepo       = InMemoryChunkRepository()
    private val blockEntryRepo  = InMemoryBlockEntryRepository()
    private val subscribedRepo  = InMemorySubscribedBlocklistRepository()
    private val blocklistRepo   = InMemoryBlocklistEntryRepository()

    private val duplicateFilter = DuplicateFilter()
    private val messageStore    = MessageStore(messageRepo, contactRepo, duplicateFilter)
    private val onlineTracker   = OnlineStatusTracker()
    private val topoTracker     = TopologyTracker(routeRepo)
    private val breadcrumbs     = BreadcrumbCache(breadcrumbRepo)
    private val scheduler       = Scheduler()
    private val blockManager    = BlockManager(blockEntryRepo, subscribedRepo, blocklistRepo)
    private val inboxFilter     = PermissiveInboxFilter()

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
        scope           = scope,
        // Flush relayed messages immediately — real wall-clock delay (100–500 ms)
        // would equal 1–5 sim-seconds at speedMult=10, breaking multi-hop propagation.
        relayBatchMinWindowMs = 1L,
        relayBatchSpreadMs    = 1L,
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
    }

    // ── Metrics state exposed to SimWorld / dashboard ─────────────────────────

    /** Number of messages this node has originated and received. */
    private val _messagesProcessed = MutableStateFlow(0L)
    val messagesProcessed: StateFlow<Long> = _messagesProcessed.asStateFlow()

    /** Number of messages dropped by dedup (already seen). */
    private val _dupDrops = MutableStateFlow(0L)
    val dupDrops: StateFlow<Long> = _dupDrops.asStateFlow()

    /** Current scheduler queue depth. */
    val schedulerQueueDepth: Int get() = scheduler.queueDepth

    fun recordProcessed() { _messagesProcessed.value++ }
    fun recordDupDrop()   { _dupDrops.value++ }

    fun deliverExchange(result: PeerExchangeResult) {
        gossipEngine.onExchange(result)
    }

    fun takeOutbound(peerUserId: String, max: Int = 50) =
        gossipEngine.messagesForExchange(peerUserId).take(max)
    fun knownIds(): Set<String> = gossipEngine.knownMessageIds()

    /** All messages currently stored on this node, with full metadata. Sim assertions only. */
    fun knownMessages(): List<com.rumor.mesh.core.model.RumorMessage> = messageRepo.snapshot()

    /**
     * Drain the scheduler (outbound queue for locally composed messages: DMs, chunks)
     * into messageRepo so SimTransport's bloom-filter exchange can find them.
     *
     * In the real app the transport drains the scheduler during each session. In the
     * sim, SimTransport reads messageRepo directly (to avoid the destructive-take problem
     * for broadcast relay). This bridge makes locally composed messages visible to the
     * exchange mechanism without disturbing the relay-path duplicate-filter logic.
     */
    fun flushSchedulerToRepo() {
        val msgs = scheduler.take(500)
        for (msg in msgs) {
            if (duplicateFilter.recordAndCheck(msg.id)) {
                messageRepo.insert(msg)
            }
        }
    }
}
