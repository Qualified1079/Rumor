package com.rumor.mesh.core.protocol

/**
 * O79 — Match an inbound room routing tag against the set of rooms a
 * receiver is subscribed to.
 *
 * Receivers maintain a small set of known subscriptions:
 *  - For OPEN rooms: just the public roomId — the precomputed tag is
 *    a deterministic function of it.
 *  - For ENCRYPTED rooms: the routing key derived from the room's
 *    shared secret at invite time. Per-message tags vary, so the
 *    receiver must recompute against each subscribed key.
 *
 * On every inbound message with type ROOM_MESSAGE (when wired into
 * GossipEngine), the receiver pulls the routing tag and asks: "is
 * this addressed to a room I'm in?"
 *
 *  - OPEN matching: byte-compare the inbound tag against
 *    [RoomRoutingTag.openRoomTag] of each subscribed open roomId.
 *    O(open-room-count) byte compares, each 16 bytes — trivially
 *    cheap.
 *
 *  - ENCRYPTED matching: byte-compare against
 *    [RoomRoutingTag.encryptedRoomTag] of each subscribed encrypted
 *    routing key + the inbound message's id. O(encrypted-room-count)
 *    HMAC-SHA-256 operations. Microseconds per op; at typical
 *    subscription counts (dozens of rooms), total under 1ms per
 *    inbound message.
 *
 * Returns the matched roomId on success, or null if no subscribed
 * room matches (drop the message in that case — addressed to a
 * room we're not in).
 *
 * Pure function — no state, no IO. The subscription lists are
 * passed in from the caller's own storage (a `RoomSubscription`
 * repository when it lands).
 */
object RoomTagMatcher {

    /**
     * Inbound tag is a raw [ByteArray] (16 bytes — the length the
     * routing-tag derivations produce). For wire transport the tag
     * would typically be Base64-encoded; the caller decodes before
     * passing in.
     */
    fun match(
        inboundTag: ByteArray,
        messageId: String,
        openSubscriptions: Collection<String>,
        encryptedSubscriptions: Collection<EncryptedRoomSubscription>,
    ): MatchResult? {
        // OPEN check is cheaper (no HMAC, just one SHA-256 per
        // subscription); do it first.
        for (roomId in openSubscriptions) {
            val expected = RoomRoutingTag.openRoomTag(roomId)
            if (expected.contentEquals(inboundTag)) {
                return MatchResult.OpenMatch(roomId)
            }
        }
        for (sub in encryptedSubscriptions) {
            val expected = RoomRoutingTag.encryptedRoomTag(sub.routingKey, messageId)
            if (expected.contentEquals(inboundTag)) {
                return MatchResult.EncryptedMatch(sub.roomId)
            }
        }
        return null
    }

    /** Subscription to an ENCRYPTED room — the routing key is what's needed for tag derivation. */
    data class EncryptedRoomSubscription(
        val roomId: String,
        /** 32-byte routing key from [RoomRoutingTag.deriveEncryptedRoomRoutingKey]. */
        val routingKey: ByteArray,
    ) {
        override fun equals(other: Any?) = other is EncryptedRoomSubscription &&
            other.roomId == roomId &&
            other.routingKey.contentEquals(routingKey)
        override fun hashCode(): Int = roomId.hashCode() * 31 + routingKey.contentHashCode()
    }

    sealed class MatchResult {
        abstract val roomId: String
        data class OpenMatch(override val roomId: String) : MatchResult()
        data class EncryptedMatch(override val roomId: String) : MatchResult()
    }
}
