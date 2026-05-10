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

    private val bleDiscovery: BleDiscoveryManager by inject()
    private val wifiDirectTransport: WifiDirectTransport by inject()
    private val gossipEngine: GossipEngine by inject()
    private val identityManager: IdentityManager by inject()
    private val onlineStatusTracker: OnlineStatusTracker by inject()
    private val topologyTracker: TopologyTracker by inject()
    private val breadcrumbCache: BreadcrumbCache by inject()
    private val pluginRegistry: PluginRegistry by inject()
    private val pluginCatalog: PluginCatalog by inject()
    private val contactDao: ContactDao by inject()
    private val transferSender: TransferSender by inject()
    // Injected for side effect — its constructor subscribes to incoming gossip.
    @Suppress("unused")
    private val transferAssembler: TransferAssembler by inject()
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

        // ── Wire transport → gossip engine ───────────────────────────────────
        // TransportConfig is immutable — no mutable vars on WifiDirectTransport.
        val transportConfig = WifiDirectTransport.TransportConfig(
            localUserId        = identity.userId,
            localPublicKey     = Base64.getEncoder().encodeToString(identity.publicKeyBytes),
            signer             = identityManager::sign,
            messageProvider    = gossipEngine::messagesForExchange,
            knownIdsProvider   = gossipEngine::knownMessageIds,
            onlineUsersProvider = onlineStatusTracker::currentSnapshot,
        )

        // ── Wire gossip engine output → plugins ──────────────────────────────
        scope.launch {
            gossipEngine.incomingMessages.collect { msg ->
                pluginRegistry.onMessageReceived(msg)
            }
        }

        // ── Wire transport output → gossip engine ────────────────────────────
        scope.launch {
            wifiDirectTransport.exchangeResults.collect { result ->
                gossipEngine.onExchange(result)
            }
        }

        // ── Peer count → notification ────────────────────────────────────────
        scope.launch {
            wifiDirectTransport.peerCount.collect { count ->
                updateNotification(
                    if (count > 0) "$count peer${if (count == 1) "" else "s"} nearby"
                    else "Scanning for peers…"
                )
            }
        }

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
                factory = { MeshtasticBridge() },
            )
        )
        pluginCatalog.declare(
            PluginDescriptor(
                pluginId = "bridge.meshcore",
                displayName = "MeshCore Bridge",
                description = "Relay messages to/from a MeshCore LoRa device",
                category = "Bridges",
                factory = { MeshCoreBridge() },
            )
        )

        // ── Start radios ─────────────────────────────────────────────────────
        bleDiscovery.start()
        wifiDirectTransport.start(transportConfig)

        updateNotification("Mesh active")
        RumorLog.i(TAG, "Mesh started for ${identity.userId.take(16)}…")
    }

    private fun stopMesh() {
        pluginRegistry.unregisterAll()
        bleDiscovery.stop()
        wifiDirectTransport.stop()
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
    }

    override fun isServiceRunning() = true

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
}
