package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire-format message — exactly what travels between nodes. */
@Serializable
data class RumorMessage(
    /** 128-bit random ID, hex-encoded. Used for duplicate suppression. */
    val id: String,
    /** Sender's User ID (hex fingerprint of their Ed25519 public key). */
    val senderId: String,
    /** Sender's raw Ed25519 public key (Base64). Included so recipients can verify without a prior key exchange. */
    val senderPublicKey: String,
    /** Per-sender monotonic counter. Used for ordering messages from the same sender. */
    val sequenceNumber: Long,
    /** Wall-clock epoch ms when the originating device composed the message. Set once; relays do not modify. */
    val sentAtMs: Long,
    val type: MessageType,
    /** Remaining hops. Decremented at each relay; message dies at zero. Applies to BROADCAST and DIRECT. */
    val hopsToLive: Int,
    /** Plaintext for BROADCAST; absent for DIRECT (use [encryptedPayload]). */
    val payload: MessagePayload? = null,
    /** AES-256-GCM ciphertext for DIRECT messages. */
    val encryptedPayload: String? = null,
    /** Recipient User ID — present only for DIRECT messages. */
    val recipientId: String? = null,
    /** Ed25519 signature over all other fields (Base64). */
    val signature: String,
    /**
     * Reserved forward-compat carrier. v0.1 ignores; v0.2+ uses for additive
     * fields without bumping `protocolVersion`. NOT covered by [signature] —
     * any security-relevant value placed here must self-authenticate. Survives
     * relay because `data class.copy()` preserves it through `decrementHops`.
     */
    @SerialName("_ext")
    val ext: Map<String, JsonElement>? = null,
    /**
     * How this node established trust in the message. Set by [GossipEngine] from
     * the ingress transport — never travels on the wire, so a peer cannot assert
     * its own trust level.
     */
    @kotlinx.serialization.Transient
    val trustLevel: TrustLevel = TrustLevel.VERIFIED,
    /** Unix epoch millis when this node first saw the message. Not propagated. */
    @kotlinx.serialization.Transient
    val receivedAtMs: Long = System.currentTimeMillis(),
    /** Whether the local user has read this message. Not propagated. */
    @kotlinx.serialization.Transient
    val isRead: Boolean = false,
    /** Whether the local user manually relayed this (reset hops-to-live). Not propagated. */
    @kotlinx.serialization.Transient
    val wasRelayed: Boolean = false,
    /**
     * O29 per-peer routing marker. When non-null, this message is targeted to
     * specific peers (chosen by breadcrumb match at relay time) and the
     * transport's `messagesForExchange(peerUserId)` filters out peers not in
     * this set. Null means "broadcast, offer to anyone" (the default for
     * non-DMs and for DMs without a breadcrumb match). Transient on purpose —
     * intended peers are a local routing decision, not part of the wire frame,
     * and would leak the routing graph to in-mesh observers if shipped.
     */
    @kotlinx.serialization.Transient
    val intendedPeers: Set<String>? = null,
)

/**
 * O32 split-TTL helpers. `floodedHops` decrements only when the relay falls
 * back to flood (no breadcrumb match for this hop); `routedHops` counts
 * confirmed-route hops separately for diagnostics and the hard ceiling.
 * Stored inside the O37-reserved `_ext` map so v0.1 ignores them gracefully.
 *
 * Default reads when `_ext` is absent: `floodedHops = hopsToLive` (legacy
 * behaviour), `routedHops = 0`. This preserves bit-exact compatibility for
 * any pre-O32 message that hits a post-O32 node.
 */
val RumorMessage.routedHops: Int
    get() = (ext?.get("routedHops") as? kotlinx.serialization.json.JsonPrimitive)
        ?.content?.toIntOrNull() ?: 0

val RumorMessage.floodedHops: Int
    get() = (ext?.get("floodedHops") as? kotlinx.serialization.json.JsonPrimitive)
        ?.content?.toIntOrNull() ?: hopsToLive

/** Hard ceiling on total path length regardless of routed/flooded split (O32). */
const val MAX_TOTAL_HOPS: Int = 30

fun RumorMessage.withTtlSplit(routedHops: Int, floodedHops: Int): RumorMessage {
    val updated = (ext ?: emptyMap()).toMutableMap().apply {
        this["routedHops"] = kotlinx.serialization.json.JsonPrimitive(routedHops)
        this["floodedHops"] = kotlinx.serialization.json.JsonPrimitive(floodedHops)
    }
    return copy(ext = updated)
}

