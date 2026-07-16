package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.ScheduledMessage
import kotlinx.coroutines.flow.Flow

/**
 * G15 / O22 — persisted store of scheduled outbound messages.
 * [com.rumor.mesh.core.scheduling.MessageScheduler] polls [dueAt] on
 * a fixed tick, fires through the normal compose path, then either
 * deletes the schedule (one-shot) or advances [ScheduledMessage]'s
 * `fireAtMs` (recurring). Survives restarts so a phone that was off
 * over the scheduled time still fires the message on next boot.
 *
 * Burst-suppression on long-paused devices is the scheduler's job,
 * not this repo's — [dueAt] honestly returns every overdue row and
 * the caller decides whether to skip backlog or send the lot.
 */
interface ScheduledMessageRepository {
    suspend fun upsert(message: ScheduledMessage)
    suspend fun delete(id: String)
    /** Up to [limit] schedules whose [ScheduledMessage.fireAtMs] is ≤ [nowMs], oldest fireAt first. */
    suspend fun dueAt(nowMs: Long, limit: Int = 100): List<ScheduledMessage>
    suspend fun getById(id: String): ScheduledMessage?
    fun observeAll(): Flow<List<ScheduledMessage>>
}
