package com.rumor.mesh.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface RoomDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoom(entity: RoomEntity)

    @Query("SELECT * FROM rooms WHERE roomId = :roomId LIMIT 1")
    suspend fun get(roomId: String): RoomEntity?

    @Query("SELECT * FROM rooms")
    suspend fun getAll(): List<RoomEntity>

    @Query("DELETE FROM rooms WHERE roomId = :roomId")
    suspend fun deleteRoom(roomId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRole(entity: RoomRosterEntity)

    @Query("DELETE FROM room_roster WHERE roomId = :roomId AND userId = :userId AND role = :role")
    suspend fun removeRole(roomId: String, userId: String, role: String)

    @Query("SELECT EXISTS(SELECT 1 FROM room_roster WHERE roomId = :roomId AND userId = :userId AND role = :role)")
    suspend fun hasRole(roomId: String, userId: String, role: String): Boolean

    @Query("SELECT userId FROM room_roster WHERE roomId = :roomId AND role = :role")
    suspend fun usersWithRole(roomId: String, role: String): List<String>

    @Query("DELETE FROM room_roster WHERE roomId = :roomId")
    suspend fun deleteRoster(roomId: String)

    @Transaction
    suspend fun upsertWithCreator(entity: RoomEntity) {
        upsertRoom(entity)
        addRole(RoomRosterEntity(entity.roomId, entity.creatorId, RoomRosterEntity.MODERATOR))
    }

    @Transaction
    suspend fun delete(roomId: String) {
        deleteRoom(roomId)
        deleteRoster(roomId)
    }

    @Transaction
    suspend fun ban(roomId: String, userId: String) {
        addRole(RoomRosterEntity(roomId, userId, RoomRosterEntity.BANNED))
        removeRole(roomId, userId, RoomRosterEntity.MEMBER)
    }
}
