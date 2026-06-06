package com.rumor.mesh.core.model

import com.rumor.mesh.core.SystemClock
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
    val receivedAtMs: Long = SystemClock.now(),
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
 * O64: ordering / "X ago" timestamp that callers should use instead of [RumorMessage.sentAtMs].
 *
 * `sentAtMs` is sender-asserted and trivially forgeable. A malicious sender forging a
 * far-future timestamp pins their message to the top of any list ordered by sentAtMs;
 * a forged past timestamp hides their message from sorted views. `receivedAtMs` is what
 * this node actually witnessed and cannot be forged by the sender. Taking the min gives:
 *  - honest senders unaffected (sentAtMs ≤ receivedAtMs in the normal case)
 *  - future-forging senders capped at receive time (no pinning attack)
 *  - past-forging senders ordered by their own claim, which is what they wanted (a
 *    sender hiding their own message is not a security problem worth defending against)
 *
 * This is the value UI sort orders and "X ago" labels should consume. Protocol behaviour
 * (eviction, dedup TTL, retry timers) MUST use `receivedAtMs` or monotonic deltas — never
 * `sentAtMs` and never `displayTimeMs`.
 */
val RumorMessage.displayTimeMs: Long
    get() = minOf(sentAtMs, receivedAtMs)

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
    /**
     * Receiver-originated cancel for an in-flight chunked transfer (O18).
     * Routed as a DM to the original sender; on receipt the sender stops
     * responding to chunk-requests for the named transferId and the
     * transfer record is marked ABANDONED on both sides.
     */
    @SerialName("transfer_cancel") TRANSFER_CANCEL,
    /**
     * Signed full keyword-filter list snapshot from a publisher (O67).
     * Subscribers verify the publisher's Ed25519 signature, apply
     * monotonic version check, store. Display-layer filter — relay path
     * is unaffected. Snapshot-only in v1 (no diff variant); list churn
     * is expected to be low so the bandwidth cost of full snapshots is
     * acceptable. Payload is a JSON-serialized [KeywordFilterList].
     */
    @SerialName("keyword_filter_publish") KEYWORD_FILTER_PUBLISH,
    /**
     * Signed delete-on-ACK request (O40). Recipient (or original sender)
     * of a DIRECT message authorizes relays to purge the named messageId
     * from their stores, tightening the forward-secrecy exposure window
     * for stored ciphertext. Relays honor the request if (a) the signing
     * sender matches the original DM's `senderId` OR matches its
     * `recipientId`, AND (b) the signature verifies against the
     * stated `senderPublicKey`. Payload is a JSON-serialized
     * [com.rumor.mesh.core.model.MessageDeletePayload]. The deleted
     * messageId is kept in the dedup set so a future replay can't
     * re-ingest it.
     */
    @SerialName("message_delete") MESSAGE_DELETE,
    /**
     * Signed short-lived X25519 prekey broadcast (O38). Receiver
     * publishes fresh prekeys for receiver-side forward secrecy.
     * Senders cache the freshest valid prekey for each known
     * contact and DH against it instead of the long-term static —
     * a relay holding stored ciphertext for an expired prekey can
     * no longer decrypt even if the recipient's long-term key
     * later leaks. Payload is a JSON-serialized
     * [com.rumor.mesh.core.model.PrekeyPublish].
     */
    @SerialName("prekey_publish") PREKEY_PUBLISH,
    /**
     * Message addressed to a Room (O79). The wire wrapper carries no
     * plaintext roomId — addressing is via an opaque routing tag in
     * `_ext.rt` (computed by `RoomRoutingTag.openRoomTag` for OPEN
     * rooms, or `RoomRoutingTag.encryptedRoomTag` for ENCRYPTED rooms).
     * Observers without the appropriate key cannot enumerate roomIds.
     *
     * **Payload shape:**
     *  - OPEN rooms: `payload.content` carries plaintext (signed but
     *    not encrypted — OPEN rooms are publicly readable by
     *    definition).
     *  - ENCRYPTED rooms: `encryptedPayload` carries a JSON-serialized
     *    [com.rumor.mesh.core.model.MultiRecipientEnvelope] — one
     *    AES-encrypted body + N per-recipient key wraps. Decrypted via
     *    [com.rumor.mesh.core.protocol.MultiRecipientEnvelopeCodec.decrypt].
     *
     * **Trust + routing:** the outer `RumorMessage.signature` is the
     * sender's Ed25519 over the standard signableBytes. For ENCRYPTED
     * rooms, the inner envelope ALSO has its own Ed25519 signature
     * covering the recipient list — relays cannot extend/trim/permute
     * recipients without breaking the inner signature even if they
     * could somehow re-sign the outer.
     *
     * trafficClass routing follows BROADCAST/DIRECT (TEXT → REALTIME,
     * media → BULK, CONTROL → INFRASTRUCTURE).
     */
    @SerialName("room_message") ROOM_MESSAGE,
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
            MessageType.TRANSFER_CANCEL,
            MessageType.BLOCKLIST_DIFF,
            MessageType.PRIORITY_LINK_REQUEST,
            MessageType.PRIORITY_LINK_ACCEPT,
            MessageType.SELF_PRESENCE,
            MessageType.MESSAGE_DELETE -> TrafficClass.INFRASTRUCTURE
            // A full blocklist snapshot is bulky sync data, not handshake-tier
            // traffic — only the small incremental diff stays INFRASTRUCTURE.
            MessageType.TRANSFER_METADATA,
            MessageType.BLOCKLIST_PUBLISH,
            MessageType.KEYWORD_FILTER_PUBLISH,
            MessageType.PREKEY_PUBLISH -> TrafficClass.TRANSFER_SETUP
            MessageType.CHUNK             -> TrafficClass.BULK
            MessageType.BROADCAST,
            MessageType.DIRECT,
            MessageType.BRIDGE_VOUCHED,
            MessageType.ROOM_MESSAGE -> when (payload?.contentType) {
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
