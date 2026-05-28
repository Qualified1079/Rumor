package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire protocol frames exchanged between two nodes during a gossip session.
 *
 * Forward-compat policy: every top-level frame carries a reserved `ext` field
 * (rendered as `_ext` on the wire) that v0.2+ uses to carry additive fields
 * without bumping `protocolVersion`. A v0.1 node treats `ext` as opaque and
 * preserves it through any copy/relay; a v0.2 node reads or writes named keys
 * inside it. Fields placed inside `ext` are NOT covered by any frame-level
 * signature — they must either self-authenticate or be non-security-affecting
 * routing/diagnostic data. See `WireJson` for the JSON config.
 */
@Serializable
sealed class GossipPacket {

    /**
     * Opening handshake. Carries claimed identity plus a random nonce that the
     * peer must sign to prove ownership of [publicKey] (see [HelloProof]).
     *
     * Version negotiation lives entirely inside the signed challenge: the
     * proof signature covers [protocolVersion], [maxProtocolVersion], and
     * [supportedFeatures] via [helloChallengeBytes]. A MITM cannot strip
     * version bits to coerce a downgrade without invalidating the proof.
     * Session uses `min(mine, theirs)` against [maxProtocolVersion].
     */
    @Serializable @SerialName("hello")
    data class Hello(
        val userId: String,
        val publicKey: String,
        /** Base64 random bytes the peer signs in their [HelloProof]. */
        val nonce: String,
        /** Wire-format version this client is sending. */
        val protocolVersion: Int = 1,
        /** Highest wire-format version this client can parse. Session uses `min(mine, theirs)`. */
        val maxProtocolVersion: Int = 1,
        /**
         * Capability flags. Each flag gates optional behavior; absence means
         * the feature is not advertised. Nostr-style additive feature detection.
         */
        val supportedFeatures: List<String> = emptyList(),
        @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
    ) : GossipPacket()

    /**
     * Proof of [Hello.publicKey] ownership.
     * [signature] = Ed25519 sign over the bytes returned by [helloChallengeBytes]
     * — i.e. a domain-tagged binding of the peer's nonce plus the version bits.
     */
    @Serializable @SerialName("hello_proof")
    data class HelloProof(
        val signature: String,
        @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
    ) : GossipPacket()

    /** Bloom filter representing this node's known message IDs (Base64-encoded). */
    @Serializable @SerialName("bloom")
    data class Bloom(
        val filter: String,
        val expectedItems: Int,
        @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
    ) : GossipPacket()

    /**
     * Raw list of known message IDs. Used in place of [Bloom] when the set is small
     * enough that exact membership beats bloom on size and gives zero false positives.
     * Sender chooses based on a size threshold; receiver handles either form.
     */
    @Serializable @SerialName("id_list")
    data class IdList(
        val ids: List<String>,
        @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
    ) : GossipPacket()

    /** Request specific messages by ID. */
    @Serializable @SerialName("request")
    data class Request(
        val messageIds: List<String>,
        @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
    ) : GossipPacket()

    /** A single message being transferred. */
    @Serializable @SerialName("message")
    data class Message(
        val message: RumorMessage,
        @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
    ) : GossipPacket()

    /**
     * Lightweight delivery acknowledgement sent after the MESSAGE phase.
     * Lists the message IDs the receiver actually accepted and ingested in this
     * session. Only confirms peer-hop delivery (the message left this device and
     * was accepted by a direct peer), not end-to-end delivery to the final recipient.
     */
    @Serializable @SerialName("ack")
    data class Ack(
        val acceptedIds: List<String>,
        @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
    ) : GossipPacket()

    /**
     * Recently-seen user IDs shared during gossip exchange.
     * Key = User ID, Value = wall-clock epoch ms when that user was last seen.
     * Devices use their hardware RTC; small clock skew is acceptable for the
     * "online within the last N minutes" granularity this map drives.
     */
    @Serializable @SerialName("online_status")
    data class OnlineStatus(
        val recentUsers: Map<String, Long>,
        @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
    ) : GossipPacket()

    /** Graceful disconnect signal. */
    @Serializable @SerialName("bye")
    data class Bye(
        val reason: String = "",
        @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
    ) : GossipPacket()

    /**
     * Compact representation of which messages this node already knows.
     * Exchanged after [HelloProof] so each side can skip messages the other
     * already has when selecting relay targets in future exchanges.
     *
     * The receiver uses this to compute an overlap fraction: the fraction of
     * its own known messages that the sender's bloom filter also contains.
     * A low overlap → this peer extends our reach; a high overlap → they are
     * already well-informed via other paths.
     *
     * [filter] is the Base64-encoded bloom filter bytes (same encoding as
     * [Bloom]). [expectedItems] drives the bloom reconstruction.
     */
    @Serializable @SerialName("neighbor_digest")
    data class NeighborDigest(
        val filter: String,
        val expectedItems: Int,
        @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
    ) : GossipPacket()
}

/**
 * Domain-tagged challenge bytes a peer signs to prove ownership of their public
 * key. Covers the nonce *and* the version-negotiation fields so a downgrade-MITM
 * can't strip version bits without invalidating the proof (TLS 1.3 lesson).
 *
 * Field order is fixed; never reorder without bumping the `rumor-hello-vN:` tag.
 */
fun helloChallengeBytes(
    nonceBase64: String,
    protocolVersion: Int = 1,
    maxProtocolVersion: Int = 1,
    supportedFeatures: List<String> = emptyList(),
): ByteArray = buildString {
    append("rumor-hello-v1:")
    append(nonceBase64)
    append('|')
    append(protocolVersion)
    append('|')
    append(maxProtocolVersion)
    append('|')
    append(supportedFeatures.sorted().joinToString(","))
}.toByteArray(Charsets.UTF_8)
