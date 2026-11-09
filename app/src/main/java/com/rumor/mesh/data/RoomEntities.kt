package com.rumor.mesh.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * O187 — persisted [com.rumor.mesh.core.data.RoomRecord]. Policies are stored
 * as enum names (renaming a `RoomMembershipPolicy`/`RoomPostingPolicy` value
 * breaks existing rows, same as every other enum column here).
 */
@Entity(tableName = "rooms")
data class RoomEntity(
    @PrimaryKey val roomId: String,
    val name: String,
    val creatorId: String,
    val createdAtMs: Long,
    val membershipPolicy: String,
    val postingPolicy: String,
    val allowMedia: Boolean,
)

/**
 * One row per (room, user, role) — moderators, members and bans share the
 * table so a roster change is a single-row write. No foreign key to [RoomEntity]
 * on purpose: rooms upsert via REPLACE (delete + insert), and a cascading FK
 * would wipe the roster every time a CREATE is re-delivered.
 */
@Entity(
    tableName = "room_roster",
    primaryKeys = ["roomId", "userId", "role"],
    indices = [Index("roomId")],
)
data class RoomRosterEntity(
    val roomId: String,
    val userId: String,
    val role: String,
) {
    companion object {
        const val MODERATOR = "MODERATOR"
        const val MEMBER = "MEMBER"
        const val BANNED = "BANNED"
    }
}
