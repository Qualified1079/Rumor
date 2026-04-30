package com.rumor.mesh.core.transport.wifidirect

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.transport.DeviceQuirks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wi-Fi Direct transport layer.
 *
 * Responsibilities:
 *  - Manage Wi-Fi Direct group lifecycle
 *  - Accept incoming gossip connections (server role)
 *  - Initiate outgoing gossip connections (client role)
 *  - Run [GossipSession] over each established TCP socket
 *  - Emit [SessionResult]s to the gossip engine
 *
 * Device quirk handling baked in — see [DeviceQuirks] for rationale.
 */
@Singleton
class WifiDirectTransport @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val TAG = "WifiDirectTransport"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var broadcastReceiver: WifiDirectBroadcastReceiver? = null

    // Session providers — injected by MeshService after construction
    var localUserId: String = ""
    var localPublicKey: String = ""
    var messageProvider: (() -> List<RumorMessage>) = { emptyList() }
    var knownIdsProvider: (() -> Set<String>) = { emptySet() }
    var onlineUsersProvider: (() -> Map<String, Long>) = { emptyMap() }

    private val _sessionResults = MutableSharedFlow<GossipSession.SessionResult>(extraBufferCapacity = 64)
    val sessionResults: SharedFlow<GossipSession.SessionResult> = _sessionResults

    private val _isGroupOwner = MutableStateFlow(false)
    val isGroupOwner: StateFlow<Boolean> = _isGroupOwner.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    // Serialise Wi-Fi Direct API calls — BUSY errors occur if commands overlap
    private val operationQueue = Semaphore(1)
    private val handler = Handler(Looper.getMainLooper())

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        val mgr = wifiP2pManager ?: run {
            RumorLog.w(TAG, "WifiP2pManager unavailable")
            return
        }

        channel = mgr.initialize(context, Looper.getMainLooper(), null)

        broadcastReceiver = WifiDirectBroadcastReceiver(
            mgr, channel!!,
            object : WifiDirectBroadcastReceiver.Listener {
                override fun onWifiP2pEnabled(enabled: Boolean) {
                    if (enabled) onP2pEnabled()
                }
                override fun onPeersChanged() = requestPeers()
                override fun onConnectionChanged(connected: Boolean) {
                    if (connected) onConnected() else onDisconnected()
                }
                override fun onThisDeviceChanged(device: WifiP2pDevice?) {}
            }
        )

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(broadcastReceiver, filter)

        // Quirk: stale groups block new connections — always clean up first
        removeGroupThenDiscover()
    }

    fun stop() {
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        runCatching { context.unregisterReceiver(broadcastReceiver) }
        removeGroup()
        scope.cancel()
        RumorLog.i(TAG, "Wi-Fi Direct transport stopped")
    }

    // ── Connection management ─────────────────────────────────────────────────

    fun connectToPeer(device: WifiP2pDevice) {
        if (!hasLocationPermission()) return

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // GO intent 0-15: 0 = prefer client, 15 = prefer GO.
            // Set to 7 (neutral) — but Samsung devices will ignore this and demand GO.
            // Dual-role handling (run server + attempt client) covers this case.
            if (DeviceQuirks.wifiDirectDualRoleRequired) {
                groupOwnerIntent = 0
            } else {
                groupOwnerIntent = 7
            }
        }

        withOperationLock {
            wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    RumorLog.d(TAG, "Connect initiated to ${device.deviceAddress}")
                }
                override fun onFailure(reason: Int) {
                    val msg = p2pError(reason)
                    RumorLog.w(TAG, "Connect failed: $msg")
                    if (reason == WifiP2pManager.BUSY) {
                        // Back off and retry
                        handler.postDelayed({ connectToPeer(device) }, 1000)
                    }
                }
            })
        }
    }

    // ── Server socket ─────────────────────────────────────────────────────────

    private fun startServerSocket() {
        serverJob?.cancel()
        serverJob = scope.launch {
            try {
                val ss = ServerSocket(DeviceQuirks.WIFI_DIRECT_GOSSIP_PORT)
                serverSocket = ss
                RumorLog.i(TAG, "Listening on port ${DeviceQuirks.WIFI_DIRECT_GOSSIP_PORT}")
                while (isActive) {
                    val client = ss.accept()
                    launch { handleIncomingSocket(client) }
                }
            } catch (e: Exception) {
                if (isActive) RumorLog.w(TAG, "Server socket error", e)
            }
        }
    }

    private suspend fun handleIncomingSocket(socket: Socket) {
        RumorLog.d(TAG, "Incoming connection from ${socket.inetAddress}")
        runSession(socket)
    }

    // ── Client connect to Group Owner ─────────────────────────────────────────

    private fun connectAsClient() {
        scope.launch {
            // Quirk: DHCP may take a few seconds after Wi-Fi Direct connection.
            // Retry TCP with exponential backoff.
            for (delay in DeviceQuirks.tcpConnectRetryDelaysMs) {
                delay(delay)
                try {
                    val socket = Socket(DeviceQuirks.WIFI_DIRECT_GO_IP, DeviceQuirks.WIFI_DIRECT_GOSSIP_PORT)
                    socket.soTimeout = 30_000
                    RumorLog.d(TAG, "Connected as client to GO")
                    runSession(socket)
                    return@launch
                } catch (e: Exception) {
                    RumorLog.d(TAG, "Client connect attempt failed (will retry): ${e.message}")
                }
            }
            RumorLog.w(TAG, "All client connect attempts exhausted")
        }
    }

    // ── Session runner ────────────────────────────────────────────────────────

    private suspend fun runSession(socket: Socket) {
        val session = GossipSession(
            socket = socket,
            localUserId = localUserId,
            localPublicKey = localPublicKey,
            knownMessageIds = knownIdsProvider(),
            messagesToOffer = messageProvider(),
            recentOnlineUsers = onlineUsersProvider(),
        )
        val result = session.run()
        if (result != null) {
            _sessionResults.emit(result)
        }
        // Disconnect after exchange — nodes don't hold persistent connections
        withOperationLock {
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { RumorLog.d(TAG, "Group removed after session") }
                override fun onFailure(r: Int) {}
            })
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun onP2pEnabled() {
        removeGroupThenDiscover()
    }

    private fun onConnected() {
        // Request connection info to know if we're GO or client
        wifiP2pManager?.requestConnectionInfo(channel) { info ->
            val isGo = info?.isGroupOwner == true
            _isGroupOwner.value = isGo
            RumorLog.i(TAG, "Connected. Group owner: $isGo")
            if (isGo) {
                startServerSocket()
            } else {
                // Dual-role: also start server in case the "GO" is another client
                // that ignored the GO intent (Samsung quirk)
                if (DeviceQuirks.wifiDirectDualRoleRequired) startServerSocket()
                connectAsClient()
            }
        }
    }

    private fun onDisconnected() {
        _isGroupOwner.value = false
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        RumorLog.d(TAG, "Wi-Fi Direct disconnected")
    }

    private fun requestPeers() {
        if (!hasLocationPermission()) return
        wifiP2pManager?.requestPeers(channel) { peers ->
            _peerCount.value = peers.deviceList.size
            RumorLog.d(TAG, "Peers visible: ${peers.deviceList.size}")
            peers.deviceList.forEach { connectToPeer(it) }
        }
    }

    private fun removeGroupThenDiscover() {
        withOperationLock {
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    RumorLog.d(TAG, "Stale group removed")
                    discoverPeers()
                }
                override fun onFailure(r: Int) {
                    // No group to remove — proceed directly
                    discoverPeers()
                }
            })
        }
    }

    private fun discoverPeers() {
        if (!hasLocationPermission()) return
        withOperationLock {
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { RumorLog.d(TAG, "Peer discovery started") }
                override fun onFailure(r: Int) {
                    val msg = p2pError(r)
                    RumorLog.w(TAG, "Peer discovery failed: $msg")
                    if (r == WifiP2pManager.BUSY) {
                        handler.postDelayed({ discoverPeers() }, 2000)
                    }
                }
            })
        }
    }

    private fun removeGroup() {
        withOperationLock {
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(r: Int) {}
            })
        }
    }

    private fun withOperationLock(block: () -> Unit) {
        if (operationQueue.tryAcquire()) {
            try { block() }
            finally {
                // Release after a short delay to avoid BUSY errors on rapid successive calls
                handler.postDelayed({ operationQueue.release() }, 300)
            }
        } else {
            RumorLog.d(TAG, "Operation skipped — queue busy")
        }
    }

    private fun hasLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun p2pError(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.BUSY            -> "BUSY"
        WifiP2pManager.ERROR           -> "ERROR"
        else                           -> "reason $reason"
    }
}
