package com.rumor.mesh.core.runtime

import com.rumor.mesh.core.Clock
import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.data.BlockEntryRepository
import com.rumor.mesh.core.data.BlocklistEntryRepository
import com.rumor.mesh.core.data.BreadcrumbRepository
import com.rumor.mesh.core.data.ChunkRepository
import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.data.MessageRepository
import com.rumor.mesh.core.data.RoomRepository
import com.rumor.mesh.core.data.RoomSubscriptionRepository
import com.rumor.mesh.core.data.RouteRepository
import com.rumor.mesh.core.data.SubscribedBlocklistRepository
import com.rumor.mesh.core.data.TransferRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.mode.ModeState
import com.rumor.mesh.core.policy.InboxFilter
import com.rumor.mesh.core.protocol.CanaryMetrics
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.protocol.RelayBatcher
import com.rumor.mesh.core.protocol.RepoRoomSubscriptionProvider
import com.rumor.mesh.core.protocol.RoomAuthorityResolver
import com.rumor.mesh.core.routing.BreadcrumbCache
import com.rumor.mesh.core.routing.MeshViewTracker
import com.rumor.mesh.core.routing.NeighborStore
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.scheduler.Scheduler
import com.rumor.mesh.core.transfer.TransferAssembler
import com.rumor.mesh.core.transfer.TransferSender
import com.rumor.mesh.plugin.DmEnvelopeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/** O252 — the storage a host supplies to [MeshGraph]: Room on Android, in-memory elsewhere. */
class MeshRepositories(
    val messages: MessageRepository,
    val contacts: ContactRepository,
    val routes: RouteRepository,
    val breadcrumbs: BreadcrumbRepository,
    val transfers: TransferRepository,
    val chunks: ChunkRepository,
    val blockEntries: BlockEntryRepository,
    val subscribedBlocklists: SubscribedBlocklistRepository,
    val blocklistEntries: BlocklistEntryRepository,
    val roomSubscriptions: RoomSubscriptionRepository,
    val rooms: RoomRepository,
)

/**
 * O252 — the one place the engine graph is built. AppModule, SimNode and
 * `:node` each used to construct GossipEngine and its collaborators by hand,
 * and they drifted (self-block guard, mode boosts and all room wiring were
 * missing from some hosts with nothing failing). A host now supplies only
 * storage and platform hooks; everything that is protocol is decided here, so a
 * simulator or `:node` run exercises the same graph the phone runs.
 *
 * Host knobs are deliberately few and named for why they exist: the clock and
 * relay windows (sim time), [breadcrumbRouting] (O29 A/B baseline runs), and
 * the shared [NeighborStore]/[DmEnvelopeRegistry] that Android plugins and
 * transports also hold. A new engine dependency is added HERE, never in a host.
 */
class MeshGraph(
    val repos: MeshRepositories,
    val identityProvider: IdentityProvider,
    val modeState: ModeState,
    inboxFilter: InboxFilter,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    clock: Clock = SystemClock,
    neighborStore: NeighborStore = NeighborStore(),
    dmEnvelopeRegistry: DmEnvelopeRegistry = DmEnvelopeRegistry(),
    breadcrumbRouting: Boolean = true,
    relayBatchMinWindowMs: Long = RelayBatcher.MIN_WINDOW_MS,
    relayBatchSpreadMs: Long = RelayBatcher.SPREAD_MS,
) {
    // Every collaborator's background work (prune loops, async upserts, transfer
    // watchdogs) runs in a child of the host's scope, so cancelling that scope
    // stops the whole graph. IO dispatcher kept from when each class owned a
    // process-lived scope of its own — and could therefore never be stopped.
    private val ioScope = CoroutineScope(scope.coroutineContext + Dispatchers.IO + SupervisorJob(scope.coroutineContext[Job]))

    val duplicateFilter = DuplicateFilter()
    val messageStore = MessageStore(repos.messages, repos.contacts, duplicateFilter, modeState, clock)
    val onlineStatusTracker = OnlineStatusTracker(clock)
    val topologyTracker = TopologyTracker(repos.routes, neighborStore, ioScope)
    val breadcrumbs = BreadcrumbCache(repos.breadcrumbs, ioScope)
    val meshView = MeshViewTracker(clock = clock)
    val scheduler = Scheduler(modeState = modeState)
    val blockManager = BlockManager(
        repos.blockEntries,
        repos.subscribedBlocklists,
        repos.blocklistEntries,
        localUserId = { identityProvider.identity.value?.userId },
        scope = ioScope,
    )
    val roomSubscriptionProvider = RepoRoomSubscriptionProvider(repos.roomSubscriptions, identityProvider)
    val roomAuthority = RoomAuthorityResolver(repos.rooms)
    val canaryMetrics = CanaryMetrics()

    val gossipEngine = GossipEngine(
        messageStore = messageStore,
        duplicateFilter = duplicateFilter,
        identityProvider = identityProvider,
        onlineStatusTracker = onlineStatusTracker,
        topologyTracker = topologyTracker,
        contactRepo = repos.contacts,
        blockManager = blockManager,
        scheduler = scheduler,
        inboxFilter = inboxFilter,
        breadcrumbs = if (breadcrumbRouting) breadcrumbs else null,
        canaryMetrics = canaryMetrics,
        scope = scope,
        dmEnvelopeRegistry = dmEnvelopeRegistry,
        relayBatchMinWindowMs = relayBatchMinWindowMs,
        relayBatchSpreadMs = relayBatchSpreadMs,
        clock = clock,
        roomSubscriptionProvider = roomSubscriptionProvider,
        roomAuthorityProvider = roomAuthority,
        roomRepo = repos.rooms,
        meshView = meshView,
        modeState = modeState,
    )

    val transferSender = TransferSender(gossipEngine, identityProvider, repos.transfers, repos.chunks, ioScope)
    val transferAssembler = TransferAssembler(gossipEngine, repos.transfers, repos.chunks, ioScope)
}
