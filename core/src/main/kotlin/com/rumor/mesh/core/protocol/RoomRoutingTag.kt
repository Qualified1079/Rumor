package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.HkdfSha256
import com.rumor.mesh.core.crypto.HmacSha256
import com.rumor.mesh.core.platform.Sha256

/**
 * O79 — Routing tag derivation for room-addressed messages.
 *
 * **Purpose:** observers and seizing-a-relay-later adversaries
 * shouldn't be able to enumerate which Rooms a given message is
 * addressed to. Instead of carrying the plaintext roomId on the
 * wire, room messages carry an opaque routing tag.
 *
 * **Two derivations, one for each room mode:**
 *
 *  - **OPEN rooms** are publicly readable by definition. There is no
 *    shared secret an observer doesn't have — anyone with the public
 *    roomId can join. The routing tag is therefore a deterministic
 *    function of just the public roomId; an observer CAN compute
 *    the tag if they bother to enumerate roomIds and check, but the
 *    tag itself doesn't carry the plaintext roomId on the wire (so
 *    casual observers who don't actively enumerate see nothing).
 *    Implementation: `SHA-256("rumor-room-route-v1:" || roomId)`,
 *    truncated to 16 bytes for wire compactness.
 *
 *  - **ENCRYPTED rooms** (INVITE / CLOSED / PASSWORD) have a per-room
 *    routing key shared among members at join time (carried in the
 *    RoomInvite payload). The routing tag is
 *    `HMAC(routingKey, messageId)` — different bytes every message
 *    (per-message tag), so a relay holding old traffic can't even
 *    cluster messages from the same Room across time. Observers
 *    without the routing key can't compute the tag at all.
 *
 * **Why per-message tag (not per-room tag) for ENCRYPTED rooms?**
 *  Per-room would give relays a clustering signal ("all of these
 *  messages are to the same opaque room") which observers also pick
 *  up. Per-message means an observer literally cannot link a stream
 *  of messages to a single room without trial-decrypting each one
 *  with every key they hold. Maximum privacy at the cost of routing
 *  efficiency — accepted per the O79 design decision recorded in
 *  CLAUDE.md.
 *
 * **Tag length is 16 bytes (128 bits).** Enough to make collisions
 * statistically irrelevant for any plausible room count (~10^9 rooms
 * would still give ~2^-58 collision per message), small enough that
 * the wire overhead is minor.
 *
 * Domain tags `rumor-room-route-v1:` and `rumor-room-msg-tag-v1:`
 * are reserved forever per `docs/RENAMED_FIELDS_NEVER_REUSE.md`.
 */
object RoomRoutingTag {

    private const val OPEN_DOMAIN = "rumor-room-route-v1:"
    private const val ENCRYPTED_DOMAIN = "rumor-room-msg-tag-v1:"
    private const val TAG_LEN = 16

    /**
     * Routing tag for an OPEN room. Deterministic function of the
     * public roomId. Anyone enumerating roomIds can compute it; this
     * doesn't add a privacy property OPEN rooms didn't already lose
     * by being open. But it does keep the plaintext roomId off the
     * wire, so casual observers who aren't actively enumerating see
     * nothing meaningful.
     */
    fun openRoomTag(roomId: String): ByteArray {
        val full = Sha256.digest((OPEN_DOMAIN + roomId).encodeToByteArray())
        return full.copyOf(TAG_LEN)
    }

    /**
     * Per-message routing tag for an ENCRYPTED room. Observers without
     * the [routingKey] cannot compute the tag. Different messageIds
     * produce different tags, so a relay can't cluster messages from
     * the same room over time.
     *
     * @param routingKey Per-room secret distributed at invite/join
     *   time. Derived via [deriveEncryptedRoomRoutingKey] from the
     *   room key material so it's domain-separated from any per-room
     *   encryption keys.
     * @param messageId The RumorMessage.id this tag covers.
     */
    fun encryptedRoomTag(routingKey: ByteArray, messageId: String): ByteArray {
        val mac = HmacSha256.mac(routingKey, (ENCRYPTED_DOMAIN + messageId).encodeToByteArray())
        return mac.copyOf(TAG_LEN)
    }

    /**
     * Derive a routing-only key from any per-room shared secret
     * (e.g. an invite material seed). The HKDF info string
     * domain-separates the routing key from any other key that
     * might later be derived from the same secret — a future
     * content-encryption key derived from the same secret with a
     * different info string will be unrelated to this routing key.
     *
     * Length is 32 bytes — the HMAC key size is determined by the
     * underlying HMAC-SHA-256, which accepts any length but is
     * cleanest at the output size.
     */
    fun deriveEncryptedRoomRoutingKey(roomSharedSecret: ByteArray): ByteArray =
        HkdfSha256.deriveKey(
            salt = ByteArray(0),
            ikm = roomSharedSecret,
            info = "rumor-room-routing-key-v1".encodeToByteArray(),
            length = 32,
        )
}
