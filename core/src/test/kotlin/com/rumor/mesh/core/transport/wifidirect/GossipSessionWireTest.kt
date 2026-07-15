package com.rumor.mesh.core.transport.wifidirect

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.sync.RbsrItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * O42 go-live wire test: two real [GossipSession]s over a loopback socket pair,
 * exercising the exact production path — HELLO with `knownCount`, the adaptive
 * summary gate, the RBSR frame loop, and the bloom fallback. This is the branch
 * SimTransport does NOT cover (it has its own in-process rbsrExchange), so
 * before this harness the production RBSR branch had never executed anywhere.
 *
 * Also the first test harness for the session state machine at all — the
 * component five field bugs lived in (G18's MESSAGES_DONE deadlock among them).
 */
class GossipSessionWireTest {

    private class Side(name: String) {
        val keys = CryptoManager.generateEd25519KeyPair()
        val userId = CryptoManager.publicKeyToUserId(keys.publicKeyBytes)
        val pubB64 = keys.publicKeyBytes.toBase64()
        val signer: (ByteArray) -> ByteArray? = { CryptoManager.sign(it, keys.privateKeyBytes) }
        val label = name
    }

    private fun msg(id: String, ts: Long, side: Side) = RumorMessage(
        id = id,
        senderId = side.userId,
        senderPublicKey = side.pubB64,
        sequenceNumber = ts,
        sentAtMs = ts,
        type = MessageType.BROADCAST,
        hopsToLive = 5,
        payload = MessagePayload(ContentType.TEXT, "content-$id"),
        signature = "unverified-at-session-layer",
    )

    /** Run both sessions concurrently over a fresh loopback pair; return (serverResult, clientResult). */
    private fun runPair(
        buildServer: (Socket) -> GossipSession,
        buildClient: (Socket) -> GossipSession,
    ): Pair<GossipSession.SessionResult?, GossipSession.SessionResult?> = runBlocking {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val clientSocket = Socket(java.net.InetAddress.getLoopbackAddress(), server.localPort)
            val serverSocket = server.accept()
            val a = async(Dispatchers.IO) { buildServer(serverSocket).run() }
            val b = async(Dispatchers.IO) { buildClient(clientSocket).run() }
            a.await() to b.await()
        }
    }

    @Test
    fun `rbsr engages above the gate and delivers the exact diff`() {
        val alice = Side("alice")
        val bob = Side("bob")

        // 3,500 shared items (over RBSR_MIN_SET_SIZE=3000); Alice holds 3 more.
        val sharedIds = (0 until 3_500).map { "shared-$it" }
        val aliceOnly = listOf("extra-1", "extra-2", "extra-3")
        val tsOf = { id: String -> 1_000L + id.hashCode().mod(100_000) }

        val aliceItems = (sharedIds + aliceOnly).map { RbsrItem(tsOf(it), it) }
        val bobItems = sharedIds.map { RbsrItem(tsOf(it), it) }
        val aliceOffer = aliceOnly.map { msg(it, tsOf(it), alice) }

        val aliceProviderRan = AtomicBoolean(false)
        val bobProviderRan = AtomicBoolean(false)

        val (aliceResult, bobResult) = runPair(
            buildServer = { sock ->
                GossipSession(
                    socket = sock,
                    localUserId = alice.userId, localPublicKey = alice.pubB64, signer = alice.signer,
                    knownMessageIds = (sharedIds + aliceOnly).toSet(),
                    messagesProvider = { aliceOffer },
                    recentOnlineUsers = emptyMap(),
                    isInbound = true,
                    sessionGate = { _, _ -> true },
                    rbsrItemsProvider = { aliceProviderRan.set(true); aliceItems },
                    supportedFeatures = listOf(GossipSession.RBSR_FEATURE),
                )
            },
            buildClient = { sock ->
                GossipSession(
                    socket = sock,
                    localUserId = bob.userId, localPublicKey = bob.pubB64, signer = bob.signer,
                    knownMessageIds = sharedIds.toSet(),
                    messagesProvider = { emptyList() },
                    recentOnlineUsers = emptyMap(),
                    isInbound = false,
                    sessionGate = { _, _ -> true },
                    rbsrItemsProvider = { bobProviderRan.set(true); bobItems },
                    supportedFeatures = listOf(GossipSession.RBSR_FEATURE),
                )
            },
        )

        assertNotNull("alice session must complete", aliceResult)
        assertNotNull("bob session must complete", bobResult)
        assertTrue("gate passed → alice snapshot taken", aliceProviderRan.get())
        assertTrue("gate passed → bob snapshot taken", bobProviderRan.get())
        assertEquals(
            "bob receives exactly the 3-message diff",
            aliceOnly.toSet(), bobResult!!.messagesReceived.map { it.id }.toSet(),
        )
        assertEquals("alice sent exactly the diff", 3, aliceResult!!.messagesSent)
        assertEquals(
            "bob acked what he received",
            aliceOnly.toSet(), aliceResult.ackedByPeer.toSet(),
        )
    }

    @Test
    fun `below the gate the snapshot is never taken and bloom still delivers`() {
        val alice = Side("alice")
        val bob = Side("bob")

        // Small sets: max(101, 100) < 3000 → bloom path even though both support RBSR.
        val sharedIds = (0 until 100).map { "s-$it" }
        val extra = msg("bloom-extra", 5_000L, alice)

        val aliceProviderRan = AtomicBoolean(false)
        val bobProviderRan = AtomicBoolean(false)

        val (aliceResult, bobResult) = runPair(
            buildServer = { sock ->
                GossipSession(
                    socket = sock,
                    localUserId = alice.userId, localPublicKey = alice.pubB64, signer = alice.signer,
                    knownMessageIds = sharedIds.toSet() + extra.id,
                    messagesProvider = { listOf(extra) },
                    recentOnlineUsers = emptyMap(),
                    isInbound = true,
                    sessionGate = { _, _ -> true },
                    rbsrItemsProvider = { aliceProviderRan.set(true); emptyList() },
                    supportedFeatures = listOf(GossipSession.RBSR_FEATURE),
                )
            },
            buildClient = { sock ->
                GossipSession(
                    socket = sock,
                    localUserId = bob.userId, localPublicKey = bob.pubB64, signer = bob.signer,
                    knownMessageIds = sharedIds.toSet(),
                    messagesProvider = { emptyList() },
                    recentOnlineUsers = emptyMap(),
                    isInbound = false,
                    sessionGate = { _, _ -> true },
                    rbsrItemsProvider = { bobProviderRan.set(true); emptyList() },
                    supportedFeatures = listOf(GossipSession.RBSR_FEATURE),
                )
            },
        )

        assertNotNull(aliceResult); assertNotNull(bobResult)
        assertFalse("below gate → alice snapshot must not run", aliceProviderRan.get())
        assertFalse("below gate → bob snapshot must not run", bobProviderRan.get())
        assertEquals(
            "bloom path still delivers the delta",
            listOf("bloom-extra"), bobResult!!.messagesReceived.map { it.id },
        )
    }

    @Test
    fun `mixed version pair falls back to bloom with no mode split`() {
        val alice = Side("alice")   // new build: provider wired, advertises rbsr-v1
        val bob = Side("bob")       // pre-O42 build: no provider, no feature flag

        val sharedIds = (0 until 3_500).map { "m-$it" }   // over the size gate
        val extra = msg("legacy-extra", 9_000L, alice)
        val aliceProviderRan = AtomicBoolean(false)

        val (aliceResult, bobResult) = runPair(
            buildServer = { sock ->
                GossipSession(
                    socket = sock,
                    localUserId = alice.userId, localPublicKey = alice.pubB64, signer = alice.signer,
                    knownMessageIds = sharedIds.toSet() + extra.id,
                    messagesProvider = { listOf(extra) },
                    recentOnlineUsers = emptyMap(),
                    isInbound = true,
                    sessionGate = { _, _ -> true },
                    rbsrItemsProvider = { aliceProviderRan.set(true); emptyList() },
                    supportedFeatures = listOf(GossipSession.RBSR_FEATURE),
                )
            },
            buildClient = { sock ->
                GossipSession(
                    socket = sock,
                    localUserId = bob.userId, localPublicKey = bob.pubB64, signer = bob.signer,
                    knownMessageIds = sharedIds.toSet(),
                    messagesProvider = { emptyList() },
                    recentOnlineUsers = emptyMap(),
                    isInbound = false,
                    sessionGate = { _, _ -> true },
                    // Pre-O42 peer: no provider, no feature flag.
                )
            },
        )

        assertNotNull("no mode split — alice completes", aliceResult)
        assertNotNull("no mode split — bob completes", bobResult)
        assertFalse("peer lacks rbsr-v1 → snapshot never taken", aliceProviderRan.get())
        assertEquals(
            "legacy bloom path delivers",
            listOf("legacy-extra"), bobResult!!.messagesReceived.map { it.id },
        )
    }
}
