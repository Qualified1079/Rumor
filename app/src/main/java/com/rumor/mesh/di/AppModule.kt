package com.rumor.mesh.di

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.block.BlocklistGossipBridge
import com.rumor.mesh.core.block.BlocklistPublisher
import com.rumor.mesh.core.block.BlocklistSubscriber
import com.rumor.mesh.core.data.BreadcrumbRepository
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
import com.rumor.mesh.core.policy.StaticMode
import com.rumor.mesh.core.policy.StaticModeManager
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
    single { BlockEntryRepositoryAdapter(get<RumorDatabase>().blockEntryDao()) }
    single { SubscribedBlocklistRepositoryAdapter(get<RumorDatabase>().subscribedBlocklistDao()) }
    single { BlocklistEntryRepositoryAdapter(get<RumorDatabase>().blocklistEntryDao()) }

    // ── Raw DAOs (consumed directly by a few constructors) ────────────────────
    single { get<RumorDatabase>().contactDao() }
    single { get<RumorDatabase>().subscribedBlocklistDao() }
    single { get<RumorDatabase>().transferDao() }
    single { get<RumorDatabase>().chunkDao() }

    // ── Identity ──────────────────────────────────────────────────────────────
    single { IdentityManager(androidContext()) }
    single<IdentityProvider> { get<IdentityManager>() }

    // ── Static mode ───────────────────────────────────────────────────────────
    single { StaticModeManager(androidContext()) }
    single<StaticMode> { get<StaticModeManager>() }

    // ── Block module ──────────────────────────────────────────────────────────
    single { BlockManager(get<BlockEntryRepositoryAdapter>(), get<SubscribedBlocklistRepositoryAdapter>(), get<BlocklistEntryRepositoryAdapter>()) }
    single { BlocklistPublisher(get<BlockEntryRepositoryAdapter>(), get<IdentityProvider>()) }
    single { BlocklistSubscriber(get<SubscribedBlocklistRepositoryAdapter>(), get<BlocklistEntryRepositoryAdapter>()) }

    // ── Protocol layer ────────────────────────────────────────────────────────
    single { DuplicateFilter() }
    single { MessageStore(get(), get(), get(), get<StaticMode>()) }
    single { OnlineStatusTracker() }
    single { NeighborStore() }
    single { TopologyTracker(get(), get()) }
    single { BreadcrumbCache(get()) }
    single { Scheduler(staticMode = get<StaticMode>()) }
    single { InboxPolicyManager(androidContext(), get()) }
    single<InboxFilter> { get<InboxPolicyManager>() }
    single { DmEnvelopeRegistry() }
    single { GossipEngine(get(), get(), get<IdentityProvider>(), get(), get(), get(), get(), get(), get(), dmEnvelopeRegistry = get()) }

    // ── Transfer layer ────────────────────────────────────────────────────────
    single { TransferAssembler(get(), get(), get()) }
    single { TransferSender(get(), get<IdentityProvider>(), get(), get()) }

    // ── Blocklist gossip bridge ───────────────────────────────────────────────
    single { BlocklistGossipBridge(get(), get(), get(), get()) }

    // ── Transport ─────────────────────────────────────────────────────────────
    single { BleDiscoveryManager(androidContext(), get<StaticMode>()) }
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
