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
import com.rumor.mesh.core.routing.BackboneRealizer
import com.rumor.mesh.core.routing.ChannelSelector
import com.rumor.mesh.core.routing.GroupCredentials
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
    /** What Android 10+ reports as our own P2P MAC unless the app is privileged. */
    private val ANONYMIZED_MAC = "02:00:00:00:00:00"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var broadcastReceiver: WifiDirectBroadcastReceiver? = null

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /**
     * Without this, Android's Wi-Fi power-saving can throttle or sleep the radio
     * mid-exchange — the P2P group and TCP socket stay nominally connected but data
     * silently stops flowing a few seconds in. High-perf mode keeps the radio awake
     * for the lifetime of the mesh session.
     */
    private val wifiLock: WifiManager.WifiLock? =
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Rumor:wifiDirectGossip")

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
        val messageProvider: suspend (peerUserId: String) -> List<RumorMessage>,
        /** Deeper O92: fetch by id from the durable store for exact-diff requests. */
        val messagesByIds: (suspend (List<String>) -> List<RumorMessage>)? = null,
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
        /**
         * True while a Rumor peer is BLE-visible. Gates the persistent-link round
         * loop: when the peer leaves radio range the link tears down so the radio
         * is free for new discoveries.
         */
        val isPeerNearby: () -> Boolean = { true },
        /**
         * O42: lazy whole-store `(sentAtMs, id)` snapshot for RBSR. Wiring this
         * is the single go-live switch — the transport advertises `rbsr-v1`
         * iff this is non-null, so capability and ability can't diverge.
         */
        val rbsrItemsProvider: (suspend () -> List<com.rumor.mesh.core.sync.RbsrItem>)? = null,
    )

    private var config: TransportConfig? = null

    /** Completed exchanges — consumed by MeshService and forwarded to GossipEngine. */
    private val _exchangeResults = MutableSharedFlow<PeerExchangeResult>(extraBufferCapacity = 64)
    val exchangeResults: SharedFlow<PeerExchangeResult> = _exchangeResults

    private val _isGroupOwner = MutableStateFlow(false)
    val isGroupOwner: StateFlow<Boolean> = _isGroupOwner.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Serialises WifiP2pManager calls — BUSY errors occur if commands overlap.
     * A real FIFO rather than a try-acquire: a dropped command has no caller-side
     * recovery, and the "peer visible → connect" command lands ~150ms after
     * discovery's command, inside the previous 300ms settle window. With the old
     * semaphore that connect was silently discarded and the transport wedged.
     */
    private val pendingCommands = ArrayDeque<() -> Unit>()
    private var commandRunning = false

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
     *
     * A failed GO negotiation often produces NO connection-changed broadcast
     * (disconnected → still disconnected), so onConnected/onDisconnected alone
     * cannot be trusted to clear this — the watchdog is the backstop that keeps
     * one failed negotiation from wedging connects for the rest of the session.
     */
    private var connectAttemptInFlight = false
    private var connectRetriesRemaining = 0
    private val MAX_CONNECT_RETRIES = 5
    private val CONNECT_WATCHDOG_MS = 45_000L
    private val connectWatchdog = Runnable {
        RumorLog.d(TAG, "Connect watchdog expired — clearing in-flight flag")
        // A credential join that never produced a connection: cool the SSID
        // down so it can't pin the radio away from the legacy flow. Blind
        // joins are exempt — "group not up yet" is their expected failure,
        // and a cooldown here would lock the client out for 3 min right when
        // the host finally arrives (their pacing is the role-retry structure).
        pendingJoinSsid?.let {
            if (!pendingJoinBlind) {
                ssidCooldownUntil[it] = System.currentTimeMillis() + SSID_COOLDOWN_MS
            }
            pendingJoinSsid = null
        }
        connectAttemptInFlight = false
    }

    /**
     * Persistent link (O2): once a group forms, it is held open and the client
     * re-runs a gossip session every [LINK_ROUND_INTERVAL_MS] over a fresh TCP
     * connect — skipping discovery, GO negotiation, and DHCP, which together cost
     * ~60s per round when the group is torn down in between. Teardown happens when
     * the peer stops being BLE-visible, rounds fail repeatedly, or (GO side, which
     * has no round timer of its own) no inbound session completes for
     * [GO_IDLE_TIMEOUT_MS].
     */
    private val LINK_ROUND_INTERVAL_MS = 10_000L
    private val GO_IDLE_TIMEOUT_MS = 35_000L
    private val MAX_ROUND_FAILURES = 3
    @Volatile private var groupConnected = false
    private var goIdleWatchdog: Job? = null

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

    /**
     * O98 Phase 3b — backbone realization state. While a non-None role is being
     * realized (or is healthy), the legacy discover→negotiate flow stands down:
     * autonomous createGroup and credential joins own the radio. Realization
     * failure is bounded ([backboneRetriesLeft]); when retries exhaust, the
     * legacy flow resumes so a device that can't realize its role (e.g. an OEM
     * that still WPS-prompts) degrades to the field-verified negotiated path
     * instead of stranding.
     */
    @Volatile private var backboneRole: BackboneRealizer.Role = BackboneRealizer.Role.None
    /** True once OUR credentialed createGroup succeeded (role-driven or legacy-GO conversion). */
    @Volatile private var backboneGroupCreated = false
    @Volatile private var backboneAttemptAtMs = 0L
    @Volatile private var backboneRetriesLeft = 0
    /** SSID of an in-flight credential join — cooled down if the watchdog fires. */
    @Volatile private var pendingJoinSsid: String? = null
    @Volatile private var pendingJoinBlind = false
    private val ssidCooldownUntil = ConcurrentHashMap<String, Long>()
    /** Converts a negotiated legacy GO group to the credentialed backbone group. */
    private var legacyGoConversionJob: Job? = null
    /** When the current non-None role was assigned — see the client holdoff in [connectToPeer]. */
    @Volatile private var roleAssignedAtMs = 0L
    /** Last time any backbone SSID was seen on air — see backbone memory in [connectToPeer]. */
    @Volatile private var backboneSeenAtMs = 0L
    private val BACKBONE_ATTEMPT_GRACE_MS = 45_000L
    private val MAX_BACKBONE_RETRIES = 3
    private val SSID_COOLDOWN_MS = 180_000L
    private val LEGACY_GO_CONVERT_DELAY_MS = 15_000L
    private val CREDENTIALED_GO_IDLE_TIMEOUT_MS = 120_000L
    private val BACKBONE_MEMORY_MS = 90_000L

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
        backboneRole = BackboneRealizer.Role.None
        backboneGroupCreated = false
        scope.cancel()
        RumorLog.i(TAG, "Transport stopped")
    }

    // ── O98 Phase 3b: backbone realization ────────────────────────────────────

    /**
     * Apply the coordinator's radio role. Called on every backbone recompute
     * tick; idempotent while the current role is healthy or an attempt is inside
     * its grace window. Requires API 29+ ([WifiP2pConfig.Builder]) — older
     * devices keep the legacy negotiated flow unconditionally.
     *
     * A Host role's client set is NOT part of the healthy check: the group is
     * derived from our own userId, so clients joining or leaving never requires
     * recreating it.
     */
    fun applyBackboneRole(role: BackboneRealizer.Role) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (config == null || !hasLocationPermission()) return
        val now = System.currentTimeMillis()
        if (sameRealization(role, backboneRole)) {
            // Client joins self-gate (connected → verify right group; attempt
            // in flight → skip; group not on air → wait) and are naturally
            // rate-limited by the connect watchdog — the Host retry/grace
            // budget below doesn't apply to them.
            if (role is BackboneRealizer.Role.Client) {
                joinBackboneGroup(role.hostUserId)
                return
            }
            when {
                role is BackboneRealizer.Role.None -> return
                groupConnected && backboneGroupCreated -> return
                now - backboneAttemptAtMs < BACKBONE_ATTEMPT_GRACE_MS -> return
                backboneRetriesLeft <= 0 -> return   // exhausted — legacy flow has the radio
            }
            backboneRetriesLeft--
        } else {
            RumorLog.i(TAG, "O98 backbone role → ${roleLabel(role)} (was ${roleLabel(backboneRole)})")
            val hostedGroup = backboneGroupCreated
            backboneRole = role
            roleAssignedAtMs = now
            backboneGroupCreated = false
            backboneRetriesLeft = MAX_BACKBONE_RETRIES
            if (role is BackboneRealizer.Role.None) {
                // The idle watchdog holds a backbone host's group, so a plan
                // that no longer wants us hosting must tear it down here.
                if (hostedGroup) removeGroup()
                return
            }
        }
        backboneAttemptAtMs = now
        when (role) {
            is BackboneRealizer.Role.Host -> hostBackboneGroup()
            is BackboneRealizer.Role.Client -> joinBackboneGroup(role.hostUserId)
            BackboneRealizer.Role.None -> {}
        }
    }

    /** Same radio outcome? Host client-set churn and None==None are not changes. */
    private fun sameRealization(a: BackboneRealizer.Role, b: BackboneRealizer.Role): Boolean = when {
        a is BackboneRealizer.Role.Host && b is BackboneRealizer.Role.Host -> true
        a is BackboneRealizer.Role.Client && b is BackboneRealizer.Role.Client -> a.hostUserId == b.hostUserId
        else -> a == b
    }

    private fun roleLabel(r: BackboneRealizer.Role): String = when (r) {
        is BackboneRealizer.Role.Host -> "HOST(${r.clients.size} clients)"
        is BackboneRealizer.Role.Client -> "CLIENT(of ${r.hostUserId.take(8)}…)"
        BackboneRealizer.Role.None -> "NONE"
    }

    /**
     * While a role is being realized (or is healthy), the legacy
     * discover→negotiate flow stands down. After bounded retries fail, the
     * legacy flow gets the radio back — degraded but connected beats stranded.
     *
     * A Client role only claims the radio while its join is actually in
     * flight: until the host's group is on the air there is nothing to join,
     * and suppressing legacy then would starve the very gossip that carries
     * beacons to the (possibly still view-blind) host.
     */
    private fun backboneOwnsRadio(): Boolean {
        val role = backboneRole
        if (role is BackboneRealizer.Role.None) return false
        if (groupConnected) return true
        val withinGrace = System.currentTimeMillis() - backboneAttemptAtMs < BACKBONE_ATTEMPT_GRACE_MS
        return when (role) {
            is BackboneRealizer.Role.Host -> backboneRetriesLeft > 0 || withinGrace
            // Once the host's SSID is on the air the credential join owns the
            // radio: a legacy negotiated connect() racing it lands as a WPS
            // invitation the GO's owner must manually accept (field-observed —
            // two prompts on the Moto), which is exactly what credentials make
            // unnecessary.
            is BackboneRealizer.Role.Client ->
                (connectAttemptInFlight && withinGrace) ||
                    ssidVisible(GroupCredentials.forHost(role.hostUserId).networkName)
            BackboneRealizer.Role.None -> false
        }
    }

    /**
     * A P2P GO's network name is an ordinary SSID — it shows up in Wi-Fi scan
     * results once the group is actually up. This is the "is there anything to
     * join yet" signal that keeps clients from acting on a plan whose host
     * hasn't realized its side.
     */
    private fun ssidVisible(networkName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val visible = wifiManager?.scanResults?.any { it.SSID == networkName } == true
        if (visible) backboneSeenAtMs = System.currentTimeMillis()
        visible
    }.getOrDefault(false)

    /**
     * Bring up the autonomous group on a scanned-quiet channel. Autonomous
     * createGroup is the ONLY path Android honors the operating frequency on —
     * negotiated connect() ignores the hint on every phone we've tested (see
     * the O99 tombstone). Any existing group is removed first: a negotiated
     * group has a random SSID our backbone clients can never find.
     */
    private fun hostBackboneGroup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cfg = config ?: return
        val creds = GroupCredentials.forHost(cfg.localUserId)
        // Defer to a senior group already on the air: clients join the
        // lexicographically-first visible SSID, so a junior competing group
        // never attracts anyone (field-observed: a skewed-view node hosted a
        // second star that just split the mesh until decay caught up). If the
        // senior group dies, backbone memory ages out and hosting proceeds.
        val senior = seniorBackboneSsid(creds.networkName)
        if (senior != null) {
            // Don't just stand down — join the group we deferred to. A
            // deferring host with no way in would sit isolated until view
            // decay re-roles it (≤6 min of dead air for the planned hub).
            RumorLog.i(TAG, "O98 deferring host — joining senior backbone group $senior")
            joinBySsid(senior)
            return
        }
        val freq = quietFrequencyMhz()
        val p2pCfg = WifiP2pConfig.Builder()
            .setNetworkName(creds.networkName)
            .setPassphrase(creds.passphrase)
            .setGroupOperatingFrequency(freq)
            .build()
        enqueueCommand {
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = createBackboneGroup(p2pCfg, creds.networkName, freq)
                override fun onFailure(r: Int) = createBackboneGroup(p2pCfg, creds.networkName, freq)
            })
        }
    }

    private fun createBackboneGroup(p2pCfg: WifiP2pConfig, networkName: String, freq: Int) {
        val ch = channel ?: return
        wifiP2pManager?.createGroup(ch, p2pCfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                backboneGroupCreated = true
                RumorLog.i(TAG, "O98 hosting backbone group $networkName @ ${freq}MHz")
            }
            override fun onFailure(reason: Int) {
                RumorLog.w(TAG, "O98 backbone createGroup failed: ${p2pError(reason)}")
            }
        })
    }

    /**
     * Join the host's credentialed group — both sides derive the same
     * networkName+passphrase from the host's userId, so the join needs no WPS
     * prompt and no discovery. The group may not exist yet (host still coming
     * up); failures fall back to the applyBackboneRole retry cycle.
     */
    /**
     * Connected-as-something already — but to the backbone host's group? A
     * legacy negotiated group must be left (its random SSID is unjoinable by
     * the other planned clients and it pins this radio); the next recompute
     * tick then performs the actual credential join.
     */
    private fun verifyClientGroup(hostUserId: String) {
        val expected = GroupCredentials.forHost(hostUserId).networkName
        wifiP2pManager?.requestGroupInfo(channel) { group ->
            // Leave a wrong group ONLY once the backbone group is actually on
            // the air. Tearing down a live legacy group for a target that
            // doesn't exist yet destroys the very links that carry beacons to
            // the (possibly still view-blind) host — field-observed round 2:
            // clients shredded working groups chasing a group nobody hosted.
            if (group != null && group.networkName != expected && ssidVisible(expected)) {
                RumorLog.i(
                    TAG,
                    "O98 leaving ${group.networkName} — backbone group $expected is on the air",
                )
                removeGroup()
            }
        }
    }

    private fun joinBackboneGroup(hostUserId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (groupConnected) { verifyClientGroup(hostUserId); return }
        val name = GroupCredentials.forHost(hostUserId).networkName
        if (!ssidVisible(name)) {
            // G38 scan-throttle fix: don't wait for the SSID to show up in
            // scan results (foreground scans are throttled to 4/2min — a node
            // can stay blind to a live group for minutes). The credentials
            // derive from the host's userId, so attempt the join blind; if the
            // group isn't up yet the attempt fails without cooling the SSID
            // and the role-retry structure paces the next one. The scan nudge
            // stays only to keep seniority/deferral ground truth fresh.
            RumorLog.d(TAG, "O98 backbone group $name not in scan results — joining blind")
            runCatching { @Suppress("DEPRECATION") wifiManager?.startScan() }
            joinBySsid(name, blind = true)
            return
        }
        joinBySsid(name)
    }

    /**
     * Credential-join a backbone group by its SSID alone — the passphrase is
     * derivable from the name (see [GroupCredentials.passphraseFor]), so this
     * serves both a planned Client role and the bootstrap case of a fresh node
     * that merely sees Rumor infrastructure on the air. Failed SSIDs (foreign
     * networks matching the pattern, stale scans) go on cooldown so they can't
     * pin the radio away from the legacy flow.
     */
    private fun joinBySsid(networkName: String, blind: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (groupConnected || connectAttemptInFlight) return
        val p2pCfg = WifiP2pConfig.Builder()
            .setNetworkName(networkName)
            .setPassphrase(GroupCredentials.passphraseFor(networkName))
            .build()
        connectAttemptInFlight = true
        pendingJoinSsid = networkName
        pendingJoinBlind = blind
        backboneAttemptAtMs = System.currentTimeMillis()
        handler.removeCallbacks(connectWatchdog)
        handler.postDelayed(connectWatchdog, CONNECT_WATCHDOG_MS)
        enqueueCommand {
            wifiP2pManager?.connect(channel, p2pCfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    RumorLog.i(TAG, "O98 joining backbone group $networkName${if (blind) " (blind)" else ""}")
                }
                override fun onFailure(reason: Int) {
                    RumorLog.w(TAG, "O98 backbone join failed: ${p2pError(reason)}")
                    // BUSY means the framework was mid-command, not that the
                    // group is absent — retry without penalizing the SSID.
                    // Blind joins never cool: "not up yet" is their expected
                    // failure and the role-retry structure paces them.
                    if (reason != WifiP2pManager.BUSY && !blind) {
                        ssidCooldownUntil[networkName] = System.currentTimeMillis() + SSID_COOLDOWN_MS
                    }
                    pendingJoinSsid = null
                    handler.removeCallbacks(connectWatchdog)
                    connectAttemptInFlight = false
                }
            })
        }
    }

    /** A visible backbone SSID sorting before [ownNetworkName], if any. */
    private fun seniorBackboneSsid(ownNetworkName: String): String? = runCatching {
        @Suppress("DEPRECATION")
        (wifiManager?.scanResults ?: emptyList())
            .mapNotNull { it.SSID }
            .filter { GroupCredentials.BACKBONE_SSID_REGEX.matches(it) && it < ownNetworkName }
            .minOrNull()
    }.getOrNull()

    /** Rumor-scheme SSIDs currently on the air, excluding our own and cooled-down ones. */
    private fun visibleBackboneSsids(): List<String> {
        val own = config?.let { GroupCredentials.forHost(it.localUserId).networkName }
        val now = System.currentTimeMillis()
        return runCatching {
            @Suppress("DEPRECATION")
            val all = (wifiManager?.scanResults ?: emptyList())
                .mapNotNull { it.SSID }
                .filter { GroupCredentials.BACKBONE_SSID_REGEX.matches(it) }
            if (all.isNotEmpty()) backboneSeenAtMs = now
            all.filter { it != own && (ssidCooldownUntil[it] ?: 0L) <= now }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
    }

    /** Least-congested non-DFS 5 GHz candidate from the latest AP scan. */
    private fun quietFrequencyMhz(): Int {
        val freqs = runCatching {
            @Suppress("DEPRECATION")
            wifiManager?.scanResults?.map { it.frequency } ?: emptyList()
        }.getOrDefault(emptyList())
        return ChannelSelector.quietestFrequency(freqs)
    }

    // ── Connection management ─────────────────────────────────────────────────

    private fun connectToPeer(device: WifiP2pDevice) {
        if (!hasLocationPermission()) return
        val cfg = config ?: return

        // A group is already up and the persistent-link loop owns it. Issuing
        // connect() to a peer while grouped disrupts the live link (observed on
        // OnePlus: repeated "Connect initiated" while connected).
        if (groupConnected) return

        // O98 3b: while a backbone role is being realized, autonomous
        // createGroup / credential joins own the radio — a negotiated connect
        // here would disrupt group formation exactly like the grouped case above.
        if (backboneOwnsRadio()) return

        // O98 3b: joinable Rumor infrastructure on the air → credential-join it,
        // never dial. A Host-role node joins only a SENIOR SSID (the group it
        // would defer to anyway); junior SSIDs it out-ranks are its own
        // clients-to-be and will collapse toward it.
        run {
            val own = GroupCredentials.forHost(cfg.localUserId).networkName
            val ssid = visibleBackboneSsids().firstOrNull {
                backboneRole !is BackboneRealizer.Role.Host || it < own
            }
            if (ssid != null) {
                joinBySsid(ssid)
                return
            }
        }

        // A Client-role node NEVER legacy-initiates: its host's group exists or
        // is coming (scan latency, hub flap); dialing peers meanwhile is what
        // produced the field-observed churn of junk pairings — O(N) of them
        // per hub flap at mesh scale. If the host is truly gone, the view's
        // recency decay clears the role (≤6 min) and legacy returns.
        if (backboneRole is BackboneRealizer.Role.Client) {
            runCatching { @Suppress("DEPRECATION") wifiManager?.startScan() }
            return
        }

        // Backbone memory: a backbone SSID seen on air moments ago means the
        // backbone is flapping, not absent — don't fill the gap with junk
        // pairings a returning hub will orphan anyway. A genuinely dead
        // backbone ages out and bootstrap pairing resumes.
        if (System.currentTimeMillis() - backboneSeenAtMs < BACKBONE_MEMORY_MS) {
            runCatching { @Suppress("DEPRECATION") wifiManager?.startScan() }
            return
        }

        // Bootstrap-by-hosting (API 29+): negotiated pairing is RETIRED on
        // devices that can do credential joins. A discovered peer with no
        // backbone SSID on the air means the mesh needs its first group — so
        // HOST it instead of dialing anyone. If both sides do this, the junior
        // (higher SSID) sees the senior on its next scan, yields at its first
        // idle check, and joins. No dialing → no GO-negotiation collisions, no
        // passive-GO invitations, no WPS prompt class at all (user directive
        // 2026-07-17: the legacy churn is unacceptable at mesh scale).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val now = System.currentTimeMillis()
            if (!backboneGroupCreated && !connectAttemptInFlight &&
                now - backboneAttemptAtMs > LEGACY_GO_CONVERT_DELAY_MS
            ) {
                backboneAttemptAtMs = now
                RumorLog.i(TAG, "O98 bootstrap: peer visible, no backbone on air — hosting")
                hostBackboneGroup()
            }
            return
        }

        // Two non-dual-role peers that both auto-connect to each other collide in
        // GO negotiation (both request GO with equal intent) and neither ever
        // reaches a stable group. Break the symmetry deterministically: only the
        // lower-addressed device initiates; the other waits to receive the
        // connection passively. This is a connection-role tiebreak only — never
        // an identity signal (device.deviceAddress is never trusted as identity;
        // see the architecture invariant on MAC addresses).
        //
        // Android 10+ anonymises our own address in THIS_DEVICE_CHANGED to
        // 02:00:00:00:00:00, which compares below any real peer MAC — both sides
        // would conclude they're the initiator and collide anyway. When the OS
        // won't give us a real address, skip the tiebreak and rely on randomised
        // GO intent in issueConnect() to break the symmetry instead.
        val usableLocalAddress = localDeviceAddress?.takeUnless { it == ANONYMIZED_MAC }
        if (!DeviceQuirks.wifiDirectDualRoleRequired && usableLocalAddress != null) {
            if (usableLocalAddress >= device.deviceAddress) {
                RumorLog.d(TAG, "Yielding initiator role to ${device.deviceAddress} — waiting to be connected")
                return
            }
        }

        if (connectAttemptInFlight) return
        connectAttemptInFlight = true
        connectRetriesRemaining = MAX_CONNECT_RETRIES
        handler.removeCallbacks(connectWatchdog)
        handler.postDelayed(connectWatchdog, CONNECT_WATCHDOG_MS)
        issueConnect(device)
    }

    /** Actually issues the connect() call. Retries on BUSY without re-checking the tiebreak. */
    private fun issueConnect(device: WifiP2pDevice) {
        val cfg = config ?: run { connectAttemptInFlight = false; return }

        // Identity is only known after HELLO. Cooldown is enforced post-handshake
        // in runSession() — we cannot trust device.deviceAddress as a userId proxy.
        val p2pCfg = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Samsung/MediaTek ignore this and always demand GO — dual-role
            // (server + client) below handles that case transparently.
            groupOwnerIntent = when {
                DeviceQuirks.wifiDirectDualRoleRequired -> 0
                localDeviceAddress?.takeUnless { it == ANONYMIZED_MAC } != null -> 7
                // No usable address tiebreak (API 29+ anonymised MAC): both sides
                // initiate, so equal intents would deadlock GO negotiation. Random
                // intent resolves it; a 1-in-15 tie just retries next round.
                // 15 excluded — it means "must be GO" and recreates the deadlock.
                else -> kotlin.random.Random.nextInt(0, 15)
            }
        }
        enqueueCommand {
            wifiP2pManager?.connect(channel, p2pCfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    RumorLog.d(TAG, "Connect initiated: ${device.deviceAddress}")
                }
                override fun onFailure(reason: Int) {
                    RumorLog.w(TAG, "Connect failed: ${p2pError(reason)}")
                    // BUSY retries must be bounded and must die with the in-flight
                    // flag: two peers with unbounded 1s retry chains keep the P2P
                    // framework permanently BUSY for each other (livelock observed
                    // after a reinstall killed the apps mid-link). When retries
                    // exhaust, the framework is likely wedged on stale group state —
                    // reset it instead of hammering connect.
                    if (reason == WifiP2pManager.BUSY &&
                        connectAttemptInFlight && connectRetriesRemaining-- > 0) {
                        handler.postDelayed(
                            { if (connectAttemptInFlight) issueConnect(device) }, 2_000)
                    } else {
                        handler.removeCallbacks(connectWatchdog)
                        connectAttemptInFlight = false
                        if (reason == WifiP2pManager.BUSY) removeGroupThenDiscover()
                    }
                }
            })
        }
    }

    // ── Server socket (Group Owner role) ──────────────────────────────────────

    private fun startServerSocket() {
        // CONNECTION_CHANGED re-fires on every group-membership change (a third
        // device joining). Rebinding here kills the live accept loop for the
        // clients already connected — keep the existing socket if it's healthy.
        if (serverJob?.isActive == true && serverSocket?.isClosed == false) return
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

    private var clientLoopJob: Job? = null

    private fun connectAsClient(groupOwnerAddress: String = DeviceQuirks.WIFI_DIRECT_GO_IP) {
        // Same membership-change re-broadcast as on the GO side: don't stack a
        // second round loop on top of a live one.
        if (clientLoopJob?.isActive == true) return
        clientLoopJob = scope.launch {
            // Quirk: Wi-Fi Direct can report CONNECTED before the data path is actually
            // usable — the TCP handshake completes but the GossipSession's HELLO phase
            // gets no reply (see HELLO_TIMEOUT_MS). That's a real, fast signal that this
            // attempt's data path is dead, so retry a fresh connection rather than
            // waiting out the full session budget on a socket that will never work.
            var linked = false
            for (delayMs in DeviceQuirks.tcpConnectRetryDelaysMs) {
                delay(delayMs)
                if (runRound(groupOwnerAddress)) { linked = true; break }
            }
            if (!linked) {
                RumorLog.w(TAG, "All client connect attempts exhausted")
                removeGroup()
                return@launch
            }

            // Persistent link (O2): the group is up and proven. Re-gossip over a
            // fresh TCP connect every round — no discovery / GO negotiation / DHCP.
            var failures = 0
            while (isActive && groupConnected && failures < MAX_ROUND_FAILURES) {
                delay(LINK_ROUND_INTERVAL_MS)
                if (config?.isPeerNearby?.invoke() == false) {
                    RumorLog.d(TAG, "Peer no longer nearby — releasing persistent link")
                    break
                }
                if (!groupConnected) break
                failures = if (runRound(groupOwnerAddress)) 0 else failures + 1
            }
            RumorLog.d(TAG, "Persistent link ended (failures=$failures) — removing group")
            removeGroup()
        }
    }

    /** One gossip round over a fresh TCP connect to the GO. True on completed exchange. */
    private suspend fun runRound(groupOwnerAddress: String): Boolean {
        return try {
            val socket = Socket(groupOwnerAddress, DeviceQuirks.WIFI_DIRECT_GOSSIP_PORT)
            socket.tcpNoDelay = true
            RumorLog.d(TAG, "Connected as client to GO at $groupOwnerAddress")
            runSession(socket, isInbound = false)
        } catch (e: Exception) {
            RumorLog.d(TAG, "Client connect attempt failed (${e.message})")
            false
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
            messagesByIds   = cfg.messagesByIds,
            recentOnlineUsers = cfg.onlineUsersProvider(),
            isInbound       = isInbound,
            sessionGate     = { peerUserId, inbound ->
                val winner = claimSession(cfg.localUserId, peerUserId, inbound)
                if (winner) claimedPeer = peerUserId
                winner
            },
            rbsrItemsProvider = cfg.rbsrItemsProvider,
            // Advertise rbsr-v1 iff we can actually run it — enforced by construction.
            supportedFeatures = if (cfg.rbsrItemsProvider != null)
                listOf(GossipSession.RBSR_FEATURE) else GossipSession.LOCAL_SUPPORTED_FEATURES,
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
                peerSupportedFeatures = result.peerSupportedFeatures,
            )
        )

        // Persistent link (O2): the group stays up for everyone. Teardown is owned
        // by the client round loop (nearby-drop / repeated failures) and the GO
        // idle watchdog — not by session completion.
        if (cfg.isPriorityPeer(result.peerUserId)) {
            activePriorityPeers.add(result.peerUserId)
        }
        // Only the actual GO runs the idle watchdog — dual-role devices accept
        // inbound sessions as client too, and their teardown is owned by the
        // outbound round loop.
        if (isInbound && _isGroupOwner.value) armGoIdleWatchdog()
        return true
    }

    /**
     * The GO has no round timer of its own — the client drives the cadence. If no
     * inbound session completes for [GO_IDLE_TIMEOUT_MS] (~3 missed rounds), the
     * client is gone: release the group so the radio is free for new discoveries.
     */
    private fun armGoIdleWatchdog() {
        goIdleWatchdog?.cancel()
        goIdleWatchdog = scope.launch {
            delay(GO_IDLE_TIMEOUT_MS)
            // Junior yield: an idle (≈ clientless) credentialed group whose
            // SSID is outranked by another backbone group on the air will never
            // attract clients — they all join the sorted-first SSID. Yield now
            // and join the senior instead of splitting the mesh for 120s.
            val own = config?.let { GroupCredentials.forHost(it.localUserId).networkName }
            val seniorOnAir = own != null && seniorBackboneSsid(own) != null
            // O98 3b: a backbone host's group exists BECAUSE the plan says so,
            // not because a client is currently chatty — hold it; the plan's
            // decay is what tears it down. Checked at EXPIRY, not arm time:
            // the groupCreated reaffirm (requestGroupInfo) is async and the
            // flag is often still false when the watchdog is armed — reading
            // it at arm time killed a converted group at 35s despite the grace.
            if (backboneRole is BackboneRealizer.Role.Host && backboneGroupCreated && !seniorOnAir) {
                RumorLog.d(TAG, "GO idle but backbone host — holding group")
                return@launch
            }
            // A credentialed group must outlive its clients' Wi-Fi scan latency
            // (10–60s before the SSID becomes visible to them). Still bounded:
            // a forever-held empty group pins this radio as GO and would
            // deadlock two view-blind converts against each other.
            if (backboneGroupCreated && !seniorOnAir) {
                delay(CREDENTIALED_GO_IDLE_TIMEOUT_MS - GO_IDLE_TIMEOUT_MS)
                if (backboneRole is BackboneRealizer.Role.Host && backboneGroupCreated) {
                    RumorLog.d(TAG, "GO idle but backbone host — holding group")
                    return@launch
                }
            }
            RumorLog.d(TAG, if (seniorOnAir) "GO idle — yielding to senior backbone group" else "GO idle — removing group")
            removeGroup()
        }
    }

    /**
     * One live session per peer, first-come-wins.
     *
     * The old rule ("lower userId keeps outbound, higher keeps inbound") assumed
     * both directions exist. In a GO↔client group only one ever does — the client
     * dials the GO — so whenever the userId comparison favored the direction a
     * role couldn't have, both sides vetoed the only possible session and the pair
     * deadlocked in endless HELLO retries (hit on the first Samsung join; the
     * original two-phone pair only worked because their userIds happened to align
     * with their roles). A true dual-role double-connect can't currently occur
     * (nothing ever dials into a non-GO), so plain duplicate-rejection is enough.
     */
    private fun claimSession(localUserId: String, peerUserId: String, isInbound: Boolean): Boolean =
        activePeerSessions.putIfAbsent(peerUserId, isInbound) == null

    // ── Wi-Fi Direct event handlers ───────────────────────────────────────────

    private fun onP2pEnabled() = removeGroupThenDiscover()

    private fun onConnected() {
        handler.removeCallbacks(connectWatchdog)
        connectAttemptInFlight = false
        pendingJoinSsid = null
        groupConnected = true
        wifiP2pManager?.requestConnectionInfo(channel) { info ->
            val isGo = info?.isGroupOwner == true
            _isGroupOwner.value = isGo
            // O98 3b: the removeGroup→createGroup handoff delivers the OLD
            // group's disconnect broadcast AFTER createGroup's success callback,
            // wiping backboneGroupCreated — then the idle watchdog's hold-guard
            // fails and kills the new group 35s in (field-observed 48s re-host
            // cycle). Reaffirm from ground truth: if the group we now own IS
            // our credentialed SSID, mark it created and refresh the retry
            // budget. Otherwise it's a negotiated legacy group with a random
            // SSID nobody can credential-join (a third phone entering it via
            // plain connect() raises the manual invitation prompt) — convert it
            // to the credentialed group after one round, so the first exchange
            // still carries beacons. The delayed re-check of backboneGroupCreated
            // keeps the conversion from firing on an already-credentialed group.
            if (isGo) {
                val own = config?.let { GroupCredentials.forHost(it.localUserId).networkName }
                wifiP2pManager?.requestGroupInfo(channel) { group ->
                    if (group != null && group.networkName == own) {
                        backboneGroupCreated = true
                        backboneRetriesLeft = MAX_BACKBONE_RETRIES
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    legacyGoConversionJob?.cancel()
                    legacyGoConversionJob = scope.launch {
                        delay(LEGACY_GO_CONVERT_DELAY_MS)
                        // A planned Client must not spin up a competing group —
                        // the verify/join flow migrates it out of the legacy GO
                        // seat once its host's SSID is on the air.
                        if (groupConnected && _isGroupOwner.value && !backboneGroupCreated &&
                            backboneRole !is BackboneRealizer.Role.Client
                        ) {
                            RumorLog.i(TAG, "O98 converting legacy GO group to credentialed backbone group")
                            hostBackboneGroup()
                        }
                    }
                }
            }
            if (isGo) armGoIdleWatchdog()
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
        handler.removeCallbacks(connectWatchdog)
        connectAttemptInFlight = false
        groupConnected = false
        // O98 3b: role persists (next recompute tick retries) but the group is
        // gone. A disconnect is a new situation — refresh the retry budget so a
        // role that exhausted its attempts against a stale group state gets to
        // try again rather than staying parked on the legacy flow forever.
        backboneGroupCreated = false
        legacyGoConversionJob?.cancel()
        if (backboneRole !is BackboneRealizer.Role.None) backboneRetriesLeft = MAX_BACKBONE_RETRIES
        goIdleWatchdog?.cancel()
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
     * Serialises WifiP2pManager calls to avoid BUSY errors. Commands run in FIFO
     * order with a 300ms settle gap after each so the framework isn't hammered.
     */
    private fun enqueueCommand(block: () -> Unit) {
        handler.post {
            pendingCommands.addLast(block)
            drainCommandQueue()
        }
    }

    private fun drainCommandQueue() {
        if (commandRunning) return
        val next = pendingCommands.removeFirstOrNull() ?: return
        commandRunning = true
        try { next() }
        finally {
            handler.postDelayed({ commandRunning = false; drainCommandQueue() }, 300)
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
