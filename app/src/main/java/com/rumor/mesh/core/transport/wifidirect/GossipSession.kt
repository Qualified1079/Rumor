package com.rumor.mesh.core.transport.wifidirect

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.GossipPacket
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.helloChallengeBytes
import com.rumor.mesh.core.logging.RumorLog
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.SecureRandom

/**
 * Single gossip exchange over a TCP socket.
 *
 * Wire format: each frame is [4-byte big-endian length][UTF-8 JSON payload].
 *
 * Sequence: HELLO → HELLO_PROOF → BLOOM → REQUEST → MESSAGE* → ACK → ONLINE_STATUS → BYE
 *
 * Authentication: each side sends a random nonce in HELLO and signs the peer's
 * nonce with their Ed25519 private key. A peer that can't produce a valid signature
 * over the challenge bytes can't claim a userId they don't own.
 *
 * Deduplication: after HELLO, [sessionGate] is consulted with the peer's userId
 * and [isInbound]. The gate enforces the dual-role tiebreak so that if both a
 * server-accept and a client-connect succeed for the same peer pair, only one
 * survives. Returning false aborts the session cleanly before any data exchange.
 */
class GossipSession(
    private val socket: Socket,
    private val localUserId: String,
    private val localPublicKey: String,
    /** Signs arbitrary bytes with the local Ed25519 private key. Null aborts the session. */
    private val signer: (ByteArray) -> ByteArray?,
    private val knownMessageIds: Set<String>,
    private val messagesToOffer: List<RumorMessage>,
    private val recentOnlineUsers: Map<String, Long>,
    /** True when this session was accepted server-side; false when initiated as client. */
    private val isInbound: Boolean,
    /** Returns true if this session should proceed; false aborts. Called after HELLO. */
    private val sessionGate: (peerUserId: String, isInbound: Boolean) -> Boolean,
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
        /** Message IDs we sent that the peer confirmed accepting. */
        val ackedByPeer: List<String>,
        val durationMs: Long,
    )

    suspend fun run(): SessionResult? = withTimeout(SESSION_TIMEOUT_MS) {
        val startMs = System.currentTimeMillis()
        try {
            val out = DataOutputStream(socket.getOutputStream().buffered())
            val inp = DataInputStream(socket.getInputStream().buffered())

            // Phase 1: HELLO — exchange identities + challenge nonces
            val ourNonce = ByteArray(32).also { SecureRandom().nextBytes(it) }.toBase64()
            send(out, GossipPacket.Hello(localUserId, localPublicKey, ourNonce))
            val hello = receive(inp) as? GossipPacket.Hello
                ?: run { RumorLog.w(TAG, "Expected HELLO, got something else"); return@withTimeout null }
            RumorLog.d(TAG, "Handshake with ${hello.userId.take(16)}…")

            // Bind the peer's claimed userId to their public key — userId is the
            // SHA-256 fingerprint of the key, so we can verify without a registry.
            val peerPubKeyBytes = try { hello.publicKey.fromBase64() }
                catch (e: Exception) { RumorLog.w(TAG, "Bad peer pubkey encoding"); return@withTimeout null }
            val expectedUserId = CryptoManager.publicKeyToUserId(peerPubKeyBytes)
            if (expectedUserId != hello.userId) {
                RumorLog.w(TAG, "Peer userId does not match its public key fingerprint")
                return@withTimeout null
            }

            // Phase 1b: HELLO_PROOF — each side signs the other's nonce
            val ourProof = signer(helloChallengeBytes(hello.nonce))
                ?: run { RumorLog.w(TAG, "Local signer unavailable"); return@withTimeout null }
            send(out, GossipPacket.HelloProof(ourProof.toBase64()))
            val proof = receive(inp) as? GossipPacket.HelloProof
                ?: run { RumorLog.w(TAG, "Expected HELLO_PROOF"); return@withTimeout null }
            val proofBytes = try { proof.signature.fromBase64() }
                catch (e: Exception) { RumorLog.w(TAG, "Bad proof encoding"); return@withTimeout null }
            if (!CryptoManager.verify(helloChallengeBytes(ourNonce), proofBytes, peerPubKeyBytes)) {
                RumorLog.w(TAG, "Peer ${hello.userId.take(16)}… failed challenge — aborting")
                return@withTimeout null
            }

            // Tiebreak: when dual-role (both server-accept and client-connect
            // succeed for the same peer), keep exactly one. Both sides agree on
            // which to keep using (localUserId vs peerUserId, isInbound).
            if (!sessionGate(hello.userId, isInbound)) {
                RumorLog.d(TAG, "Duplicate session with ${hello.userId.take(16)}… — yielding")
                return@withTimeout null
            }

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

            // Phase 4b: ACK — confirm which messages we accepted in this session.
            // Reaching this point means signature checks (done by the engine on ingest)
            // haven't run yet, so we acknowledge what we received at the wire layer.
            send(out, GossipPacket.Ack(received.map { it.id }))
            val ackedByPeer = (packet as? GossipPacket.Ack)?.acceptedIds ?: emptyList()
            if (packet is GossipPacket.Ack) packet = receive(inp)

            // Phase 5: ONLINE_STATUS
            send(out, GossipPacket.OnlineStatus(recentOnlineUsers))
            val theirOnline = (packet as? GossipPacket.OnlineStatus)?.recentUsers ?: emptyMap()

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
                ackedByPeer = ackedByPeer,
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
