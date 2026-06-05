package com.rumor.mesh.core.transport.wifidirect

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.GossipPacket
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.helloChallengeBytes
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.sync.toMemory
import com.rumor.mesh.core.sync.toWire
import kotlinx.coroutines.withTimeout
import com.rumor.mesh.core.wire.WireJson
import kotlinx.serialization.encodeToString
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
    /**
     * Optional snapshot of `(sentAtMs, id)` tuples for every message in the local
     * store. When non-null AND both peers advertise `rbsr-v1` in HELLO, the
     * summary phase uses Range-Based Set Reconciliation (O42) instead of the
     * bloom-filter offer/want. Defaulting null keeps the existing bloom path
     * for builds that haven't wired the storage adapter through yet.
     */
    private val rbsrItems: List<com.rumor.mesh.core.sync.RbsrItem>? = null,
    /** Capability flags this session advertises in HELLO. See [LOCAL_SUPPORTED_FEATURES]. */
    private val supportedFeatures: List<String> = LOCAL_SUPPORTED_FEATURES,
) {
    private val TAG = "GossipSession"
    private val SESSION_TIMEOUT_MS = 30_000L

    /**
     * Below this many known IDs, send a raw list during the summary phase.
     * At ~32 chars per ID, 500 IDs is roughly the crossover where a bloom filter
     * with 1% false-positive rate becomes more compact than the JSON-encoded list.
     */
    private val BLOOM_THRESHOLD = 500

    private val json get() = WireJson

    companion object {
        /** Wire-format version this build emits. */
        const val LOCAL_PROTOCOL_VERSION: Int = 1
        /** Highest wire-format version this build can parse. Session uses `min(mine, theirs)`. */
        const val LOCAL_MAX_PROTOCOL_VERSION: Int = 1
        /** Range-Based Set Reconciliation capability flag (O42), v1 — Rumor-original XOR-of-domain-tagged-SHA256. */
        const val RBSR_FEATURE: String = "rbsr-v1"
        /**
         * O42 v2 — NIP-77 / hoytech-compatible fingerprint formula.
         * Wins free byte-compat with strfry and any NIP-77 relay (unblocks
         * O54 transport plugins and O72 Nostr fallback reusing the RBSR
         * machinery). Selected when BOTH peers advertise `rbsr-v2`;
         * fallback to v1 if only one peer advertises v2; fallback to
         * bloom if neither does. Stays out of [LOCAL_SUPPORTED_FEATURES]
         * until promote-to-default sim gate (see CLAUDE.md O42).
         */
        const val RBSR_V2_FEATURE: String = "rbsr-v2"
        /**
         * Per-message compression + padding capability flag (O76).
         *
         * Not yet honored by the compose path — gating it on requires the
         * AEAD-AD wiring documented in `core/wire/CompressedPaddedExt.kt`
         * to land first. The receive path will treat `_ext.c = true` as a
         * signal to call `CompressedPaddedCodec.decodeFromWire`, but the
         * receive integration is also pending.
         *
         * Stays out of [LOCAL_SUPPORTED_FEATURES] until both halves of the
         * integration ship — advertising support without honoring it
         * would silently break peers that DO honor it.
         */
        const val COMPRESSION_FEATURE: String = "compression-v1"
        /**
         * Route advertisements in HELLO (O31). When BOTH peers advertise this
         * flag, the HelloProof signs the v2 challenge bytes
         * ([helloChallengeBytesV2]) including the sender's
         * `recentlyExchangedWith` list. Stays out of
         * [LOCAL_SUPPORTED_FEATURES] until the per-handshake negotiation +
         * recent-exchange tracker is wired through. Sender-side population
         * also needs the per-contact opt-out mechanism (contacts marked
         * "don't list me" filter out before populating).
         */
        const val ROUTE_ADV_FEATURE: String = "route-adv-v1"
        /**
         * Capability flags this build advertises. Empty for v0.1 production —
         * RBSR ([RBSR_FEATURE]) is opt-in per session for now via the
         * `supportedFeatures` constructor parameter, until wire-locked against
         * the reference impl. Simulator scenarios override this to drive the
         * RBSR path; production transport leaves it empty so the bloom path
         * remains canonical.
         */
        val LOCAL_SUPPORTED_FEATURES: List<String> = listOf(COMPRESSION_FEATURE)
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
        /**
         * O76 / capability cache. The peer's HELLO `supportedFeatures` as
         * received in this session. Caller writes this into
         * `Contact.lastKnownSupportedFeatures` (JSON-encoded) so future
         * compose paths can gate on per-feature support without a fresh
         * handshake.
         */
        val peerSupportedFeatures: List<String> = emptyList(),
    )

    suspend fun run(): SessionResult? = withTimeout(SESSION_TIMEOUT_MS) {
        val startMs = System.currentTimeMillis()
        try {
            val out = DataOutputStream(socket.getOutputStream().buffered())
            val inp = DataInputStream(socket.getInputStream().buffered())

            // Phase 1: HELLO — exchange identities + challenge nonces + version bits
            val ourNonce = ByteArray(32).also { SecureRandom().nextBytes(it) }.toBase64()
            val ourHello = GossipPacket.Hello(
                userId = localUserId,
                publicKey = localPublicKey,
                nonce = ourNonce,
                protocolVersion = LOCAL_PROTOCOL_VERSION,
                maxProtocolVersion = LOCAL_MAX_PROTOCOL_VERSION,
                supportedFeatures = supportedFeatures,
            )
            send(out, ourHello)
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

            // Phase 1b: HELLO_PROOF — each side signs the other's nonce bound to
            // that side's claimed version bits. A downgrade-MITM that strips the
            // peer's supportedFeatures would invalidate the proof we verify below.
            val ourChallenge = helloChallengeBytes(
                nonceBase64 = hello.nonce,
                protocolVersion = ourHello.protocolVersion,
                maxProtocolVersion = ourHello.maxProtocolVersion,
                supportedFeatures = ourHello.supportedFeatures,
            )
            val ourProof = signer(ourChallenge)
                ?: run { RumorLog.w(TAG, "Local signer unavailable"); return@withTimeout null }
            send(out, GossipPacket.HelloProof(ourProof.toBase64()))
            val proof = receive(inp) as? GossipPacket.HelloProof
                ?: run { RumorLog.w(TAG, "Expected HELLO_PROOF"); return@withTimeout null }
            val proofBytes = try { proof.signature.fromBase64() }
                catch (e: Exception) { RumorLog.w(TAG, "Bad proof encoding"); return@withTimeout null }
            val peerChallenge = helloChallengeBytes(
                nonceBase64 = ourNonce,
                protocolVersion = hello.protocolVersion,
                maxProtocolVersion = hello.maxProtocolVersion,
                supportedFeatures = hello.supportedFeatures,
            )
            if (!CryptoManager.verify(peerChallenge, proofBytes, peerPubKeyBytes)) {
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

            // Phase 2: SUMMARY — exchange knowledge sets. Capability-gated:
            // RBSR (O42) replaces the bloom/idlist path when both peers advertise
            // `rbsr-v1` in HELLO AND we have an `rbsrItems` snapshot wired through.
            // Falls back to bloom/idlist on any precondition miss for clean
            // backwards compatibility with v0.1 peers.
            // v2 wins when both advertise it; v1 if both advertise v1; bloom otherwise.
            val useRbsrV2 = supportedFeatures.contains(RBSR_V2_FEATURE) &&
                hello.supportedFeatures.contains(RBSR_V2_FEATURE) &&
                rbsrItems != null
            val useRbsr = useRbsrV2 || (
                supportedFeatures.contains(RBSR_FEATURE) &&
                    hello.supportedFeatures.contains(RBSR_FEATURE) &&
                    rbsrItems != null
                )
            val rbsrFormula = if (useRbsrV2)
                com.rumor.mesh.core.sync.FingerprintFormula.V2_NIP77
            else
                com.rumor.mesh.core.sync.FingerprintFormula.V1_XOR

            val theyNeed: List<RumorMessage>
            val weNeedIds: List<String>
            val overlapFraction: Float

            if (useRbsr) {
                val rbsr = com.rumor.mesh.core.sync.Rbsr(
                    com.rumor.mesh.core.sync.SortedListRbsrStorage(rbsrItems!!, rbsrFormula),
                )
                val peerHas = HashSet<String>()
                val peerNeeds = HashSet<String>()
                var ourFrames = rbsr.initiate()
                for (round in 0 until com.rumor.mesh.core.sync.MAX_RBSR_ROUNDS) {
                    send(out, GossipPacket.Rbsr(ourFrames.map { it.toWire() }))
                    val incoming = receive(inp) as? GossipPacket.Rbsr
                        ?: return@withTimeout null
                    val r = rbsr.respond(incoming.frames.map { it.toMemory() })
                    peerHas.addAll(r.peerHas)
                    peerNeeds.addAll(r.peerNeeds)
                    if (ourFrames.isEmpty() && incoming.frames.isEmpty()) break
                    ourFrames = r.outgoing
                }
                theyNeed = messagesToOffer.filter { it.id in peerNeeds }
                weNeedIds = peerHas.toList()
                overlapFraction = if (messagesToOffer.isEmpty()) 0f
                else (messagesToOffer.size - theyNeed.size).toFloat() / messagesToOffer.size
            } else {
                // Legacy bloom/idlist path: send our summary, derive a `peerKnows`
                // predicate from theirs, and compute the offer side. Peer requests
                // what they need in a separate Request frame because they cannot
                // enumerate that just from our summary.
                send(out, buildSummary())
                // O13: a peer can send an adversarial bloom (expectedItems =
                // Int.MAX_VALUE) that would OOM the receiver. deserializeOrNull
                // returns null on OOM/IAE; we treat that as "peer's bloom is
                // unusable — assume they know nothing" and fall back to capped
                // flood. Receiver stays alive; sender over-offers this exchange.
                val peerKnows: (String) -> Boolean = when (val peerSummary = receive(inp)) {
                    is GossipPacket.IdList -> peerSummary.ids.toHashSet().let { set ->
                        { id: String -> id in set }
                    }
                    is GossipPacket.Bloom -> {
                        val f = BloomFilterData.deserializeOrNull(
                            peerSummary.filter, peerSummary.expectedItems,
                        )
                        if (f == null) {
                            RumorLog.w(TAG, "Peer bloom unusable (oversized?) — over-offering this exchange")
                            val none: (String) -> Boolean = { false }
                            none
                        } else {
                            val pred: (String) -> Boolean = { id -> f.mightContain(id) }
                            pred
                        }
                    }
                    else -> return@withTimeout null
                }
                overlapFraction = if (messagesToOffer.isEmpty()) 0f
                else messagesToOffer.count { peerKnows(it.id) }.toFloat() / messagesToOffer.size
                theyNeed = messagesToOffer.filter { msg -> !peerKnows(msg.id) }
                weNeedIds = emptyList()
            }

            // Phase 3: REQUEST — RBSR path knows what we need; bloom path doesn't
            // (we send empty, peer replies with what they think we need based on
            // our summary).
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
                peerSupportedFeatures = hello.supportedFeatures,
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
