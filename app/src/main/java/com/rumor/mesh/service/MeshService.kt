package com.rumor.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rumor.mesh.MainActivity
import com.rumor.mesh.R
import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.mode.ModeState
import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.block.BlocklistGossipBridge
import com.rumor.mesh.core.transfer.TransferAssembler
import com.rumor.mesh.core.transfer.TransferSender
import com.rumor.mesh.core.routing.BreadcrumbCache
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.transport.ble.BleDiscoveryManager
import com.rumor.mesh.core.transport.wifidirect.WifiDirectTransport
import com.rumor.mesh.data.ContactDao
import com.rumor.mesh.plugin.PluginCatalog
import com.rumor.mesh.plugin.PluginDescriptor
import com.rumor.mesh.plugin.PluginRegistry
import com.rumor.mesh.plugin.meshcore.MeshCoreBridge
import com.rumor.mesh.plugin.meshtastic.MeshtasticBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Base64

/**
 * Foreground service that orchestrates all mesh modules.
 *
 * This is the only class that imports from multiple modules simultaneously.
 * Its sole job is lifecycle management and wiring — no protocol logic lives here.
 *
 * Wiring summary
 * --------------
 * 1. [BleDiscoveryManager] advertises and scans (no identity, just a signal)
 * 2. [WifiDirectTransport] handles peer connections and produces [PeerExchangeResult]s
 * 3. [GossipEngine] consumes [PeerExchangeResult]s, produces outbound messages
 * 4. [PluginRegistry] receives incoming messages and forwards them to bridge plugins
 *
 * Plugin registration
 * -------------------
 * To add a new bridge plugin, instantiate it and call [PluginRegistry.register]
 * inside [startMesh]. That's the only place you need to touch.
 */
class MeshService : Service(), MeshController {

    private val TAG = "MeshService"
    private val CHANNEL_ID = "rumor_mesh"
    private val NOTIFICATION_ID = 1
    /** Gossip-round cadence while a Rumor peer is BLE-visible. */
    private val GOSSIP_ROUND_INTERVAL_MS = 15_000L

    /**
     * O106: the host-agnostic orchestration (reseed, purges, coordinator,
     * beacon/recompute loops, HLC persistence, incoming→plugins). Built at
     * [startMesh] from the unlocked identity; null until the mesh is running.
     * This service is one host of it — the :node desktop runtime is another.
     */
    @Volatile private var meshRuntime: com.rumor.mesh.core.runtime.MeshRuntime? = null

    private val bleDiscovery: BleDiscoveryManager by inject()
    private val wifiDirectTransport: WifiDirectTransport by inject()
    private val gossipEngine: GossipEngine by inject()
    private val identityManager: IdentityManager by inject()
    private val onlineStatusTracker: OnlineStatusTracker by inject()
    private val topologyTracker: TopologyTracker by inject()
    private val breadcrumbCache: BreadcrumbCache by inject()
    private val meshViewTracker: com.rumor.mesh.core.routing.MeshViewTracker by inject()
    private val pluginRegistry: PluginRegistry by inject()
    private val pluginCatalog: PluginCatalog by inject()
    private val modeState: ModeState by inject()
    private val modeOrchestrator: com.rumor.mesh.core.policy.ModeOrchestrator by inject()

    /**
     * O93 LAN path. Built at [startMesh] like the coordinator (needs the
     * unlocked identity); null until the mesh is running.
     */
    @Volatile private var lanTransportManager: com.rumor.mesh.core.transport.lan.LanTransportManager? = null
    private var lanCollectJob: kotlinx.coroutines.Job? = null

