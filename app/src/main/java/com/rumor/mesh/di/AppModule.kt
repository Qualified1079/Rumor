package com.rumor.mesh.di

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.block.BlocklistGossipBridge
import com.rumor.mesh.core.block.BlocklistPublisher
import com.rumor.mesh.core.block.BlocklistSubscriber
import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.core.policy.InboxPolicyManager
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.routing.BreadcrumbCache
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.scheduler.Scheduler
import com.rumor.mesh.core.transfer.TransferAssembler
import com.rumor.mesh.core.transfer.TransferSender
import com.rumor.mesh.core.transport.ble.BleDiscoveryManager
import com.rumor.mesh.core.transport.wifidirect.WifiDirectTransport
import com.rumor.mesh.data.RumorDatabase
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
import com.rumor.mesh.ui.settings.SettingsViewModel
import com.rumor.mesh.ui.transfers.TransfersViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module wiring all singletons + ViewModels.
 *
 * Replaces the previous Hilt setup. Constructor params on each class are
 * resolved via `get()`; ViewModels register through `viewModel { ... }` so
 * Compose's `koinViewModel()` can fetch them.
 */
val appModule = module {

    // ── Database + DAOs ───────────────────────────────────────────────────────
    single { RumorDatabase.create(androidContext()) }
    single { get<RumorDatabase>().messageDao() }
    single { get<RumorDatabase>().contactDao() }
    single { get<RumorDatabase>().breadcrumbDao() }
    single { get<RumorDatabase>().routeDao() }
    single { get<RumorDatabase>().blockEntryDao() }
    single { get<RumorDatabase>().subscribedBlocklistDao() }
    single { get<RumorDatabase>().blocklistEntryDao() }
    single { get<RumorDatabase>().transferDao() }
    single { get<RumorDatabase>().chunkDao() }

    // ── Identity ──────────────────────────────────────────────────────────────
    single { IdentityManager(androidContext()) }

    // ── Block module ──────────────────────────────────────────────────────────
    // BlockManager is consulted only by the inbox filter, never the relay path.
    single { BlockManager(get(), get(), get()) }
    single { BlocklistPublisher(get(), get()) }
    single { BlocklistSubscriber(get(), get()) }
    // Bridge wires publisher/subscriber into gossip flow. Created after GossipEngine.


    // ── Protocol layer ────────────────────────────────────────────────────────
    single { DuplicateFilter() }
    single { MessageStore(get(), get(), get()) }
    single { OnlineStatusTracker() }
    single { TopologyTracker(get()) }
    single { BreadcrumbCache(get()) }
    single { Scheduler() }
    single { InboxPolicyManager(androidContext(), get()) }
    single { GossipEngine(get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    // ── Transfer layer ────────────────────────────────────────────────────────
    single { TransferAssembler(get(), get(), get()) }
    single { TransferSender(get(), get(), get(), get()) }

    // ── Blocklist gossip bridge (subscribes to gossip on construction) ────────
    single { BlocklistGossipBridge(get(), get(), get(), get()) }

    // ── Transport ─────────────────────────────────────────────────────────────
    single { BleDiscoveryManager(androidContext()) }
    single { WifiDirectTransport(androidContext()) }

    // ── Plugins ───────────────────────────────────────────────────────────────
    single { PluginRegistry(get(), get(), get()) }
    single { PluginCatalog(androidContext(), get()) }

    // ── Service binding bridge ────────────────────────────────────────────────
    single { MeshControllerHolder() }

    // ── ViewModels ────────────────────────────────────────────────────────────
    viewModel { FeedViewModel(get(), get<MeshControllerHolder>()) }
    viewModel { ContactsViewModel(get(), get()) }
    viewModel { SettingsViewModel(get(), androidContext()) }
    viewModel { PluginsViewModel(get()) }
    viewModel { InboxPolicyViewModel(get()) }
    viewModel { BlockManagementViewModel(get(), get()) }
    viewModel { MessagesViewModel(get(), get(), get(), get()) }
    viewModel { ThreadViewModel(get(), get(), get(), get(), get()) }
    viewModel { TransfersViewModel(get(), get()) }
}
