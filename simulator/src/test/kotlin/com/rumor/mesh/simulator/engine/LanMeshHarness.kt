package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.transport.lan.LanTransport
import java.net.InetAddress
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-fidelity LAN backend for the mesh harness. Every node is a [SimNode]
 * (real `GossipEngine`/relay/dedup/crypto) wired to a REAL [LanTransport] on
 * loopback — real TCP, real `GossipSession` wire framing, the actual transport
 * the desktop `:node` and phone use. This is the top of the fidelity ladder:
 * unlike [MeshHarness] (real engine, in-process `SimTransport` wire), here the
 * bytes go over sockets.
 *
 * mDNS is disabled ([LanTransport.Config.enableMdns] = false) so topology is
 * deterministic (loopback mDNS auto-full-meshes otherwise); edges are wired via
 * [LanTransport.onPeerLocated]. Gossip rounds are 10 s (transport constant), so
 * multi-hop delivery is wall-clock slow — fidelity, not speed; keep N small.
 */
class LanMeshHarness(
    private val n: Int,
    private val seed: Long = 1,
    /** Nodes that refuse to relay: they still receive/absorb, but offer nothing
     *  to peers (a real "won't forward" blackhole over the wire). */
    private val hostile: Set<Int> = emptySet(),
) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val clock = SimClock(System.currentTimeMillis())
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    val nodes = (0 until n).map { SimNode(it, scope, useBreadcrumbs = true, clock = clock) }
    private val transports = arrayOfNulls<LanTransport>(n)
    private val online = BooleanArray(n) { true }
    private var edges: List<Pair<Int, Int>> = emptyList()

    private fun buildTransport(i: Int): LanTransport {
        val node = nodes[i]
        val lan = LanTransport(
            LanTransport.Config(
                localUserId = node.userId,
                localPublicKey = Base64.getEncoder().encodeToString(node.identityProvider.identity.value!!.publicKeyBytes),
                signer = { bytes -> node.identityProvider.identity.value?.let { CryptoManager.sign(bytes, it.privateKeyBytes) } },
                // A hostile relay absorbs but offers nothing onward.
                messageProvider = if (i in hostile) { _ -> emptyList() } else node.gossipEngine::messagesForExchange,
                messagesByIds = node.gossipEngine::messagesByIds,
                knownIdsProvider = node.gossipEngine::knownMessageIds,
                onlineUsersProvider = node.onlineTracker::currentSnapshot,
                onExchangeFailed = node.gossipEngine::onExchangeFailed,
                rbsrItemsProvider = node.gossipEngine::rbsrSnapshot,
                enableMdns = false,
            )
        )
        // exchangeResults is a per-instance SharedFlow; the collector rides the
        // harness scope so it survives a stop()/start() of this transport.
        scope.launch { lan.exchangeResults.collect { node.gossipEngine.onExchange(it) } }
        lan.start(loopback)
        return lan
    }

    private suspend fun awaitBound(t: LanTransport) {
        var tries = 0
        while (t.boundPort() == null && tries++ < 200) delay(25)
        checkNotNull(t.boundPort()) { "LanTransport never bound" }
    }

    /** (Re)dial every edge touching an online node, using current ports. Idempotent. */
    private fun rewire() {
        for ((a, b) in edges) {
            if (!online[a] || !online[b]) continue
            val ta = transports[a] ?: continue
            val tb = transports[b] ?: continue
            tb.onPeerLocated(nodes[a].userId.take(16), loopback, ta.boundPort()!!)
        }
    }

    /** Build a real LanTransport per node and wire the given undirected [edges]. */
    suspend fun start(edges: List<Pair<Int, Int>>) {
        this.edges = edges
        for (i in 0 until n) transports[i] = buildTransport(i)
        transports.forEach { awaitBound(it!!) }
        rewire()
    }

    /**
     * Take a node offline (stop its real transport — server + peer loops down)
     * or back online (fresh transport on a new port, re-wired). Models a
     * duty-cycled device: the O202 delivery risk, exercised over the real wire.
     */
    suspend fun setOnline(index: Int, up: Boolean) {
        if (online[index] == up) return
        if (!up) {
            transports[index]?.stop()
            online[index] = false
        } else {
            transports[index] = buildTransport(index)
            awaitBound(transports[index]!!)
            online[index] = true
            rewire()  // restarted node got a new port; re-dial affected edges
        }
    }

    /** Compose a real DM (X25519+AES-GCM) [from] -> [to]; returns its message id. */
    fun sendDm(from: Int, to: Int, text: String): String? {
        val recip = nodes[to].identityProvider.identity.value ?: return null
        return nodes[from].gossipEngine.composeDirect(
            recipientId = nodes[to].userId,
            recipientPublicKey = recip.publicKeyBytes,
            text = text,
            hopsToLive = 16,
        )?.id
    }

    /** Compose a real signed broadcast from [from]; returns its message id. */
    fun broadcast(from: Int, text: String): String? =
        nodes[from].gossipEngine.composeBroadcast(text)?.id

    /** Byzantine flood: [from] originates [count] junk broadcasts to load the mesh. */
    fun flood(from: Int, count: Int, tag: String = "flood") {
        for (i in 0 until count) nodes[from].gossipEngine.composeBroadcast("$tag-$i")
    }

    /** Poll the recipient's real store until [id] arrives or [timeoutMs] elapses. */
    suspend fun awaitDelivered(id: String, to: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (nodes[to].messageRepo.getById(id) != null) return true
            delay(500)
        }
        return nodes[to].messageRepo.getById(id) != null
    }

    fun stop() {
        transports.forEach { t -> t?.let { runCatching { it.stop() } } }
        scope.cancel()
    }
}
