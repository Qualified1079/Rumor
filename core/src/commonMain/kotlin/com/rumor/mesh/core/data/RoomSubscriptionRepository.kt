package com.rumor.mesh.core.data

/**
 * O79 — Local persistence of the rooms this user is subscribed to.
 *
 * Pure interface. Impls live in:
 *  - `:app/data/adapter/RoomSubscriptionRepositoryAdapter.kt`     (Room/SQLite)
 *  - `:simulator/data/InMemoryRoomSubscriptionRepository`         (in-memory stub)
 *
 * **Conceptual model:** the user is subscribed to zero or more
 * [RoomSubscription] records. Each subscription pins (a) the
 * canonical roomId and (b) the membership-derived material the
 * receiver needs to recognize and decrypt inbound traffic.
 *
 *  - For OPEN rooms: subscription stores only the public roomId.
 *    The routing tag is derivable from roomId via
 *    `RoomRoutingTag.openRoomTag(roomId)`; the receiver doesn't
 *    need any secret to match inbound traffic.
 *
 *  - For ENCRYPTED rooms: subscription stores the per-room
 *    routing key (32 bytes from
 *    `RoomRoutingTag.deriveEncryptedRoomRoutingKey`) AND the
 *    user's own X25519 static private key indirectly — actually
 *    the static private is owned by `IdentityManager`, not by
 *    this repo. The subscription stores routing material only;
 *    decryption material is the identity layer's job.
 *
 * The receiver-side `RoomTagMatcher` consumes a snapshot of all
 * subscriptions to match inbound tags. Typically the cache is
 * read into memory once at startup + refreshed on every join/
 * unsubscribe; per-inbound-message lookups are O(1) against the
 * cache and don't hit storage.
 *
 * Membership state for each Room is a SEPARATE concern (covered
 * by the events-derived membership projection described in the
 * O79 row); this repository tracks "am I in this room?" not "who
 * else is in this room?".
 */
interface RoomSubscriptionRepository {

    suspend fun upsert(subscription: RoomSubscription)

    suspend fun delete(roomId: String)

    suspend fun get(roomId: String): RoomSubscription?

    /**
     * Snapshot of all current subscriptions. Caller (typically
     * GossipEngine on-startup + on-mutation) wraps the result for
     * the receiver-side `RoomTagMatcher`.
     */
    suspend fun getAll(): List<RoomSubscription>
}

/**
 * One subscription record. The [routingKey] field is meaningful
 * only for ENCRYPTED rooms; OPEN room subs leave it empty.
 *
 * @property roomId Canonical roomId. Always present.
 * @property mode Distinguishes OPEN from ENCRYPTED at lookup time
 *   so the matcher knows which derivation to apply.
 * @property routingKey 32-byte per-room routing key for ENCRYPTED
 *   rooms; empty byte array for OPEN rooms. Derived from the room's
 *   shared seed via `RoomRoutingTag.deriveEncryptedRoomRoutingKey`
 *   at join time; never re-derived (the seed itself is not retained
 *   to bound forward-secrecy exposure).
 * @property joinedAtMs Wall-clock epoch ms the user joined. Diagnostic
 *   only; not used in matching or routing decisions.
 */
data class RoomSubscription(
    val roomId: String,
    val mode: RoomSubscriptionMode,
    val routingKey: ByteArray,
    val joinedAtMs: Long,
) {
    init {
        when (mode) {
            RoomSubscriptionMode.OPEN -> require(routingKey.isEmpty()) {
                "OPEN room subscriptions must not carry a routing key"
            }
            RoomSubscriptionMode.ENCRYPTED -> require(routingKey.size == 32) {
                "ENCRYPTED room subscriptions require a 32-byte routing key (got ${routingKey.size})"
            }
        }
    }

    override fun equals(other: Any?) = other is RoomSubscription &&
        other.roomId == roomId &&
        other.mode == mode &&
        other.routingKey.contentEquals(routingKey) &&
        other.joinedAtMs == joinedAtMs

    override fun hashCode(): Int {
        var h = roomId.hashCode()
        h = h * 31 + mode.hashCode()
        h = h * 31 + routingKey.contentHashCode()
        h = h * 31 + joinedAtMs.hashCode()
        return h
    }
}

enum class RoomSubscriptionMode {
    /** Public room — routing tag is a deterministic function of roomId. */
    OPEN,
    /**
     * Invite/closed/password room — routing tag requires the
     * per-room routing key derived at join time. Cipher decryption
     * uses the user's X25519 static private from `IdentityManager`.
     */
    ENCRYPTED,
}
