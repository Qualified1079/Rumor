package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.ScheduledMessage
import kotlinx.coroutines.flow.Flow

interface ScheduledMessageRepository {
    suspend fun upsert(message: ScheduledMessage)
    suspend fun delete(id: String)
    /** Up to [limit] schedules whose [ScheduledMessage.fireAtMs] is ≤ [nowMs], oldest fireAt first. */
    suspend fun dueAt(nowMs: Long, limit: Int = 100): List<ScheduledMessage>
    suspend fun getById(id: String): ScheduledMessage?
    fun observeAll(): Flow<List<ScheduledMessage>>
}
