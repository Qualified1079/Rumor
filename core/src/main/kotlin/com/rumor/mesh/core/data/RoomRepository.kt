package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.RoomMembershipPolicy
import com.rumor.mesh.core.model.RoomPostingPolicy

/**
 * O187 — persisted room metadata and roster.
 *
 * Distinct from [RoomSubscriptionRepository], which holds only the *local*
 * user's own routing/decrypt material ("am I in this room, and with what
 * key?"). This holds what the room IS: its policies, who moderates it, who is
 * a member, who is banned. O89's write-authority check needs exactly this and
 * had nothing to read from, which is why `RoomPostingCertVerifier` deferred the
 * moderator lookup to "a RoomRepository once that interface is shipped".
 *
 * Moderator and member sets are kept as explicit relations rather than fields
 * on [RoomRecord] so a single membership change is one row write, not a
 * read-modify-write of the whole room (O148/O149 will add authority *levels*
 * and an op-log on top; this is deliberately the flat shape they refine, not a
 * guess at their model).
 */
interface RoomRepository {

    suspend fun upsert(room: RoomRecord)

    suspend fun get(roomId: String): RoomRecord?

    suspend fun getAll(): List<RoomRecord>

    suspend fun delete(roomId: String)

    // ── Moderators ───────────────────────────────────────────────────────────

    suspend fun addModerator(roomId: String, userId: String)

    suspend fun removeModerator(roomId: String, userId: String)

    suspend fun isModerator(roomId: String, userId: String): Boolean

    suspend fun moderatorsOf(roomId: String): Set<String>

    // ── Membership ───────────────────────────────────────────────────────────

    suspend fun addMember(roomId: String, userId: String)

    suspend fun removeMember(roomId: String, userId: String)

    suspend fun isMember(roomId: String, userId: String): Boolean

    suspend fun membersOf(roomId: String): Set<String>

    // ── Bans ─────────────────────────────────────────────────────────────────

    /**
     * Bans are separate from "not a member" because they must survive removal
     * and outlive a rejoin attempt — a kick is reversible by re-invite, a ban
     * is not (O152 unifies kick/ban/timeout under one Revoke op later).
     */
    suspend fun addBan(roomId: String, userId: String)

    suspend fun removeBan(roomId: String, userId: String)

    suspend fun isBanned(roomId: String, userId: String): Boolean

    suspend fun bansOf(roomId: String): Set<String>
}

/**
 * Persisted projection of a [com.rumor.mesh.core.model.RoomCreate] — the room's
 * own declared shape, after its creator signature has been verified.
 *
 * `creatorId` is retained because the creator is a moderator by construction
 * and that fact must survive every later roster edit: a room whose mod list is
 * emptied by a malicious or buggy action would otherwise become permanently
 * ungovernable.
 */
data class RoomRecord(
    val roomId: String,
    val name: String,
    val creatorId: String,
    val createdAtMs: Long,
    val membershipPolicy: RoomMembershipPolicy,
    val postingPolicy: RoomPostingPolicy,
    val allowMedia: Boolean,
)
