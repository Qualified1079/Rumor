package com.rumor.mesh.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RoomSubscriptionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RoomSubscriptionEntity)

    @Query("DELETE FROM room_subscriptions WHERE roomId = :roomId")
    suspend fun delete(roomId: String)

    @Query("SELECT * FROM room_subscriptions WHERE roomId = :roomId LIMIT 1")
    suspend fun get(roomId: String): RoomSubscriptionEntity?

    @Query("SELECT * FROM room_subscriptions")
    suspend fun getAll(): List<RoomSubscriptionEntity>
}
