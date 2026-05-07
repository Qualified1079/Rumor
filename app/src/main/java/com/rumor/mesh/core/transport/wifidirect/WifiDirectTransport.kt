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
import com.rumor.mesh.core.protocol.ExchangeSource
import com.rumor.mesh.core.protocol.PeerExchangeResult
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
 * Responsibilities
 * ----------------
 * - Manage Wi-Fi Direct group lifecycle (peer discovery, connection, teardown)
 * - Accept incoming TCP gossip connections (server role)
 * - Initiate outgoing TCP gossip connections (client role)
 * - Run [GossipSession] over each TCP socket
 * - Emit [PeerExchangeResult]s to whoever is listening (MeshService → GossipEngine)
 *
 * Device-quirk handling
 * ----------------------
 * All OEM-specific workarounds are documented in [DeviceQuirks] and applied here.
 * When fixing a new device-specific bug, add the detection to [DeviceQuirks] first,
 * then apply the workaround in this file — keeps fixes centralized and findable.
 *
 * Starting the transport
 * ----------------------
 * Call [start] with a [TransportConfig] containing the local identity and
 * providers for messages and known IDs. These are provided at start-time
 * (not as mutable fields) so there are no race conditions with the identity lock.
 */
@Singleton
class WifiDirectTransport @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val TAG = "WifiDirect"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var broadcastReceiver: WifiDirectBroadcastReceiver? = null

    /**
     * Configuration provided at [start] time. Immutable for the lifetime of this session.
     * Replace by calling [stop] then [start] again after an identity change.
     */
    data class TransportConfig(
        /** Local User ID (hex SHA-256 of public key). */
        val localUserId: String,
        /** Base64-encoded Ed25519 public key. */
        val localPublicKey: String,
        /** Returns messages to offer during the next gossip exchange. */
        val messageProvider: () -> List<RumorMessage>,
        /** Returns the set of message IDs this node already knows. */
        val knownIdsProvider: () -> Set<String>,
        /** Returns online-status elapsed-ms map to share with peers. */
        val onlineUsersProvider: () -> Map<String, Long>,
    )

    private var config: TransportConfig? = null

    /** Completed exchanges — consumed by MeshService and forwarded to GossipEngine. */
    private val _exchangeResults = MutableSharedFlow<PeerExchangeResult>(extraBufferCapacity = 64)
    val exchangeResults: SharedFlow<PeerExchangeResult> = _exchangeResults

    private val _isGroupOwner = MutableStateFlow(false)
    val isGroupOwner: StateFlow<Boolean> = _isGroupOwner.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    // Serialise WifiP2pManager calls — BUSY errors occur if commands overlap
    private val commandQueue = Semaphore(1)
    private val handler = Handler(Looper.getMainLooper())

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun start(cfg: TransportConfig) {
        config = cfg
        val mgr = wifiP2pManager ?: run {
            RumorLog.w(TAG, "WifiP2pManager unavailable — Wi-Fi Direct not starting")
            return
        }

        channel = mgr.initialize(context, Looper.getMainLooper(), null)

        broadcastReceiver = WifiDirectBroadcastReceiver(
            mgr, channel!!,
            object : WifiDirectBroadcastReceiver.Listener {
                override fun onWifiP2pEnabled(enabled: Boolean) { if (enabled) onP2pEnabled() }
                override fun onPeersChanged()                   = requestPeers()
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

        // Quirk: stale groups from previous sessions block new connections
        removeGroupThenDiscover()
        RumorLog.i(TAG, "Transport started for ${cfg.localUserId.take(16)}…")
    }

    fun stop() {
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        runCatching { context.unregisterReceiver(broadcastReceiver) }
        removeGroup()
        config = null
        scope.cancel()
        RumorLog.i(TAG, "Transport stopped")
    }

    // ── Connection management ─────────────────────────────────────────────────

    private fun connectToPeer(device: WifiP2pDevice) {
        if (!hasLocationPermission()) return
        val cfg = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Neutral GO intent. Samsung/MediaTek ignore this and always demand GO —
            // dual-role (server + client) below handles that case transparently.
            groupOwnerIntent = if (DeviceQuirks.wifiDirectDualRoleRequired) 0 else 7
        }
        enqueueCommand {
            wifiP2pManager?.connect(channel, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    RumorLog.d(TAG, "Connect initiated: ${device.deviceAddress}")
                }
                override fun onFailure(reason: Int) {
                    RumorLog.w(TAG, "Connect failed: ${p2pError(reason)}")
                    if (reason == WifiP2pManager.BUSY) {
                        handler.postDelayed({ connectToPeer(device) }, 1_000)
                    }
                }
            })
        }
    }

    // ── Server socket (Group Owner role) ──────────────────────────────────────

    private fun startServerSocket() {
        serverJob?.cancel()
        serverJob = scope.launch {
            try {
                val ss = ServerSocket(DeviceQuirks.WIFI_DIRECT_GOSSIP_PORT)
                serverSocket = ss
                RumorLog.i(TAG, "Listening on port ${DeviceQuirks.WIFI_DIRECT_GOSSIP_PORT}")
                while (isActive) {
                    val client = ss.accept()
                    launch { runSession(client) }
                }
            } catch (e: Exception) {
                if (isActive) RumorLog.w(TAG, "Server socket closed", e)
            }
        }
    }

    // ── Client connect to GO ──────────────────────────────────────────────────

    private fun connectAsClient() {
        scope.launch {
            // Quirk: DHCP after Wi-Fi Direct connection takes time — retry with backoff
            for (delayMs in DeviceQuirks.tcpConnectRetryDelaysMs) {
                delay(delayMs)
                try {
                    val socket = Socket(DeviceQuirks.WIFI_DIRECT_GO_IP, DeviceQuirks.WIFI_DIRECT_GOSSIP_PORT)
                    socket.soTimeout = 30_000
                    RumorLog.d(TAG, "Connected as client to GO")
                    runSession(socket)
                    return@launch
                } catch (e: Exception) {
                    RumorLog.d(TAG, "Client connect attempt failed (${e.message}) — retrying")
                }
            }
            RumorLog.w(TAG, "All client connect attempts exhausted")
        }
    }

    // ── Session runner ────────────────────────────────────────────────────────

    private suspend fun runSession(socket: Socket) {
        val cfg = config ?: return
        val session = GossipSession(
            socket          = socket,
            localUserId     = cfg.localUserId,
            localPublicKey  = cfg.localPublicKey,
            knownMessageIds = cfg.knownIdsProvider(),
            messagesToOffer = cfg.messageProvider(),
            recentOnlineUsers = cfg.onlineUsersProvider(),
        )
        val result = session.run() ?: return

        // Map from transport-internal SessionResult to the protocol-layer boundary type
        _exchangeResults.emit(
            PeerExchangeResult(
                peerUserId      = result.peerUserId,
                peerPublicKey   = result.peerPublicKey,
                messagesReceived = result.messagesReceived,
                messagesSent    = result.messagesSent,
                peerOnlineUsers = result.peerOnlineUsers,
                durationMs      = result.durationMs,
                source          = ExchangeSource.WIFI_DIRECT,
            )
        )

        // Disconnect after exchange — Rumor does not hold persistent connections
        enqueueCommand {
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { RumorLog.d(TAG, "Group removed after session") }
                override fun onFailure(r: Int) {}
            })
        }
    }

    // ── Wi-Fi Direct event handlers ───────────────────────────────────────────

    private fun onP2pEnabled() = removeGroupThenDiscover()

    private fun onConnected() {
        wifiP2pManager?.requestConnectionInfo(channel) { info ->
            val isGo = info?.isGroupOwner == true
            _isGroupOwner.value = isGo
            RumorLog.i(TAG, "Connected — Group Owner: $isGo")
            if (isGo) {
                startServerSocket()
            } else {
                // Quirk: Samsung/MediaTek may ignore our GO intent and become GO anyway.
                // Run both a server socket and a client connect attempt; whichever
                // TCP connection succeeds first wins.
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
        RumorLog.d(TAG, "Disconnected")
    }

    private fun requestPeers() {
        if (!hasLocationPermission()) return
        wifiP2pManager?.requestPeers(channel) { peers ->
            _peerCount.value = peers.deviceList.size
            RumorLog.d(TAG, "${peers.deviceList.size} Wi-Fi Direct peer(s) visible")
            peers.deviceList.forEach { connectToPeer(it) }
        }
    }

    private fun removeGroupThenDiscover() {
        enqueueCommand {
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess()        = discoverPeers()
                override fun onFailure(r: Int)  = discoverPeers()  // no group — proceed anyway
            })
        }
    }

    private fun discoverPeers() {
        if (!hasLocationPermission()) return
        enqueueCommand {
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { RumorLog.d(TAG, "Peer discovery started") }
                override fun onFailure(r: Int) {
                    RumorLog.w(TAG, "Peer discovery failed: ${p2pError(r)}")
                    if (r == WifiP2pManager.BUSY) handler.postDelayed({ discoverPeers() }, 2_000)
                }
            })
        }
    }

    private fun removeGroup() {
        enqueueCommand {
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(r: Int) {}
            })
        }
    }

    /**
     * Serialises WifiP2pManager calls to avoid BUSY errors.
     * Releases the permit 300ms after the call returns to give the framework
     * time to settle before the next command.
     */
    private fun enqueueCommand(block: () -> Unit) {
        if (commandQueue.tryAcquire()) {
            try { block() }
            finally { handler.postDelayed({ commandQueue.release() }, 300) }
        } else {
            RumorLog.d(TAG, "Command skipped — queue busy")
        }
    }

    private fun hasLocationPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun p2pError(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY            -> "BUSY"
        WifiP2pManager.ERROR           -> "ERROR"
        else                           -> "reason=$reason"
    }
}
