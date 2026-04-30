package com.rumor.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rumor.mesh.MainActivity
import com.rumor.mesh.R
import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.routing.BreadcrumbCache
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.transport.ble.BleDiscoveryManager
import com.rumor.mesh.core.transport.wifidirect.WifiDirectTransport
import com.rumor.mesh.plugin.PluginRegistry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that orchestrates all mesh modules.
 * Keeps the gossip engine alive in the background.
 *
 * Wiring:
 *   BLE peerNearbySignal → trigger Wi-Fi Direct peer discovery
 *   Wi-Fi Direct sessionResults → GossipEngine.onSessionResult
 *   GossipEngine incomingMessages → PluginRegistry
 */
@AndroidEntryPoint
class MeshService : Service(), MeshController {

    private val TAG = "MeshService"
    private val CHANNEL_ID = "rumor_mesh"
    private val NOTIFICATION_ID = 1

    @Inject lateinit var bleDiscovery: BleDiscoveryManager
    @Inject lateinit var wifiDirectTransport: WifiDirectTransport
    @Inject lateinit var gossipEngine: GossipEngine
    @Inject lateinit var identityManager: IdentityManager
    @Inject lateinit var onlineStatusTracker: OnlineStatusTracker
    @Inject lateinit var topologyTracker: TopologyTracker
    @Inject lateinit var breadcrumbCache: BreadcrumbCache
    @Inject lateinit var pluginRegistry: PluginRegistry

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getController(): MeshController = this@MeshService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        RumorLog.i(TAG, "MeshService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMesh()
        return START_STICKY  // restart automatically if killed
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
            RumorLog.w(TAG, "No unlocked identity — mesh not started")
            updateNotification("Locked — unlock the app to connect")
            return
        }

        // Wire transport providers
        wifiDirectTransport.localUserId = identity.userId
        wifiDirectTransport.localPublicKey = identity.publicKeyBytes
            .let { java.util.Base64.getEncoder().encodeToString(it) }
        wifiDirectTransport.messageProvider = { gossipEngine.messagesForExchange() }
        wifiDirectTransport.knownIdsProvider = { gossipEngine.knownMessageIds() }
        wifiDirectTransport.onlineUsersProvider = { onlineStatusTracker.currentAsElapsed() }

        // BLE → Wi-Fi Direct handoff
        scope.launch {
            bleDiscovery.peerNearbySignal.collect { nearby ->
                if (nearby) {
                    RumorLog.d(TAG, "BLE signal: peers nearby — Wi-Fi Direct already discovering")
                    // WifiDirectTransport continuously discovers peers once started;
                    // the BLE signal is informational here (logged for diagnostics)
                }
            }
        }

        // Session results → gossip engine
        scope.launch {
            wifiDirectTransport.sessionResults.collect { result ->
                RumorLog.d(TAG, "Session result from ${result.peerUserId.take(16)}…")
                gossipEngine.onSessionResult(result)
            }
        }

        // Gossip engine output → plugin registry
        scope.launch {
            gossipEngine.incomingMessages.collect { msg ->
                pluginRegistry.onMessageReceived(msg)
            }
        }

        // Peer count → notification
        scope.launch {
            wifiDirectTransport.peerCount.collect { count ->
                val text = if (count > 0) "$count peer${if (count == 1) "" else "s"} nearby"
                else "Scanning for peers…"
                updateNotification(text)
            }
        }

        // Start radios
        bleDiscovery.start()
        wifiDirectTransport.start()

        updateNotification("Connected to mesh")
        RumorLog.i(TAG, "Mesh started for ${identity.userId.take(16)}…")
    }

    private fun stopMesh() {
        bleDiscovery.stop()
        wifiDirectTransport.stop()
    }

    // ── MeshController ────────────────────────────────────────────────────────

    override fun sendBroadcast(text: String) {
        gossipEngine.composeBroadcast(text)
    }

    override fun sendDirect(recipientId: String, text: String) {
        scope.launch {
            // Look up recipient's public key from contacts
            // (omitted for brevity — ContactDao lookup would go here)
            RumorLog.d(TAG, "sendDirect to ${recipientId.take(16)}…")
        }
    }

    override fun manualRelay(message: RumorMessage) {
        gossipEngine.manualRelay(message)
    }

    override fun triggerActiveScan() {
        // Switch BLE to high-power scan mode temporarily
        bleDiscovery.stop()
        bleDiscovery.start()
    }

    override fun isServiceRunning() = true

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
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
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
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}
