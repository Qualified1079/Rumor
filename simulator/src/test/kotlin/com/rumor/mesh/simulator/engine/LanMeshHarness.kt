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
class LanMeshHarness(private val n: Int, private val seed: Long = 1) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val clock = SimClock(System.currentTimeMillis())
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    val nodes = (0 until n).map { SimNode(it, scope, useBreadcrumbs = true, clock = clock) }
    private val transports = ArrayList<LanTransport>(n)

    /** Build a real LanTransport per node and wire the given undirected [edges]. */
    suspend fun start(edges: List<Pair<Int, Int>>) {
        for (node in nodes) {
            val idKeys = node.identityProvider.identity.value!!
            val lan = LanTransport(
                LanTransport.Config(
                    localUserId = node.userId,
                    localPublicKey = Base64.getEncoder().encodeToString(idKeys.publicKeyBytes),
                    signer = { bytes -> node.identityProvider.identity.value?.let { CryptoManager.sign(bytes, it.privateKeyBytes) } },
                    messageProvider = node.gossipEngine::messagesForExchange,
                    messagesByIds = node.gossipEngine::messagesByIds,
                    knownIdsProvider = node.gossipEngine::knownMessageIds,
                    onlineUsersProvider = node.onlineTracker::currentSnapshot,
                    onExchangeFailed = node.gossipEngine::onExchangeFailed,
                    rbsrItemsProvider = node.gossipEngine::rbsrSnapshot,
                    enableMdns = false,
                )
            )
            scope.launch { lan.exchangeResults.collect { node.gossipEngine.onExchange(it) } }
            lan.start(loopback)
            transports.add(lan)
        }
        // Wait for every server socket to bind.
        for (t in transports) {
            var tries = 0
            while (t.boundPort() == null && tries++ < 200) delay(25)
            checkNotNull(t.boundPort()) { "LanTransport never bound" }
        }
        // Wire each undirected edge as one dial (session is bidirectional).
        for ((a, b) in edges) {
            transports[b].onPeerLocated(nodes[a].userId.take(16), loopback, transports[a].boundPort()!!)
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
        transports.forEach { runCatching { it.stop() } }
        scope.cancel()
    }
}
