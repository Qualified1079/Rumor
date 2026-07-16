package com.rumor.mesh.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduledMessageEntity)

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun delete(id: String)

    /** Up to [limit] schedules whose fireAtMs is ≤ [nowMs], oldest fireAtMs first. */
    @Query("SELECT * FROM scheduled_messages WHERE fireAtMs <= :nowMs ORDER BY fireAtMs ASC LIMIT :limit")
    suspend fun dueAt(nowMs: Long, limit: Int): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun getById(id: String): ScheduledMessageEntity?

    @Query("SELECT * FROM scheduled_messages ORDER BY fireAtMs ASC")
    fun observeAll(): Flow<List<ScheduledMessageEntity>>
}
