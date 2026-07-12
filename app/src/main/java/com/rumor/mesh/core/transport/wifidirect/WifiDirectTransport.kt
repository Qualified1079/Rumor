package com.rumor.mesh.core.transport.wifidirect

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

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
class WifiDirectTransport(
    private val context: Context,
) {
    private val TAG = "WifiDirect"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var broadcastReceiver: WifiDirectBroadcastReceiver? = null

    /**
     * Without this, Android's Wi-Fi power-saving can throttle or sleep the radio
     * mid-exchange — the P2P group and TCP socket stay nominally connected but data
     * silently stops flowing a few seconds in. High-perf mode keeps the radio awake
     * for the lifetime of the mesh session.
     */
    private val wifiLock: WifiManager.WifiLock? =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Rumor:wifiDirectGossip")

    /**
     * Configuration provided at [start] time. Immutable for the lifetime of this session.
     * Replace by calling [stop] then [start] again after an identity change.
     */
    data class TransportConfig(
        /** Local User ID (hex SHA-256 of public key). */
        val localUserId: String,
        /** Base64-encoded Ed25519 public key. */
        val localPublicKey: String,
        /** Signs arbitrary bytes with the local Ed25519 private key. Used for HELLO challenge-response. */
        val signer: (ByteArray) -> ByteArray?,
        /** Returns messages to offer to a specific peer (called post-HELLO). */
        val messageProvider: (peerUserId: String) -> List<RumorMessage>,
        /** Returns the set of message IDs this node already knows. */
        val knownIdsProvider: () -> Set<String>,
        /** Returns online-status elapsed-ms map to share with peers. */
        val onlineUsersProvider: () -> Map<String, Long>,
        /**
         * Returns true for peers that have established a mutual priority link.
         * Priority peers skip [removeGroup] after each exchange so the Wi-Fi
         * Direct connection is held open between gossip rounds.
         */
        val isPriorityPeer: suspend (String) -> Boolean = { false },
        /** Invoked with the peer's userId when a session completes with no result. */
        val onExchangeFailed: (String) -> Unit = {},
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

    /**
     * Multiple independent triggers can ask for rediscovery around the same time
     * (BLE nearby-signal, a manual scan tap, repeated taps) — without de-duping,
     * each spawns its own BUSY-retry chain and they collide forever, hammering
     * WifiP2pManager indefinitely. Only one rediscovery attempt runs at a time,
     * bounded to a few retries, then it simply stops until the next trigger.
     */
    private var discoveryInFlight = false
    private var discoveryRetriesRemaining = 0
    private val MAX_DISCOVERY_RETRIES = 3

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    /**
     * Our own P2P device address, learned from WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.
     * Used only as a connection-role tiebreak (see [connectToPeer]) — never as
     * identity, per the architecture invariant that MAC addresses prove nothing.
     */
    private var localDeviceAddress: String? = null

    /**
     * True while we have an outstanding connect() attempt. Two non-dual-role peers
     * that both auto-connect to each other on every PEERS_CHANGED collide in GO
     * negotiation (both requesting GO with equal intent) and neither side ever
     * reaches a stable group — the connection stalls for the full ~40s framework
     * negotiation timeout and repeats forever. Guards against re-triggering connect
     * while one is already in flight.
     */
    private var connectAttemptInFlight = false

    /**
     * Tracks which session won the dual-role tiebreak per peer. Value is the
     * direction kept (true = inbound, false = outbound). Cleared after the
     * exchange completes so the next encounter is fresh.
     */
    private val activePeerSessions = ConcurrentHashMap<String, Boolean>()

    /**
     * Priority-peer userIds we want to re-establish a session with. Populated when
     * a Wi-Fi Direct disconnect drops a previously-connected priority peer; cleared
     * as each comes back via a new successful exchange. Never trusts MAC addresses —
     * identity is only confirmed after HELLO. While non-empty, an aggressive
     * rediscovery loop runs to find the peer again.
     */
    private val priorityReconnectPending = ConcurrentHashMap.newKeySet<String>()
    /** Priority-peer userIds currently in a live session. Snapshotted on disconnect. */
    private val activePriorityPeers = ConcurrentHashMap.newKeySet<String>()
    private var priorityWatcher: Job? = null

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
                override fun onThisDeviceChanged(device: WifiP2pDevice?) {
                    localDeviceAddress = device?.deviceAddress
                }
            }
        )

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(broadcastReceiver, filter)

        runCatching { wifiLock?.acquire() }

        // Quirk: stale groups from previous sessions block new connections
        removeGroupThenDiscover()
        RumorLog.i(TAG, "Transport started for ${cfg.localUserId.take(16)}…")
    }

    fun stop() {
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        runCatching { context.unregisterReceiver(broadcastReceiver) }
        removeGroup()
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
        config = null
        scope.cancel()
        RumorLog.i(TAG, "Transport stopped")
    }

    // ── Connection management ─────────────────────────────────────────────────

    private fun connectToPeer(device: WifiP2pDevice) {
        if (!hasLocationPermission()) return
        val cfg = config ?: return

        // Two non-dual-role peers that both auto-connect to each other collide in
        // GO negotiation (both request GO with equal intent) and neither ever
        // reaches a stable group. Break the symmetry deterministically: only the
        // lower-addressed device initiates; the other waits to receive the
        // connection passively. This is a connection-role tiebreak only — never
        // an identity signal (device.deviceAddress is never trusted as identity;
        // see the architecture invariant on MAC addresses).
        if (!DeviceQuirks.wifiDirectDualRoleRequired) {
            val ourAddress = localDeviceAddress
            if (ourAddress != null && ourAddress >= device.deviceAddress) {
                RumorLog.d(TAG, "Yielding initiator role to ${device.deviceAddress} — waiting to be connected")
                return
            }
        }

        if (connectAttemptInFlight) return
        connectAttemptInFlight = true
        issueConnect(device)
    }

    /** Actually issues the connect() call. Retries on BUSY without re-checking the tiebreak. */
    private fun issueConnect(device: WifiP2pDevice) {
        val cfg = config ?: run { connectAttemptInFlight = false; return }

        // Identity is only known after HELLO. Cooldown is enforced post-handshake
        // in runSession() — we cannot trust device.deviceAddress as a userId proxy.
        val p2pCfg = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Neutral GO intent. Samsung/MediaTek ignore this and always demand GO —
            // dual-role (server + client) below handles that case transparently.
            groupOwnerIntent = if (DeviceQuirks.wifiDirectDualRoleRequired) 0 else 7
        }
        enqueueCommand {
            wifiP2pManager?.connect(channel, p2pCfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    RumorLog.d(TAG, "Connect initiated: ${device.deviceAddress}")
                }
                override fun onFailure(reason: Int) {
                    RumorLog.w(TAG, "Connect failed: ${p2pError(reason)}")
                    if (reason == WifiP2pManager.BUSY) {
                        handler.postDelayed({ issueConnect(device) }, 1_000)
                    } else {
                        connectAttemptInFlight = false
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
                    client.tcpNoDelay = true
                    launch { runSession(client, isInbound = true) }
                }
            } catch (e: Exception) {
                if (isActive) RumorLog.w(TAG, "Server socket closed", e)
            }
        }
    }

    // ── Client connect to GO ──────────────────────────────────────────────────

    private fun connectAsClient(groupOwnerAddress: String = DeviceQuirks.WIFI_DIRECT_GO_IP) {
        scope.launch {
            // Quirk: Wi-Fi Direct can report CONNECTED before the data path is actually
            // usable — the TCP handshake completes but the GossipSession's HELLO phase
            // gets no reply (see HELLO_TIMEOUT_MS). That's a real, fast signal that this
            // attempt's data path is dead, so retry a fresh connection rather than
            // waiting out the full session budget on a socket that will never work.
            for (delayMs in DeviceQuirks.tcpConnectRetryDelaysMs) {
                delay(delayMs)
                try {
                    val socket = Socket(groupOwnerAddress, DeviceQuirks.WIFI_DIRECT_GOSSIP_PORT)
                    socket.tcpNoDelay = true
                    RumorLog.d(TAG, "Connected as client to GO at $groupOwnerAddress")
                    if (runSession(socket, isInbound = false)) return@launch
                    RumorLog.d(TAG, "Session on this connection failed — retrying fresh connect")
                } catch (e: Exception) {
                    RumorLog.d(TAG, "Client connect attempt failed (${e.message}) — retrying")
                }
            }
            RumorLog.w(TAG, "All client connect attempts exhausted")
        }
    }

    // ── Session runner ────────────────────────────────────────────────────────

    /** Returns true if the exchange completed; false if the caller should retry. */
    private suspend fun runSession(socket: Socket, isInbound: Boolean): Boolean {
        val cfg = config ?: return false
        var claimedPeer: String? = null
        val session = GossipSession(
            socket          = socket,
            localUserId     = cfg.localUserId,
            localPublicKey  = cfg.localPublicKey,
            signer          = cfg.signer,
            knownMessageIds = cfg.knownIdsProvider(),
            messagesProvider = cfg.messageProvider,
            recentOnlineUsers = cfg.onlineUsersProvider(),
            isInbound       = isInbound,
            sessionGate     = { peerUserId, inbound ->
                val winner = claimSession(cfg.localUserId, peerUserId, inbound)
                if (winner) claimedPeer = peerUserId
                winner
            },
        )
        val result = try { session.run() }
        finally { claimedPeer?.let { activePeerSessions.remove(it) } }
        if (result == null) {
            claimedPeer?.let { cfg.onExchangeFailed(it) }
            return false
        }

        priorityReconnectPending.remove(result.peerUserId)

        // Map from transport-internal SessionResult to the protocol-layer boundary type
        _exchangeResults.emit(
            PeerExchangeResult(
                peerUserId          = result.peerUserId,
                peerPublicKey       = result.peerPublicKey,
                messagesReceived    = result.messagesReceived,
                messagesSent        = result.messagesSent,
                peerOnlineUsers     = result.peerOnlineUsers,
                ackedByPeer         = result.ackedByPeer,
                durationMs          = result.durationMs,
                source              = ExchangeSource.WIFI_DIRECT,
                bytesTransferred    = result.bytesTransferred,
                peerOverlapFraction = result.peerOverlapFraction,
            )
        )

        // Priority peers hold their connection open between gossip rounds.
        // Non-priority peers disconnect so the radio is free for new discoveries.
        val priority = cfg.isPriorityPeer(result.peerUserId)
        if (!priority) {
            enqueueCommand {
                wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { RumorLog.d(TAG, "Group removed after session") }
                    override fun onFailure(r: Int) {}
                })
            }
        } else {
            activePriorityPeers.add(result.peerUserId)
            RumorLog.d(TAG, "Keeping group alive for priority peer ${result.peerUserId.take(8)}…")
        }
        return true
    }

    /**
     * Dual-role tiebreak. When both server-accept and client-connect succeed for
     * the same peer pair, both sides apply this rule and exactly one direction
     * survives — no central coordination needed.
     *
     * Convention: the side with the lexicographically lower userId keeps its
     * outbound (client) session; the higher keeps inbound. Both sides agree.
     */
    private fun claimSession(localUserId: String, peerUserId: String, isInbound: Boolean): Boolean {
        val keepInbound = localUserId > peerUserId
        if (isInbound != keepInbound) return false
        // Reserve the slot; if a duplicate of the preferred direction races in, drop it.
        return activePeerSessions.putIfAbsent(peerUserId, isInbound) == null
    }

    // ── Wi-Fi Direct event handlers ───────────────────────────────────────────

    private fun onP2pEnabled() = removeGroupThenDiscover()

    private fun onConnected() {
        connectAttemptInFlight = false
        wifiP2pManager?.requestConnectionInfo(channel) { info ->
            val isGo = info?.isGroupOwner == true
            _isGroupOwner.value = isGo
            // Read the actual GO address from WifiP2pInfo. The well-known constant
            // (192.168.49.1) is the typical value but is not guaranteed by spec —
            // vendor builds vary. Fall back to the constant if the API returns null.
            val goAddress = info?.groupOwnerAddress?.hostAddress ?: DeviceQuirks.WIFI_DIRECT_GO_IP
            RumorLog.i(TAG, "Connected — Group Owner: $isGo, GO address: $goAddress")
            if (isGo) {
                startServerSocket()
            } else {
                // Quirk: Samsung/MediaTek may ignore our GO intent and become GO anyway.
                // Run both a server socket and a client connect attempt; whichever
                // TCP connection succeeds first wins.
                if (DeviceQuirks.wifiDirectDualRoleRequired) startServerSocket()
                connectAsClient(goAddress)
            }
        }
    }

    private fun onDisconnected() {
        _isGroupOwner.value = false
        connectAttemptInFlight = false
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        if (activePriorityPeers.isNotEmpty()) {
            priorityReconnectPending.addAll(activePriorityPeers)
            activePriorityPeers.clear()
            startPriorityWatcher()
        }
        RumorLog.d(TAG, "Disconnected")
    }

    /**
     * Aggressive rediscovery loop while priority peers are missing. Issues a
     * Wi-Fi Direct peer discovery with exponential backoff (2s → 30s) until the
     * pending set is empty. Identity is confirmed only via HELLO — discovery
     * just produces connection candidates, none of which are trusted pre-handshake.
     */
    private fun startPriorityWatcher() {
        if (priorityWatcher?.isActive == true) return
        priorityWatcher = scope.launch {
            var backoffMs = 2_000L
            while (priorityReconnectPending.isNotEmpty()) {
                RumorLog.d(TAG, "Priority reconnect: scanning (${priorityReconnectPending.size} pending)")
                discoverPeers()
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
            RumorLog.d(TAG, "Priority reconnect: all peers reattached")
        }
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

    /**
     * Public re-entry point for a manual scan or a BLE-detected nearby signal.
     * Ignored if a rediscovery attempt is already in flight — see [discoveryInFlight].
     */
    fun rediscoverPeers() {
        if (discoveryInFlight) {
            RumorLog.d(TAG, "Rediscovery already in progress — ignoring duplicate trigger")
            return
        }
        discoveryInFlight = true
        discoveryRetriesRemaining = MAX_DISCOVERY_RETRIES
        discoverPeers()
    }

    private fun discoverPeers() {
        if (!hasLocationPermission()) { discoveryInFlight = false; return }
        enqueueCommand {
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    RumorLog.d(TAG, "Peer discovery started")
                    discoveryInFlight = false
                }
                override fun onFailure(r: Int) {
                    RumorLog.w(TAG, "Peer discovery failed: ${p2pError(r)}")
                    if (r == WifiP2pManager.BUSY && discoveryRetriesRemaining > 0) {
                        discoveryRetriesRemaining--
                        handler.postDelayed({ discoverPeers() }, 2_000)
                    } else {
                        discoveryInFlight = false
                    }
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
