package com.rumor.mesh.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * O79 — Room subscription persistence row.
 *
 * Mirrors the `RoomSubscription` core model:
 *  - `roomId` is the canonical room identifier (primary key — one
 *    row per subscription).
 *  - `mode` is the subscription mode enum, persisted as its `name`
 *    via the existing Converters' enum support (added below if not
 *    already covered).
 *  - `routingKey` is the 32-byte HKDF-derived routing key for
 *    ENCRYPTED rooms, persisted as a BLOB column. Empty for OPEN
 *    rooms.
 *  - `joinedAtMs` is wall-clock epoch ms; diagnostic only.
 *
 * **Decryption material is NOT stored here.** The user's X25519
 * static private comes from `IdentityManager`; this entity carries
 * only the routing material needed by `RoomTagMatcher`.
 *
 * Why a flat shape rather than JSON-blob (the pattern used by
 * `KeywordFilterListEntity`)? Subscription rows are queried by
 * `roomId` for the tag-matcher snapshot at every room-message
 * receive; flat columns let Room generate efficient queries
 * without a JSON-parse step. Schema is also stable (no nested
 * lists or maps), so flat fields are the natural choice.
 */
@Entity(tableName = "room_subscriptions")
data class RoomSubscriptionEntity(
    @PrimaryKey val roomId: String,
    val mode: String,            // "OPEN" or "ENCRYPTED" (enum name)
    val routingKey: ByteArray,   // 32 bytes for ENCRYPTED, empty for OPEN
    val joinedAtMs: Long,
) {
    override fun equals(other: Any?) = other is RoomSubscriptionEntity &&
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