    /** §5: true while the mesh wiring is live — see the guard in [startMesh]. */
    @Volatile private var meshStarted = false
    private val contactRepo: com.rumor.mesh.core.data.ContactRepository by inject()
    private val messageRepo: com.rumor.mesh.core.data.MessageRepository by inject()
    private val contactDao: ContactDao by inject()
    private val transferSender: TransferSender by inject()
    // Injected for side effect — its constructor subscribes to incoming gossip.
    @Suppress("unused")
    private val transferAssembler: TransferAssembler by inject()
    private val messageScheduler: com.rumor.mesh.core.scheduling.MessageScheduler by inject()
    @Suppress("unused")
    private val blocklistGossipBridge: BlocklistGossipBridge by inject()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getController(): MeshController = this@MeshService
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Starting…")
        // The connectedDevice service type was added in API 29. On older versions,
        // start without a type — the manifest attribute is harmlessly ignored.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        RumorLog.i(TAG, "MeshService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMesh()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopMesh()
        scope.cancel()
        super.onDestroy()
        RumorLog.i(TAG, "MeshService destroyed")
    }

    // ── Mesh lifecycle ────────────────────────────────────────────────────────

    private fun startMesh() {
        val identity = identityManager.identity.value ?: run {
            RumorLog.w(TAG, "Identity not unlocked — start the app and unlock first")
            updateNotification("Locked — unlock the app to connect")
            return
        }

        // §5 reentrancy guard: onStartCommand re-fires on every foreground
        // transition; without this every cycle stacked a fresh set of flow
        // collectors (duplicate message processing, double-forwarding bridges)
        // and orphaned transport state. Latched only once identity is unlocked
        // so a locked start still retries.
        if (meshStarted) return
        meshStarted = true

        // O106: everything host-agnostic (reseed, purges, coordinator, beacon +
        // recompute loops, HLC persistence, incoming→plugins) lives in the
        // shared MeshRuntime; this service supplies only the Android edges.
        val hlcPrefs = getSharedPreferences("rumor_hlc", MODE_PRIVATE)
        val runtime = com.rumor.mesh.core.runtime.MeshRuntime(
            gossipEngine = gossipEngine,
            identityProvider = identityManager,
            contactRepo = contactRepo,
            messageRepo = messageRepo,
            meshView = meshViewTracker,
            modeProvider = { modeState.mode.value },
            beaconIntervalMsProvider = { modeState.envelope.presenceBeaconIntervalMs },
            hlcStore = object : com.rumor.mesh.core.runtime.HlcStore {
                override fun load() = com.rumor.mesh.core.time.HlcTimestamp(
                    hlcPrefs.getLong("wallMs", 0L),
                    hlcPrefs.getLong("counter", 0L),
                )
                override fun save(ts: com.rumor.mesh.core.time.HlcTimestamp) {
                    hlcPrefs.edit().putLong("wallMs", ts.wallMs).putLong("counter", ts.counter).apply()
                }
            },
            incomingSink = { msg -> pluginRegistry.onMessageReceived(msg) },
            backboneRealizer = { role -> wifiDirectTransport.applyBackboneRole(role) },
        )
        meshRuntime = runtime
        runtime.start(scope)

        // ── Wire transport → gossip engine ───────────────────────────────────
        // TransportConfig is immutable — no mutable vars on WifiDirectTransport.
        val transportConfig = WifiDirectTransport.TransportConfig(
            localUserId         = identity.userId,
            localPublicKey      = Base64.getEncoder().encodeToString(identity.publicKeyBytes),
            signer              = identityManager::sign,
            messageProvider     = gossipEngine::messagesForExchange,
            messagesByIds       = gossipEngine::messagesByIds,
            knownIdsProvider    = gossipEngine::knownMessageIds,
            onlineUsersProvider = onlineStatusTracker::currentSnapshot,
            // O98: a peer the backbone plan wants held counts as priority, in
            // addition to any manually-pinned contact. This is the seam the
            // planner uses to control persistent-link retention.
            isPriorityPeer      = { userId ->
                meshRuntime?.coordinator?.backbonePeers?.contains(userId) == true ||
                    contactRepo.getById(userId)?.isPriorityPeer == true
            },
            onExchangeFailed    = gossipEngine::onExchangeFailed,
            isPeerNearby        = { bleDiscovery.peerNearbySignal.value },
            // O42 go-live: wiring this single provider both advertises rbsr-v1
            // and supplies the snapshot (transport derives the capability from
            // its presence). Sessions still run bloom below the adaptive
            // size gate (RBSR_MIN_SET_SIZE) or against pre-O42 peers.
            rbsrItemsProvider   = gossipEngine::rbsrSnapshot,
        )

        // ── Wire transport output → gossip engine ────────────────────────────
        // O98: a completed P2P exchange is a realizable backbone edge — the
        // runtime records it so we advertise it in our own beacon and budget
        // our degree (feedsCoordinator defaults true).
        scope.launch {
            wifiDirectTransport.exchangeResults.collect { result ->
                runtime.onExchange(result)
            }
        }

        // ── Peer count / static mode → notification ──────────────────────────
        scope.launch {
            wifiDirectTransport.peerCount.collect { updateNotification(statusText(it)) }
        }

        // ── BLE nearby signal → Wi-Fi Direct rediscovery ─────────────────────
        // BLE has no identity, just presence — a detected beacon means "worth
        // re-scanning for a Wi-Fi Direct peer now".
        //
        // The signal is a level, not an edge: it stays true while a peer remains
        // visible. A plain collect fires once per appearance, so two colocated
        // devices would gossip exactly once and never sync anything composed
        // afterwards. While the signal holds, keep starting gossip rounds on a
        // fixed cadence; when it drops, park until the next appearance.
        scope.launch {
            while (isActive) {
                if (bleDiscovery.peerNearbySignal.value) {
                    wifiDirectTransport.rediscoverPeers()
                    delay(GOSSIP_ROUND_INTERVAL_MS)
                } else {
                    bleDiscovery.peerNearbySignal.first { it }
                }
            }
        }
        // Re-arm BLE on toggle so the new scan/advertise duty cycle takes effect
        // immediately. drop(1) skips the initial value emitted on collect.
        scope.launch {
            modeState.mode.drop(1).collect { newMode ->
                updateNotification(statusText(wifiDirectTransport.peerCount.value))
                bleDiscovery.stop()
                bleDiscovery.start()
                // O57/G12 mode-change pulse: fire a fresh SELF_PRESENCE immediately
                // so peers re-weight this node's routing role without waiting for
                // the next beacon interval (entry AND exit — a MOBILE beacon is the
                // demotion pulse).
                runtime.pulseNow(newMode)
            }
        }

        // ── O93: same-LAN transport ──────────────────────────────────────────
        // Up exactly while STA-associated (where Wi-Fi Direct discovery is
        // dead). LAN exchanges feed the engine like any other, but NOT the
        // O98 coordinator: the backbone planner realizes P2P groups, and
        // planning links for peers already reachable over the LAN would make
        // the transport fight the very AP association this path exists for.
        val lanManager = com.rumor.mesh.core.transport.lan.LanTransportManager(applicationContext, {
            com.rumor.mesh.core.transport.lan.LanTransport(
                com.rumor.mesh.core.transport.lan.LanTransport.Config(
                    localUserId = identity.userId,
                    localPublicKey = Base64.getEncoder().encodeToString(identity.publicKeyBytes),
                    signer = identityManager::sign,
                    messageProvider = gossipEngine::messagesForExchange,
                    messagesByIds = gossipEngine::messagesByIds,
                    knownIdsProvider = gossipEngine::knownMessageIds,
                    onlineUsersProvider = onlineStatusTracker::currentSnapshot,
                    onExchangeFailed = gossipEngine::onExchangeFailed,
                    rbsrItemsProvider = gossipEngine::rbsrSnapshot,
                )
            )
        })
        lanManager.onTransportUp = { t ->
            lanCollectJob?.cancel()
            lanCollectJob = scope.launch {
                // feedsCoordinator=false: see the O93 note above — LAN links are
                // not backbone-realizable P2P edges.
                t.exchangeResults.collect { result -> runtime.onExchange(result, feedsCoordinator = false) }
            }
        }
        lanManager.start()
        lanTransportManager = lanManager

        // ── O80: mode auto-fire ──────────────────────────────────────────────
        // Plug/screen/battery/network signals → ModeProfile → setMode. Applies
        // only while the user's mode selection is "Auto" (manual wins, O57).
        // Idempotent — safe against startMesh re-entry.
        modeOrchestrator.start()

        // ── Declare available plugins ────────────────────────────────────────
        // The catalog persists user toggle state. Adding a new plugin = one
        // declare() call here. Nothing else in core touches plugin code paths.
        // Plugins of any kind can be added — bridges, logging, automation, etc.
        pluginCatalog.declare(
            PluginDescriptor(
                pluginId = "bridge.meshtastic",
                displayName = "Meshtastic Bridge",
                description = "Relay messages to/from a Meshtastic LoRa device",
                category = "Bridges",
                factory = { MeshtasticBridge(applicationContext) },
            )
        )
        pluginCatalog.declare(
            PluginDescriptor(
                pluginId = "bridge.meshcore",
                displayName = "MeshCore Bridge",
                description = "Relay messages to/from a MeshCore LoRa device",
                category = "Bridges",
                factory = { MeshCoreBridge(applicationContext) },
            )
        )

        // ── Start radios ─────────────────────────────────────────────────────
        bleDiscovery.start()
        wifiDirectTransport.start(transportConfig)

        // O22 / G15: kick off the scheduled-message poll loop. Fires
        // due schedules through composeBroadcast / composeDirect on its
        // own tick.
        messageScheduler.start()

        updateNotification("Mesh active")
        RumorLog.i(TAG, "Mesh started for ${identity.userId.take(16)}…")
    }

    private fun stopMesh() {
        meshStarted = false
        lanTransportManager?.stop()
        lanTransportManager = null
        lanCollectJob?.cancel()
        lanCollectJob = null
        modeOrchestrator.stop()
        messageScheduler.stop()
        pluginRegistry.unregisterAll()
        bleDiscovery.stop()
        wifiDirectTransport.stop()
        meshRuntime?.stop()
        meshRuntime = null
    }

    // ── MeshController (exposed to UI via binder) ─────────────────────────────

    override fun sendBroadcast(text: String) {
        gossipEngine.composeBroadcast(text)
    }

    override fun sendDirect(recipientId: String, text: String) {
        scope.launch {
            val contact = contactDao.getById(recipientId)
            if (contact == null) {
                RumorLog.w(TAG, "sendDirect: no contact for ${recipientId.take(16)}… — dropping")
                return@launch
            }
            val recipientKey = Base64.getDecoder().decode(contact.publicKey)
            gossipEngine.composeDirect(recipientId, recipientKey, text)
        }
    }

    override fun sendFile(
        recipientId: String?,
        contentType: ContentType,
        data: ByteArray,
        mimeType: String?,
        title: String?,
    ) {
        scope.launch {
            transferSender.sendFile(recipientId, contentType, data, mimeType, title)
        }
    }

    override fun manualRelay(message: RumorMessage) {
        gossipEngine.manualRelay(message)
    }

    override fun triggerActiveScan() {
        bleDiscovery.stop()
        bleDiscovery.start()
        wifiDirectTransport.rediscoverPeers()
        // O124: announce, don't just listen — a node with no completed exchange
        // is invisible to the mesh regardless of radio state; an immediate pulse
        // rides the next exchange instead of waiting out the beacon timer.
        meshRuntime?.pulseNow()
    }

    override fun isServiceRunning() = true

    override fun sentPlaintextFor(messageId: String): String? =
        gossipEngine.sentPlaintextFor(messageId)

    override fun availablePlugins(): List<PluginDescriptor> = pluginCatalog.available()

    override fun isPluginEnabled(pluginId: String): Boolean =
        pluginCatalog.isEnabled(pluginId)

    override fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        pluginCatalog.setEnabled(pluginId, enabled)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rumor Mesh",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the mesh connection alive in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rumor")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_mesh_signal)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    /** Notification status line, with a mode suffix when not in the default MOBILE mode. */
    private fun statusText(peerCount: Int): String {
        val base = if (peerCount > 0) {
            "$peerCount peer${if (peerCount == 1) "" else "s"} nearby"
        } else {
            "Scanning for peers…"
        }
        return when (modeState.mode.value) {
            UserMode.STATIC -> "$base · static node"
            UserMode.FREE -> "$base · free node"
            UserMode.MOBILE -> base
        }
    }
}
