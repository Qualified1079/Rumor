package com.rumor.mesh.core.transport.lan

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.ExchangeSource
import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.sync.RbsrItem
import com.rumor.mesh.core.transport.wifidirect.GossipSession
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * O93 — same-LAN transport. When two Rumor nodes are already on the same
 * Wi-Fi network, Wi-Fi Direct is the wrong tool twice over: P2P discovery
 * silently fails while STA-associated, and forming a P2P group disrupts the
 * very AP both nodes are using. This transport sidesteps both: mDNS service
 * discovery over the shared LAN + plain TCP + the exact same [GossipSession]
 * wire state machine the Wi-Fi Direct path runs.
 *
 * Pure JVM (the :node desktop target reuses it as-is, O104/O106); the Android
 * host supplies the Wi-Fi interface address and holds a MulticastLock while
 * this runs. Lifecycle: [start] when a usable LAN appears, [stop] when it
 * drops — both idempotent.
 *
 * Identity: the advertised service name is a 16-hex userId prefix used only
 * for self-filtering and connection targeting — never as identity (the
 * MAC-is-never-identity rule applies to mDNS names verbatim). Identity is
 * established exclusively by the HELLO Ed25519 challenge inside the session.
 * Advertising presence on the LAN is the same local-observer leak class as
 * BLE/Wi-Fi adjacency — documented, accepted (O51).
 *
 * Cross-transport overlap: while STA-associated the P2P path is dead (the
 * reason this transport exists), so simultaneous LAN+P2P sessions with the
 * same peer are rare; when they do race, message-level dedup makes the second
 * exchange a no-op (overlap=1.0), same as any duplicate session.
 */