/**
 * Per-hop trust in a message, decided locally from the transport it arrived on.
 * Deliberately not [Serializable] — trust is never carried in the wire format.
 */
enum class TrustLevel {
    /** [RumorMessage.signature] was verified against the sender's Ed25519 key. */
    VERIFIED,
    /**
     * Bridge-vouched content (O17). The OUTER Rumor signature on this message
     * is by a bridge node's long-term key — that's verified. The INNER payload
     * is from a foreign network (Meshtastic, MeshCore, etc.) and the bridge
     * vouches only for *having received* it, never for content authenticity.
     * Unlike [BRIDGED], BRIDGE_VOUCHED messages relay normally so the bridge's
     * reach extends beyond its direct peers. The display layer must surface
     * the via-bridge framing explicitly so users don't conflate it with
     * native Rumor authenticity (O47).
     */
    BRIDGE_VOUCHED,
    /**
     * Carried in from a non-Rumor network by a local bridge plugin; outer
     * signature is `BRIDGE_UNSIGNED` sentinel. Never re-relayed onto the
     * signed mesh — a BRIDGED message reaches only its bridge's direct peers.
     * Use [BRIDGE_VOUCHED] for cross-mesh propagation.
     */
    BRIDGED,
}

@Serializable
enum class MessageType {
    @SerialName("broadcast")         BROADCAST,
    @SerialName("direct")            DIRECT,
    @SerialName("ping")              PING,
    @SerialName("pong")              PONG,
    /** Announces a chunked transfer: metadata, chunk count, content hash. */
    @SerialName("transfer_metadata") TRANSFER_METADATA,
    /** A single chunk of a larger transfer payload. */
    @SerialName("chunk")             CHUNK,
    /** NACK: request re-transmission of specific missing chunks. Routed as DM to original sender. */
    @SerialName("chunk_request")     CHUNK_REQUEST,
    /** Signed full blocklist snapshot from a publisher. Broadcast through the mesh. */
    @SerialName("blocklist_publish") BLOCKLIST_PUBLISH,
    /** Signed incremental blocklist diff from a publisher. Broadcast through the mesh. */
    @SerialName("blocklist_diff")    BLOCKLIST_DIFF,
    /** Request a persistent priority link with the recipient. Routed as DM. */
    @SerialName("priority_link_request") PRIORITY_LINK_REQUEST,
    /** Accept an incoming priority link request. Routed as DM back to originator. */
    @SerialName("priority_link_accept")  PRIORITY_LINK_ACCEPT,
    /**
     * Signed announcement that the sender is migrating from an old identity
     * (userId / Ed25519 key) to a new one. The outer [RumorMessage.signature]
     * is by the *new* key (so existing relay-layer signature checks still
     * verify against [RumorMessage.senderPublicKey]); the inner
     * [IdentityRotationPayload.continuitySignature] is by the *old* key and
     * proves the rotation is authorized (old-key holder consents). Recipients
     * holding [IdentityRotationPayload.oldUserId] as a contact rebind to the
     * new userId locally. See O41.
     */
    @SerialName("identity_rotation") IDENTITY_ROTATION,
    /**
     * Self-presence beacon (O30 + O57). Sender declares its current [UserMode]
     * to the mesh — entry-pulse on mode-up (going Static/Free), exit-pulse on
     * mode-down (back to Mobile). Mesh peers consume this to weight the sender
     * as a routing anchor (Static, Free) or drop the anchor weight immediately
     * (exit pulse). Self-only: a node beacons its OWN mode; never relays
     * someone else's mode for them.
     */
    @SerialName("self_presence") SELF_PRESENCE,
    /**
     * Bridge-vouched cross-network content (O17). Outer Rumor signature by a
     * bridge node certifies "I received this content from {originNetwork}".
     * Recipients verify the outer sig, set [TrustLevel.BRIDGE_VOUCHED], and
     * allow normal relay so the bridge's reach extends beyond direct peers.
     * Payload is a [BridgeVouchedPayload].
     */
    @SerialName("bridge_vouched") BRIDGE_VOUCHED,
}

@Serializable
data class MessagePayload(
    val contentType: ContentType,
    /** Text content (for TEXT type) or Base64-encoded binary (IMAGE, VOICE, FILE). */
    val content: String,
    /** Original filename — present for FILE type. */
    val filename: String? = null,
    /** MIME type hint. */
    val mimeType: String? = null,
    /** Byte size of the original content before Base64. */
    val sizeBytes: Long = 0,
)

