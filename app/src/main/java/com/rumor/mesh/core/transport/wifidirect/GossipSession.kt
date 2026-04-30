package com.rumor.mesh.core.transport.wifidirect

import com.rumor.mesh.core.model.GossipPacket
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.logging.RumorLog
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * Single gossip exchange over a TCP socket.
 *
 * Wire format: each frame is [4-byte big-endian length][UTF-8 JSON payload].
 *
 * Sequence: HELLO → BLOOM → REQUEST → MESSAGE* → ONLINE_STATUS → BYE
 * Both sides send and receive each phase concurrently (full-duplex).
 */
class GossipSession(
    private val socket: Socket,
    private val localUserId: String,
    private val localPublicKey: String,
    private val knownMessageIds: Set<String>,
    private val messagesToOffer: List<RumorMessage>,
    private val recentOnlineUsers: Map<String, Long>,
) {
    private val TAG = "GossipSession"
    private val SESSION_TIMEOUT_MS = 30_000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    data class SessionResult(
        val peerUserId: String,
        val peerPublicKey: String,
        val messagesReceived: List<RumorMessage>,
        val messagesSent: Int,
        val peerOnlineUsers: Map<String, Long>,
        val durationMs: Long,
    )

    suspend fun run(): SessionResult? = withTimeout(SESSION_TIMEOUT_MS) {
        val startMs = System.currentTimeMillis()
        try {
            val out = DataOutputStream(socket.getOutputStream().buffered())
            val inp = DataInputStream(socket.getInputStream().buffered())

            // Phase 1: HELLO
            send(out, GossipPacket.Hello(localUserId, localPublicKey))
            val hello = receive(inp) as? GossipPacket.Hello
                ?: run { RumorLog.w(TAG, "Expected HELLO, got something else"); return@withTimeout null }
            RumorLog.d(TAG, "Handshake with ${hello.userId.take(16)}…")

            // Phase 2: BLOOM — exchange knowledge sets
            val bloom = buildBloom()
            send(out, GossipPacket.Bloom(bloom.serialized, bloom.expectedItems))
            val peerBloom = receive(inp) as? GossipPacket.Bloom
                ?: return@withTimeout null

            // Phase 3: REQUEST — ask for what we're missing
            val peerFilter = BloomFilterData.deserialize(peerBloom.filter, peerBloom.expectedItems)
            val theyNeed = messagesToOffer.filter { msg -> !peerFilter.mightContain(msg.id) }
            val weNeedIds = knownMessageIds.filter { id -> !bloom.filter.mightContain(id) }
                .let { emptyList<String>() } // We don't have a reverse map here; peer will send REQUEST

            send(out, GossipPacket.Request(weNeedIds))
            val theirRequest = receive(inp) as? GossipPacket.Request
                ?: return@withTimeout null

            // Phase 4: MESSAGE exchange — send what they asked for + what they're missing
            val toSend = (theyNeed + messagesToOffer.filter { it.id in theirRequest.messageIds })
                .distinctBy { it.id }
            for (msg in toSend) {
                send(out, GossipPacket.Message(msg))
            }

            // Receive their messages
            val received = mutableListOf<RumorMessage>()
            var packet = receive(inp)
            while (packet is GossipPacket.Message) {
                received.add(packet.message)
                packet = receive(inp)
            }

            // Phase 5: ONLINE_STATUS
            send(out, GossipPacket.OnlineStatus(recentOnlineUsers))
            val theirOnline = (packet as? GossipPacket.OnlineStatus)?.recentUsers
                ?: run {
                    if (packet !is GossipPacket.OnlineStatus) receive(inp) as? GossipPacket.OnlineStatus
                    else packet
                }?.recentUsers ?: emptyMap()

            // Phase 6: BYE
            send(out, GossipPacket.Bye())

            val duration = System.currentTimeMillis() - startMs
            RumorLog.i(TAG, "Session complete: ${received.size} received, ${toSend.size} sent in ${duration}ms")

            SessionResult(
                peerUserId = hello.userId,
                peerPublicKey = hello.publicKey,
                messagesReceived = received,
                messagesSent = toSend.size,
                peerOnlineUsers = theirOnline,
                durationMs = duration,
            )
        } catch (e: Exception) {
            RumorLog.w(TAG, "Session error", e)
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun send(out: DataOutputStream, packet: GossipPacket) {
        val bytes = json.encodeToString(packet).toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun receive(inp: DataInputStream): GossipPacket {
        val len = inp.readInt()
        require(len in 1..4_000_000) { "Frame length out of range: $len" }
        val bytes = ByteArray(len)
        inp.readFully(bytes)
        return json.decodeFromString(String(bytes, Charsets.UTF_8))
    }

    private fun buildBloom(): BloomData {
        val filter = BloomFilterData(expectedItems = maxOf(knownMessageIds.size, 100))
        knownMessageIds.forEach { filter.add(it) }
        return BloomData(filter, filter.serialize(), knownMessageIds.size)
    }

    private data class BloomData(
        val filter: BloomFilterData,
        val serialized: String,
        val expectedItems: Int,
    )
}
