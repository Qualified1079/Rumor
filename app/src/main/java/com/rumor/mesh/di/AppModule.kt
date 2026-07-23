package com.rumor.mesh.di

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.block.BlocklistGossipBridge
import com.rumor.mesh.core.block.BlocklistPublisher
import com.rumor.mesh.core.block.BlocklistSubscriber
import com.rumor.mesh.core.data.BlockEntryRepository
import com.rumor.mesh.core.data.BlocklistEntryRepository
import com.rumor.mesh.core.data.FilterSubscriptionRepository
import com.rumor.mesh.core.data.KeywordFilterListRepository
import com.rumor.mesh.core.data.ScheduledMessageRepository
import com.rumor.mesh.core.scheduling.MessageScheduler
import com.rumor.mesh.core.filter.KeywordFilterGossipBridge
import com.rumor.mesh.core.filter.KeywordFilterPublisher
import com.rumor.mesh.core.filter.KeywordFilterSubscriber
import com.rumor.mesh.core.data.BreadcrumbRepository
import com.rumor.mesh.core.data.SubscribedBlocklistRepository
import com.rumor.mesh.core.data.ChunkRepository
import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.data.MessageRepository
import com.rumor.mesh.core.data.RouteRepository
import com.rumor.mesh.core.data.TransferRepository
import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.logging.AndroidLogSink
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.policy.InboxFilter
import com.rumor.mesh.core.policy.InboxPolicyManager
import com.rumor.mesh.core.mode.ModeState
import com.rumor.mesh.core.policy.ModeStateManager
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.routing.BreadcrumbCache
import com.rumor.mesh.core.routing.NeighborStore
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.scheduler.Scheduler
import com.rumor.mesh.core.transfer.TransferAssembler
import com.rumor.mesh.core.transfer.TransferSender
import com.rumor.mesh.core.transport.ble.BleDiscoveryManager
import com.rumor.mesh.core.transport.wifidirect.WifiDirectTransport
import com.rumor.mesh.data.RumorDatabase
import com.rumor.mesh.data.adapter.BreadcrumbRepositoryAdapter
import com.rumor.mesh.data.adapter.BlockEntryRepositoryAdapter
import com.rumor.mesh.data.adapter.BlocklistEntryRepositoryAdapter
import com.rumor.mesh.data.adapter.FilterSubscriptionRepositoryAdapter
import com.rumor.mesh.data.adapter.KeywordFilterListRepositoryAdapter
import com.rumor.mesh.data.adapter.ScheduledMessageRepositoryAdapter
import com.rumor.mesh.data.adapter.ChunkRepositoryAdapter
import com.rumor.mesh.data.adapter.ContactRepositoryAdapter
import com.rumor.mesh.data.adapter.MessageRepositoryAdapter
import com.rumor.mesh.data.adapter.RouteRepositoryAdapter
import com.rumor.mesh.data.adapter.SubscribedBlocklistRepositoryAdapter
import com.rumor.mesh.data.adapter.TransferRepositoryAdapter
import com.rumor.mesh.plugin.DmEnvelopeRegistry
import com.rumor.mesh.plugin.PluginCatalog
import com.rumor.mesh.plugin.PluginRegistry
import com.rumor.mesh.service.MeshControllerHolder
import com.rumor.mesh.ui.blocks.BlockManagementViewModel
import com.rumor.mesh.ui.contacts.ContactsViewModel
import com.rumor.mesh.ui.feed.FeedViewModel
import com.rumor.mesh.ui.inbox.InboxPolicyViewModel
import com.rumor.mesh.ui.messages.MessagesViewModel
import com.rumor.mesh.ui.messages.ThreadViewModel
import com.rumor.mesh.ui.plugins.PluginsViewModel
import com.rumor.mesh.ui.debug.DebugMetricsViewModel
import com.rumor.mesh.ui.settings.ChangePassphraseViewModel
import com.rumor.mesh.ui.settings.SettingsViewModel
import com.rumor.mesh.ui.transfers.TransfersViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {

    // Install Android logcat sink once at module init time.
    single(createdAtStart = true) { RumorLog.apply { sink = AndroidLogSink } }

    // ── Database ──────────────────────────────────────────────────────────────
    single { RumorDatabase.create(androidContext()) }

    // ── Repository adapters (Room → core interfaces) ──────────────────────────
    single<MessageRepository>  { MessageRepositoryAdapter(get<RumorDatabase>().messageDao()) }
    single<ContactRepository>  { ContactRepositoryAdapter(get<RumorDatabase>().contactDao()) }
    single<RouteRepository>    { RouteRepositoryAdapter(get<RumorDatabase>().routeDao()) }
    single<BreadcrumbRepository> { BreadcrumbRepositoryAdapter(get<RumorDatabase>().breadcrumbDao()) }
    single<TransferRepository> { TransferRepositoryAdapter(get<RumorDatabase>().transferDao()) }
    single<ChunkRepository>    { ChunkRepositoryAdapter(get<RumorDatabase>().chunkDao()) }
    // Raw DAOs exposed for the Koin verify() static check (G6 AppModuleTest) —
    // every adapter constructor in this module takes a DAO directly, so verify
    // needs each DAO to be resolvable as its own binding. Production code path
    // is unchanged: nothing actually calls `get<MessageDao>()` etc., the
    // adapters consume the DAO at construction time inside their own lambdas.
    single { get<RumorDatabase>().messageDao() }
    single { get<RumorDatabase>().contactDao() }
    single { get<RumorDatabase>().routeDao() }
    single { get<RumorDatabase>().breadcrumbDao() }
    single { get<RumorDatabase>().blockEntryDao() }
    single { get<RumorDatabase>().subscribedBlocklistDao() }
    single { get<RumorDatabase>().blocklistEntryDao() }
    single { get<RumorDatabase>().transferDao() }
    single { get<RumorDatabase>().chunkDao() }
    single<BlockEntryRepository>        { BlockEntryRepositoryAdapter(get<RumorDatabase>().blockEntryDao()) }
    single<SubscribedBlocklistRepository> { SubscribedBlocklistRepositoryAdapter(get<RumorDatabase>().subscribedBlocklistDao()) }
    single<BlocklistEntryRepository>    { BlocklistEntryRepositoryAdapter(get<RumorDatabase>().blocklistEntryDao()) }
    single { get<RumorDatabase>().keywordFilterListDao() }
    single { get<RumorDatabase>().filterSubscriptionDao() }
    single<KeywordFilterListRepository>  { KeywordFilterListRepositoryAdapter(get()) }
    single<FilterSubscriptionRepository> { FilterSubscriptionRepositoryAdapter(get()) }
    single { get<RumorDatabase>().scheduledMessageDao() }
    single<ScheduledMessageRepository>   { ScheduledMessageRepositoryAdapter(get()) }

    // ── Identity ──────────────────────────────────────────────────────────────
    single { IdentityManager(androidContext()) }
    single<IdentityProvider> { get<IdentityManager>() }

    // ── Device mode (O62) ───────────────────────────────────────────────────────
    single { ModeStateManager(androidContext()) }
    single<ModeState> { get<ModeStateManager>() }
    // O80 auto-fire orchestrator; MeshService owns start/stop.
    single { com.rumor.mesh.core.policy.ModeOrchestrator(androidContext(), get()) }

    // ── Block module ──────────────────────────────────────────────────────────
    single {
        val identityProvider = get<IdentityProvider>()
        BlockManager(
            get<BlockEntryRepository>(),
            get<SubscribedBlocklistRepository>(),
            get<BlocklistEntryRepository>(),
            localUserId = { identityProvider.identity.value?.userId },
        )
    }
    single { BlocklistPublisher(get<BlockEntryRepository>(), get<IdentityProvider>()) }
    single { BlocklistSubscriber(get<SubscribedBlocklistRepository>(), get<BlocklistEntryRepository>()) }

    // ── Protocol layer ────────────────────────────────────────────────────────
    single { DuplicateFilter() }
    single { MessageStore(get(), get(), get(), get<ModeState>()) }
    single { OnlineStatusTracker() }
    single { NeighborStore() }
    single { TopologyTracker(get(), get()) }
    single { BreadcrumbCache(get()) }
    single { Scheduler(modeState = get<ModeState>()) }
    single { InboxPolicyManager(androidContext(), get(), localUserId = { get<IdentityManager>().identity.value?.userId }) }
    single<InboxFilter> { get<InboxPolicyManager>() }
    single { DmEnvelopeRegistry() }
    single { com.rumor.mesh.core.protocol.CanaryMetrics() }
    single<com.rumor.mesh.core.Clock> { com.rumor.mesh.core.SystemClock }
    // O79 RoomSubscriptionRepository — Room/SQLite-backed adapter.
    single { get<RumorDatabase>().roomSubscriptionDao() }
    single<com.rumor.mesh.core.data.RoomSubscriptionRepository> {
        com.rumor.mesh.data.adapter.RoomSubscriptionRepositoryAdapter(get())
    }

    // O79 room-subscription provider — bridges the persistent
    // RoomSubscriptionRepository to the synchronous
    // RoomSubscriptionProvider contract the GossipEngine consumes
    // on every ROOM_MESSAGE receive. Caching strategy: an in-memory
    // snapshot refreshed on demand. For v1 (low subscription counts)
    // we re-query the repo per inbound message and rely on Room's
    // own caching layer; if profiling shows this matters we'll
    // promote to an explicit in-memory projection cache invalidated
    // on subscribe/unsubscribe.
    //
    // O91 (closed): localX25519StaticPrivate now derives the X25519 private
    // from the unlocked Ed25519 identity seed (SHA-512(seed)[0:32], RFC 7748
    // clamped). Caller of the engine zeroes the returned bytes after use.
    // Returns null while the identity is locked.
    single<GossipEngine.RoomSubscriptionProvider> {
        val repo = get<com.rumor.mesh.core.data.RoomSubscriptionRepository>()
        val identityProvider = get<IdentityProvider>()
        object : GossipEngine.RoomSubscriptionProvider {
            override fun openRoomIds(): List<String> = kotlinx.coroutines.runBlocking {
                repo.getAll()
                    .filter { it.mode == com.rumor.mesh.core.data.RoomSubscriptionMode.OPEN }
                    .map { it.roomId }
            }
            override fun encryptedRoomSubscriptions(): List<com.rumor.mesh.core.protocol.RoomTagMatcher.EncryptedRoomSubscription> = kotlinx.coroutines.runBlocking {
                repo.getAll()
                    .filter { it.mode == com.rumor.mesh.core.data.RoomSubscriptionMode.ENCRYPTED }
                    .map { com.rumor.mesh.core.protocol.RoomTagMatcher.EncryptedRoomSubscription(it.roomId, it.routingKey) }
            }
            override fun localX25519StaticPrivate(): ByteArray? {
                val seed = identityProvider.identity.value?.privateKeyBytes ?: return null
                return com.rumor.mesh.core.crypto.CryptoManager.ed25519ToX25519PrivateSeed(seed)
            }
        }
    }
    // O98 MeshView substrate — populated from inbound SELF_PRESENCE beacons; feeds the persistence planner.
    single { com.rumor.mesh.core.routing.MeshViewTracker() }
    single { GossipEngine(get(), get(), get<IdentityProvider>(), get(), get(), get(), get(), get(), get(), breadcrumbs = get<BreadcrumbCache>(), canaryMetrics = get(), dmEnvelopeRegistry = get(), roomSubscriptionProvider = get<GossipEngine.RoomSubscriptionProvider>(), meshView = get<com.rumor.mesh.core.routing.MeshViewTracker>()) }

    // ── Transfer layer ────────────────────────────────────────────────────────
    single { TransferAssembler(get(), get(), get()) }
    single { TransferSender(get(), get<IdentityProvider>(), get(), get()) }

    // ── Blocklist gossip bridge ───────────────────────────────────────────────
    single { BlocklistGossipBridge(get(), get(), get(), get()) }

    // ── Keyword filters (O67) ─────────────────────────────────────────────────
    single { KeywordFilterPublisher(get<IdentityProvider>()) }
    single { KeywordFilterSubscriber(get<KeywordFilterListRepository>(), get<FilterSubscriptionRepository>()) }
    single { KeywordFilterGossipBridge(get(), get(), get()) }

    // ── Scheduled messages (O22 / G15) ────────────────────────────────────────
    single { MessageScheduler(get<ScheduledMessageRepository>(), get<GossipEngine>(), get<ContactRepository>()) }

    // ── Transport ─────────────────────────────────────────────────────────────
    single { BleDiscoveryManager(androidContext(), get<ModeState>()) }
    single { WifiDirectTransport(androidContext()) }

    // ── Plugins ───────────────────────────────────────────────────────────────
    single { PluginRegistry(get(), get<IdentityManager>(), get(), get()) }
    single { PluginCatalog(androidContext(), get()) }

    // ── Service ───────────────────────────────────────────────────────────────
    single { MeshControllerHolder() }

    // ── ViewModels ────────────────────────────────────────────────────────────
    viewModel { FeedViewModel(get(), get<MeshControllerHolder>(), get<IdentityProvider>(), get()) }
    viewModel { ContactsViewModel(get(), get(), get<MeshControllerHolder>()) }
    viewModel { SettingsViewModel(get(), get(), androidContext()) }
    viewModel { PluginsViewModel(get()) }
    viewModel { InboxPolicyViewModel(get()) }
    viewModel { BlockManagementViewModel(get(), get()) }
    viewModel { MessagesViewModel(get(), get(), get(), get()) }
    viewModel { ThreadViewModel(get(), get(), get(), get(), get()) }
    viewModel { TransfersViewModel(get(), get()) }
    viewModel { DebugMetricsViewModel(get()) }
    viewModel { ChangePassphraseViewModel(get()) }
}