@Serializable
enum class ContentType {
    @SerialName("text")    TEXT,
    @SerialName("image")   IMAGE,
    @SerialName("voice")   VOICE,
    @SerialName("file")    FILE,
    /** Protocol control payloads (CHUNK_REQUEST, etc.). Not displayed in UI. */
    @SerialName("control") CONTROL,
}

/**
 * Priority tier used by the outbound scheduler. Higher tiers preempt lower ones;
 * within a tier, deficit round robin across sources keeps any single peer from
 * starving the rest.
 *
 * A message's class is derived from its type and content via [trafficClass] —
 * it is never read off the wire, so it cannot be spoofed.
 */
@Serializable
enum class TrafficClass {
    /** Routing info, handshakes, blocklist updates. */
    @SerialName("infrastructure") INFRASTRUCTURE,
    /** Text and direct messages. */
    @SerialName("realtime")       REALTIME,
    /** Transfer metadata, thumbnails, contact sync, bloom filters. */
    @SerialName("transfer_setup") TRANSFER_SETUP,
    /** Image, voice, video, file chunks. */
    @SerialName("bulk")           BULK,
}

/**
 * Payload-size ceilings (approx bytes) per class. A message may *claim* a
 * high-priority type, but it cannot also be large: anything over its class's
 * ceiling is forced down to BULK by [trafficClass]. This makes a custom client
 * mislabelling a bulky payload upward pointless — and mislabelling a *small*
 * payload upward is harmless, since small messages cost almost nothing in the
 * byte-fair scheduler.
 *
 * Ceilings are generous relative to legitimate traffic: real PING/PONG, chunk
 * requests and blocklist diffs are a few KB; a long text is well under 16 KB;
 * transfer metadata with a thumbnail is tens of KB. Session-layer exchanges
 * (handshakes, bloom/cache digests) are [GossipPacket]s, not [RumorMessage]s,
 * so they never pass through here.
 */
private const val INFRASTRUCTURE_CEILING_BYTES = 16 * 1024
private const val REALTIME_CEILING_BYTES       = 16 * 1024
private const val TRANSFER_SETUP_CEILING_BYTES = 256 * 1024

/**
 * Traffic class for this message, derived from its [type], payload content
 * type, and size.
 *
 * Derived rather than carried on the wire on purpose: a sender cannot mislabel
 * a bulky video as high-priority to jump the queue — the class always reflects
 * what the message actually is. DIRECT messages are always text DMs in this
 * protocol (media goes through the chunked-transfer path), so they map to
 * REALTIME.
 */
val RumorMessage.trafficClass: TrafficClass
    get() {
        val base = when (type) {
            MessageType.PING,
            MessageType.PONG,
            MessageType.CHUNK_REQUEST,
            MessageType.BLOCKLIST_DIFF,
            MessageType.PRIORITY_LINK_REQUEST,
            MessageType.PRIORITY_LINK_ACCEPT,
            MessageType.IDENTITY_ROTATION,
            MessageType.SELF_PRESENCE -> TrafficClass.INFRASTRUCTURE
            // A full blocklist snapshot is bulky sync data, not handshake-tier
            // traffic — only the small incremental diff stays INFRASTRUCTURE.
            MessageType.TRANSFER_METADATA,
            MessageType.BLOCKLIST_PUBLISH -> TrafficClass.TRANSFER_SETUP
            MessageType.CHUNK             -> TrafficClass.BULK
            MessageType.BROADCAST,
            MessageType.DIRECT,
            MessageType.BRIDGE_VOUCHED -> when (payload?.contentType) {
                ContentType.IMAGE, ContentType.VOICE, ContentType.FILE -> TrafficClass.BULK
                ContentType.CONTROL                                    -> TrafficClass.INFRASTRUCTURE
                ContentType.TEXT, null                                 -> TrafficClass.REALTIME
            }
        }
        val ceiling = when (base) {
            TrafficClass.INFRASTRUCTURE -> INFRASTRUCTURE_CEILING_BYTES
            TrafficClass.REALTIME       -> REALTIME_CEILING_BYTES
            TrafficClass.TRANSFER_SETUP -> TRANSFER_SETUP_CEILING_BYTES
            TrafficClass.BULK           -> return TrafficClass.BULK
        }
        return if (approxPayloadBytes > ceiling) TrafficClass.BULK else base
    }

/** Approximate payload byte cost — content + ciphertext, matching the scheduler's costing. */
private val RumorMessage.approxPayloadBytes: Int
    get() = (payload?.content?.length ?: 0) + (encryptedPayload?.length ?: 0)
