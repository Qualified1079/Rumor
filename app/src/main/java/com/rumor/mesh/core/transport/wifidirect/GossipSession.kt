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
    /**
     * Provides messages to offer to the peer once their userId is verified.
     * Called after HELLO completes so the engine can shape the offer using
     * the peer's historical overlap (see [NeighborStore]).
     */
    private val messagesProvider: (peerUserId: String) -> List<RumorMessage>,
    private val recentOnlineUsers: Map<String, Long>,
    /** True when this session was accepted server-side; false when initiated as client. */
    private val isInbound: Boolean,
    /** Returns true if this session should proceed; false aborts. Called after HELLO. */
    private val sessionGate: (peerUserId: String, isInbound: Boolean) -> Boolean,
) {
    private val TAG = "GossipSession"
    private val SESSION_TIMEOUT_MS = 30_000L

    /**
     * Below this many known IDs, send a raw list during the summary phase.
     * At ~32 chars per ID, 500 IDs is roughly the crossover where a bloom filter
     * with 1% false-positive rate becomes more compact than the JSON-encoded list.
     */
    private val BLOOM_THRESHOLD = 500

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
        /** Total bytes moved in both directions (rough envelope-aware estimate). */
        val bytesTransferred: Long = 0,
        /**
         * Fraction of the offered batch that the peer's summary indicated they
         * already knew (0=fully novel, 1=full overlap). Used to score this peer
         * for diversity-aware relay selection.
         */
        val peerOverlapFraction: Float = 0.5f,
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

            // Now that the peer is verified, ask the engine for a peer-shaped offer.
            // The engine uses NeighborStore overlap history to trim or expand the batch.
            val messagesToOffer = messagesProvider(hello.userId)

            // Phase 1c: NEIGHBOR_DIGEST — share compact representation of our known
            // message set so each side can compute an overlap fraction after the session.
            send(out, buildNeighborDigest())
            val peerDigest = receive(inp) as? GossipPacket.NeighborDigest

            // Phase 2: SUMMARY — exchange knowledge sets.
            // Below the bloom threshold, send a raw ID list (zero false positives, more
            // compact at small sizes). Above it, send a bloom filter. Either form is
            // accepted on receive.
            send(out, buildSummary())
            val peerKnows = when (val peerSummary = receive(inp)) {
                is GossipPacket.IdList -> peerSummary.ids.toHashSet().let { set ->
                    { id: String -> id in set }
                }
                is GossipPacket.Bloom -> {
                    val bloom = BloomFilterData.tryDeserialize(
                        peerSummary.filter, peerSummary.expectedItems,
                    )
                    if (bloom != null) {
                        { id: String -> bloom.mightContain(id) }
                    } else {
                        // Peer-supplied expectedItems forced an allocation failure.
                        // Drop the bloom and treat the peer as knowing nothing —
                        // we'll over-offer this one exchange instead of crashing.
                        RumorLog.w(TAG, "Rejected adversarial bloom; over-offering this exchange")
                        val empty: (String) -> Boolean = { false }
                        empty
                    }
                }
                else -> return@withTimeout null
            }

            // How much of our offer did the peer already know? Lower = more valuable relay.
            val overlapFraction = if (messagesToOffer.isEmpty()) 0f
            else messagesToOffer.count { peerKnows(it.id) }.toFloat() / messagesToOffer.size

            // Phase 3: REQUEST — ask for what we're missing.
            // We can't enumerate "what we're missing" without knowing what they have
            // beyond their summary, so send empty here; peer will reply with what they
            // think we're missing based on our own summary.
            val theyNeed = messagesToOffer.filter { msg -> !peerKnows(msg.id) }
            val weNeedIds = emptyList<String>()

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

            fun msgBytes(msgs: List<RumorMessage>) = msgs.sumOf { msg ->
                256L + (msg.payload?.content?.length ?: 0) + (msg.encryptedPayload?.length ?: 0)
            }
            val bytesTransferred = msgBytes(received) + msgBytes(toSend)

            RumorLog.i(TAG, "Session complete: ${received.size} received, ${toSend.size} sent " +
                "in ${duration}ms, overlap=%.2f".format(overlapFraction))

            SessionResult(
                peerUserId          = hello.userId,
                peerPublicKey       = hello.publicKey,
                messagesReceived    = received,
                messagesSent        = toSend.size,
                peerOnlineUsers     = theirOnline,
                ackedByPeer         = ackedByPeer,
                durationMs          = duration,
                bytesTransferred    = bytesTransferred,
                peerOverlapFraction = overlapFraction,
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

    /**
     * Compact bloom digest of [knownMessageIds] for overlap scoring.
     * Always a bloom (never an id_list) — the recipient only needs to probe
     * membership, not enumerate the set.
     */
    private fun buildNeighborDigest(): GossipPacket.NeighborDigest {
        val n = knownMessageIds.size.coerceAtLeast(1)
        val filter = BloomFilterData(n)
        knownMessageIds.forEach { filter.add(it) }
        return GossipPacket.NeighborDigest(filter.serialize(), n)
    }

    /**
     * Choose the most compact representation of [knownMessageIds]. Below the threshold
     * a raw list is smaller and exact; above it a bloom filter wins on size at the
     * cost of a small false-positive rate (acceptable because the mesh's redundant
     * paths reach skipped nodes through other routes anyway).
     */
    private fun buildSummary(): GossipPacket {
        if (knownMessageIds.size < BLOOM_THRESHOLD) {
            return GossipPacket.IdList(knownMessageIds.toList())
        }
        val filter = BloomFilterData(expectedItems = knownMessageIds.size)
        knownMessageIds.forEach { filter.add(it) }
        return GossipPacket.Bloom(filter.serialize(), knownMessageIds.size)
    }
}
