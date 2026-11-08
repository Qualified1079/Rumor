package com.rumor.mesh.core.transport.wifidirect

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.GossipPacket
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.helloChallengeBytes
import com.rumor.mesh.core.wire.WireJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * O242 — a peer that sends the session's frames out of order, or ends its
 * message stream without a matching terminator, must make the session abort
 * rather than be silently re-read as the next phase. The hostile side is a
 * script that completes a genuine handshake and then sends exactly the frames
 * each case names; the honest side is the real [GossipSession].
 */
class GossipSessionStrictFrameTest {

    private val keys = CryptoManager.generateEd25519KeyPair()
    private val userId = CryptoManager.publicKeyToUserId(keys.publicKeyBytes)
    private val pubB64 = keys.publicKeyBytes.toBase64()

    private fun send(out: DataOutputStream, p: GossipPacket) {
        val bytes = WireJson.encodeToString(GossipPacket.serializer(), p).toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size); out.write(bytes); out.flush()
    }

    private fun receive(inp: DataInputStream): GossipPacket {
        val bytes = ByteArray(inp.readInt()).also { inp.readFully(it) }
        return WireJson.decodeFromString(GossipPacket.serializer(), String(bytes, Charsets.UTF_8))
    }

    private fun message(id: String) = RumorMessage(
        id = id, senderId = userId, senderPublicKey = pubB64, sequenceNumber = 1, sentAtMs = 1,
        type = MessageType.BROADCAST, hopsToLive = 5,
        payload = MessagePayload(ContentType.TEXT, "m"), signature = "unverified-at-session-layer",
    )

    private val digest get() = GossipPacket.NeighborDigest(BloomFilterData(1).serialize(), 1)
    private val tail get() = listOf(
        GossipPacket.Ack(emptyList()),
        GossipPacket.OnlineStatus(emptyMap()),
        GossipPacket.Bye(),
    )

    /**
     * Runs the honest session against a scripted peer. The script does a correct
     * HELLO/HELLO_PROOF, then sends [afterHandshake] while a drain thread reads
     * (and keeps) everything the honest side sends.
     */
    private fun runAgainst(
        afterHandshake: List<GossipPacket>,
        honestFeatures: List<String> = emptyList(),
    ): Pair<GossipSession.SessionResult?, List<GossipPacket>> = runBlocking {
        val honestKeys = CryptoManager.generateEd25519KeyPair()
        val seen = java.util.Collections.synchronizedList(mutableListOf<GossipPacket>())
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val scriptSocket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            val honestSocket = server.accept()
            val honest = async(Dispatchers.IO) {
                GossipSession(
                    socket = honestSocket,
                    localUserId = CryptoManager.publicKeyToUserId(honestKeys.publicKeyBytes),
                    localPublicKey = honestKeys.publicKeyBytes.toBase64(),
                    signer = { CryptoManager.sign(it, honestKeys.privateKeyBytes) },
                    knownMessageIds = emptySet(),
                    messagesProvider = { emptyList() },
                    recentOnlineUsers = emptyMap(),
                    isInbound = true,
                    sessionGate = { _, _ -> true },
                    supportedFeatures = honestFeatures,
                ).run()
            }
            val script = async(Dispatchers.IO) {
                scriptSocket.use { sock ->
                    val out = DataOutputStream(sock.getOutputStream().buffered())
                    val inp = DataInputStream(sock.getInputStream().buffered())
                    val nonce = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }.toBase64()
                    val ours = GossipPacket.Hello(userId = userId, publicKey = pubB64, nonce = nonce)
                    send(out, ours)
                    val theirs = receive(inp) as GossipPacket.Hello
                    seen.add(theirs)
                    val challenge = helloChallengeBytes(
                        nonceBase64 = theirs.nonce,
                        protocolVersion = ours.protocolVersion,
                        maxProtocolVersion = ours.maxProtocolVersion,
                        supportedFeatures = ours.supportedFeatures,
                    )
                    send(out, GossipPacket.HelloProof(CryptoManager.sign(challenge, keys.privateKeyBytes)!!.toBase64()))
                    seen.add(receive(inp))
                    val drain = Thread {
                        runCatching { while (true) seen.add(receive(inp)) }
                    }.apply { isDaemon = true; start() }
                    runCatching { afterHandshake.forEach { send(out, it) } }
                    drain.join(5_000)
                }
            }
            val result = honest.await()
            script.await()
            result to seen.toList()
        }
    }

    @Test
    fun `control - a peer that follows the protocol completes the session`() {
        val (result, _) = runAgainst(
            listOf(digest, GossipPacket.IdList(emptyList()), GossipPacket.Request(emptyList()),
                GossipPacket.Message(message("m1")), GossipPacket.MessagesDone(1)) + tail,
        )
        assertNotNull("a well-formed exchange must complete", result)
        assertEquals(listOf("m1"), result!!.messagesReceived.map { it.id })
    }

    @Test
    fun `skipping the neighbor digest aborts instead of eating the summary`() {
        val (result, _) = runAgainst(
            // A second summary where the digest belongs: the lenient reader took the
            // first as the digest and the second as the summary, and carried on.
            listOf(GossipPacket.IdList(emptyList()), GossipPacket.IdList(emptyList()),
                GossipPacket.Request(emptyList()), GossipPacket.MessagesDone(0)) + tail,
        )
        assertNull("a missing NEIGHBOR_DIGEST must abort the session", result)
    }

    @Test
    fun `a message stream without its terminator aborts`() {
        val (result, _) = runAgainst(
            listOf(digest, GossipPacket.IdList(emptyList()), GossipPacket.Request(emptyList()),
                GossipPacket.Message(message("m1"))) + tail,
        )
        assertNull("a stream ended by ACK instead of MESSAGES_DONE must abort", result)
        assertThrows(AssertionError::class.java) { assertNotNull("teeth", result) }
    }

    @Test
    fun `a terminator whose count does not match aborts`() {
        val (result, _) = runAgainst(
            listOf(digest, GossipPacket.IdList(emptyList()), GossipPacket.Request(emptyList()),
                GossipPacket.Message(message("m1")), GossipPacket.MessagesDone(2)) + tail,
        )
        assertNull("MESSAGES_DONE(2) after one message must abort", result)
    }

    @Test
    fun `an out-of-order tail aborts`() {
        val (result, _) = runAgainst(
            listOf(digest, GossipPacket.IdList(emptyList()), GossipPacket.Request(emptyList()),
                GossipPacket.MessagesDone(0), GossipPacket.OnlineStatus(emptyMap()),
                GossipPacket.Ack(emptyList()), GossipPacket.Bye()),
        )
        assertNull("ONLINE_STATUS where ACK belongs must abort", result)
    }

    @Test
    fun `rbsr is not advertised without a snapshot to run it from`() {
        val (_, seen) = runAgainst(emptyList(), honestFeatures = listOf(GossipSession.RBSR_FEATURE))
        val hello = seen.filterIsInstance<GossipPacket.Hello>().single()
        assertFalse(
            "advertising rbsr-v1 with no rbsrItemsProvider lets both peers pick a mode this side can't run",
            GossipSession.RBSR_FEATURE in hello.supportedFeatures,
        )
    }
}