class LanTransport(
    private val config: Config,
) {
    data class Config(
        val localUserId: String,
        val localPublicKey: String,
        val signer: (ByteArray) -> ByteArray?,
        val messageProvider: suspend (peerUserId: String) -> List<RumorMessage>,
        val messagesByIds: (suspend (List<String>) -> List<RumorMessage>)? = null,
        val knownIdsProvider: () -> Set<String>,
        val onlineUsersProvider: () -> Map<String, Long>,
        val onExchangeFailed: (peerUserId: String) -> Unit = {},
        val rbsrItemsProvider: (suspend () -> List<RbsrItem>)? = null,
    )

    private val TAG = "LanTransport"
    private val SERVICE_TYPE = "_rumor-gossip._tcp.local."
    private val ROUND_INTERVAL_MS = 10_000L

    private var scope: CoroutineScope? = null
    private var jmdns: JmDNS? = null
    private var serverSocket: ServerSocket? = null

    /** Per-peer round loops keyed by mDNS service name (userId prefix). */
    private val peerLoops = ConcurrentHashMap<String, Job>()

    /** First-come-wins per verified peer userId — same rule as G22's claimSession. */
    private val activePeerSessions = ConcurrentHashMap<String, Boolean>()

    /**
     * O166 — pre-HELLO admission caps. `activePeerSessions` only gates AFTER a
     * peer proves its userId, so it doesn't bound the Ed25519-verification cost
     * an unauthenticated flooder can force. On O104's "the laptop IS the AP"
     * node, any associated device can reach this port for free. Cap total
     * concurrent inbound sessions and per-source-IP concurrency so a single
     * source can't monopolise the accept loop; the 5s HELLO soTimeout recycles
     * silent slots. Excess connections are closed immediately and retry next
     * round (10s) — graceful degradation, not loss.
     */
    private val inboundInflight = AtomicInteger(0)
    private val inboundPerSource = ConcurrentHashMap<String, Int>()

    private val _exchangeResults = MutableSharedFlow<PeerExchangeResult>(extraBufferCapacity = 64)
    val exchangeResults: SharedFlow<PeerExchangeResult> = _exchangeResults

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount

    private val serviceName get() = config.localUserId.take(16)

    fun start(bindAddress: InetAddress) {
        if (scope != null) return
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        s.launch {
            try {
                val ss = ServerSocket(0, 8, bindAddress)
                serverSocket = ss
                // mDNS is best-effort: multicast can be filtered (test
                // containers, exotic APs). The TCP server must survive that —
                // peers located by other means (tests, a future beacon-derived
                // address hint) can still dial in.
                runCatching {
                    val dns = JmDNS.create(bindAddress, serviceName)
                    jmdns = dns
                    dns.registerService(
                        ServiceInfo.create(SERVICE_TYPE, serviceName, ss.localPort, ""),
                    )
                    dns.addServiceListener(SERVICE_TYPE, listener)
                }.onFailure { RumorLog.w(TAG, "mDNS unavailable (${it.message}) — TCP server only") }
                RumorLog.i(TAG, "LAN transport up on ${bindAddress.hostAddress}:${ss.localPort}")
                while (isActive) {
                    val client = ss.accept()
                    client.tcpNoDelay = true
                    val src = client.inetAddress?.hostAddress ?: "?"
                    if (!admitInbound(client, src)) continue
                    launch {
                        try { runSession(client, isInbound = true) }
                        finally { releaseInbound(src) }
                    }
                }
            } catch (e: Exception) {
                if (isActive) RumorLog.w(TAG, "LAN transport failed (${e.message})")
            }
        }
    }

    /** Bound TCP port once the server is up; null before that. Tests + diagnostics. */
    fun boundPort(): Int? = serverSocket?.takeIf { !it.isClosed }?.localPort

    fun stop() {
        val dns = jmdns
        jmdns = null
        // JmDNS.close() sends goodbye packets and can block — do it off-caller.
        if (dns != null) Thread { runCatching { dns.close() } }.start()
        runCatching { serverSocket?.close() }
        serverSocket = null
        peerLoops.clear()
        peerTarget.clear()
        activePeerSessions.clear()
        inboundPerSource.clear()
        inboundInflight.set(0)
        _peerCount.value = 0
        scope?.cancel()
        scope = null
    }

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            // Resolution is async; serviceResolved carries the address/port.
            jmdns?.requestServiceInfo(event.type, event.name)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            peerLoops.remove(event.name)?.cancel()
            peerTarget.remove(event.name)
            _peerCount.value = peerLoops.size
        }

        override fun serviceResolved(event: ServiceEvent) {
            if (event.name == serviceName) return
            val info = event.info ?: return
            val address = info.inet4Addresses.firstOrNull() ?: return
            onPeerLocated(event.name, address, info.port)
        }
    }

    /** Last resolved endpoint per peer name — so a re-resolve to a NEW port re-targets. */
    private val peerTarget = ConcurrentHashMap<String, String>()

    /**
     * Starts (or keeps) the round loop for a located peer. Exposed so tests
     * can drive the session path over loopback without multicast.
     *
     * A re-resolve carrying a *different* endpoint (address:port) cancels the
     * stale loop and starts a fresh one: a peer that restarts gets a new
     * ephemeral port every time (field-observed on app reflash 2026-07-22), and
     * the old loop would otherwise keep dialing a dead port until its failure
     * budget expired — minutes of blindness to a peer that's actually back.
     */
    internal fun onPeerLocated(name: String, address: InetAddress, port: Int) {
        val s = scope ?: return
        val endpoint = "${address.hostAddress}:$port"
        peerLoops.compute(name) { _, existing ->
            if (existing?.isActive == true && peerTarget[name] == endpoint) return@compute existing
            existing?.cancel()
            peerTarget[name] = endpoint
            s.launch {
                RumorLog.i(TAG, "LAN peer $name at $endpoint")
                var failures = 0
                while (isActive && failures < MAX_ROUND_FAILURES) {
                    failures = if (runRound(address, port)) 0 else failures + 1
                    delay(ROUND_INTERVAL_MS)
                }
                peerLoops.remove(name)
                peerTarget.remove(name)
                _peerCount.value = peerLoops.size
            }
        }
        _peerCount.value = peerLoops.size
    }

    private suspend fun runRound(address: InetAddress, port: Int): Boolean = try {
        val socket = Socket(address, port)
        socket.tcpNoDelay = true
        runSession(socket, isInbound = false)
    } catch (e: Exception) {
        RumorLog.d(TAG, "LAN round failed (${e.message})")
        false
    }

    /**
     * O166 pre-HELLO admission. Reserves a total + per-source slot atomically;
     * on rejection the socket is closed immediately (the flooder pays a TCP
     * handshake, we pay nothing). Rolls back cleanly if either cap is hit.
     */
    private fun admitInbound(socket: Socket, src: String): Boolean {
        val perSource = inboundPerSource.merge(src, 1, Integer::sum)!!
        if (perSource > MAX_INBOUND_PER_SOURCE) {
            releaseSource(src)
            RumorLog.w(TAG, "inbound per-source cap ($MAX_INBOUND_PER_SOURCE) for $src — dropping")
            runCatching { socket.close() }
            return false
        }
        if (inboundInflight.incrementAndGet() > MAX_INBOUND_INFLIGHT) {
            inboundInflight.decrementAndGet()
            releaseSource(src)
            RumorLog.w(TAG, "inbound total cap ($MAX_INBOUND_INFLIGHT) reached — dropping $src")
            runCatching { socket.close() }
            return false
        }
        return true
    }

    private fun releaseInbound(src: String) {
        inboundInflight.decrementAndGet()
        releaseSource(src)
    }

    /** Decrement a source's concurrent count, removing the entry at zero (no map growth). */
    private fun releaseSource(src: String) {
        inboundPerSource.computeIfPresent(src) { _, v -> (v - 1).takeIf { it > 0 } }
    }

    private suspend fun runSession(socket: Socket, isInbound: Boolean): Boolean {
        val cfg = config
        var claimedPeer: String? = null
        val session = GossipSession(
            socket = socket,
            localUserId = cfg.localUserId,
            localPublicKey = cfg.localPublicKey,
            signer = cfg.signer,
            knownMessageIds = cfg.knownIdsProvider(),
            messagesProvider = cfg.messageProvider,
            messagesByIds = cfg.messagesByIds,
            recentOnlineUsers = cfg.onlineUsersProvider(),
            isInbound = isInbound,
            sessionGate = { peerUserId, _ ->
                val winner = activePeerSessions.putIfAbsent(peerUserId, true) == null
                if (winner) claimedPeer = peerUserId
                winner
            },
            rbsrItemsProvider = cfg.rbsrItemsProvider,
            supportedFeatures = if (cfg.rbsrItemsProvider != null)
                listOf(GossipSession.RBSR_FEATURE) else GossipSession.LOCAL_SUPPORTED_FEATURES,
        )
        val result = try { session.run() }
        finally { claimedPeer?.let { activePeerSessions.remove(it) } }
        if (result == null) {
            claimedPeer?.let { cfg.onExchangeFailed(it) }
            return false
        }
        _exchangeResults.emit(
            PeerExchangeResult(
                peerUserId = result.peerUserId,
                peerPublicKey = result.peerPublicKey,
                messagesReceived = result.messagesReceived,
                messagesSent = result.messagesSent,
                peerOnlineUsers = result.peerOnlineUsers,
                ackedByPeer = result.ackedByPeer,
                durationMs = result.durationMs,
                source = ExchangeSource.LAN,
                bytesTransferred = result.bytesTransferred,
                peerOverlapFraction = result.peerOverlapFraction,
                peerSupportedFeatures = result.peerSupportedFeatures,
            )
        )
        return true
    }

    companion object {
        private const val MAX_ROUND_FAILURES = 3

        /**
         * O166 admission caps. Total bounds concurrent Ed25519-verification cost;
         * per-source stops one IP from monopolising the accept loop. Generous for
         * a LAN (a peer opens one session at a time via the round loop), tight
         * enough that an unauthenticated flooder can't force unbounded crypto.
         * Excess connections retry next round (10s). `internal` so tests assert
         * against the real numbers.
         */
        internal const val MAX_INBOUND_INFLIGHT = 32
        internal const val MAX_INBOUND_PER_SOURCE = 4
    }
}
