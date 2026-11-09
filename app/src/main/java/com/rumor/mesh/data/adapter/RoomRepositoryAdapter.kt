package com.rumor.mesh.data.adapter

import com.rumor.mesh.core.data.RoomRecord
import com.rumor.mesh.core.data.RoomRepository
import com.rumor.mesh.core.model.RoomMembershipPolicy
import com.rumor.mesh.core.model.RoomPostingPolicy
import com.rumor.mesh.data.RoomDao
import com.rumor.mesh.data.RoomEntity
import com.rumor.mesh.data.RoomRosterEntity
import com.rumor.mesh.data.RoomRosterEntity.Companion.BANNED
import com.rumor.mesh.data.RoomRosterEntity.Companion.MEMBER
import com.rumor.mesh.data.RoomRosterEntity.Companion.MODERATOR

/**
 * O187 Room-backed [RoomRepository]; semantics mirror `InMemoryRoomRepository`.
 *
 * An unparseable policy name throws rather than mapping to null: a null
 * [get] reads as "unknown room", which requires no cert and no membership —
 * a corrupt row would silently turn a governed room ungoverned.
 */
class RoomRepositoryAdapter(
    private val dao: RoomDao,
) : RoomRepository {

    override suspend fun upsert(room: RoomRecord) = dao.upsertWithCreator(room.toEntity())

    override suspend fun get(roomId: String): RoomRecord? = dao.get(roomId)?.toModel()

    override suspend fun getAll(): List<RoomRecord> = dao.getAll().map { it.toModel() }

    override suspend fun delete(roomId: String) = dao.delete(roomId)

    override suspend fun addModerator(roomId: String, userId: String) =
        dao.addRole(RoomRosterEntity(roomId, userId, MODERATOR))

    override suspend fun removeModerator(roomId: String, userId: String) {
        if (dao.get(roomId)?.creatorId == userId) return
        dao.removeRole(roomId, userId, MODERATOR)
    }

    override suspend fun isModerator(roomId: String, userId: String) = dao.hasRole(roomId, userId, MODERATOR)

    override suspend fun moderatorsOf(roomId: String) = dao.usersWithRole(roomId, MODERATOR).toSet()

    override suspend fun addMember(roomId: String, userId: String) =
        dao.addRole(RoomRosterEntity(roomId, userId, MEMBER))

    override suspend fun removeMember(roomId: String, userId: String) = dao.removeRole(roomId, userId, MEMBER)

    override suspend fun isMember(roomId: String, userId: String) = dao.hasRole(roomId, userId, MEMBER)

    override suspend fun membersOf(roomId: String) = dao.usersWithRole(roomId, MEMBER).toSet()

    override suspend fun addBan(roomId: String, userId: String) = dao.ban(roomId, userId)

    override suspend fun removeBan(roomId: String, userId: String) = dao.removeRole(roomId, userId, BANNED)

    override suspend fun isBanned(roomId: String, userId: String) = dao.hasRole(roomId, userId, BANNED)

    override suspend fun bansOf(roomId: String) = dao.usersWithRole(roomId, BANNED).toSet()
}

private fun RoomRecord.toEntity() = RoomEntity(
    roomId = roomId,
    name = name,
    creatorId = creatorId,
    createdAtMs = createdAtMs,
    membershipPolicy = membershipPolicy.name,
    postingPolicy = postingPolicy.name,
    allowMedia = allowMedia,
)

private fun RoomEntity.toModel() = RoomRecord(
    roomId = roomId,
    name = name,
    creatorId = creatorId,
    createdAtMs = createdAtMs,
    membershipPolicy = RoomMembershipPolicy.valueOf(membershipPolicy),
    postingPolicy = RoomPostingPolicy.valueOf(postingPolicy),
    allowMedia = allowMedia,
)
