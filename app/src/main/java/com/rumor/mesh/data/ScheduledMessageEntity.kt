package com.rumor.mesh.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.rumor.mesh.core.model.MessageType

/**
 * O22 / G15 — Persistence row for a user-scheduled message. Flat shape:
 * every field on `ScheduledMessage` is already a primitive or a
 * Room-convertible enum, so no JSON-blob storage is needed (unlike
 * KeywordFilterListEntity).
 *
 * Stored locally only — schedules are never gossiped. Fire-time
 * resolution is the wall-clock millisecond; `MessageScheduler` polls
 * `dueAt(now)` on a configurable tick.
 */
@Entity(tableName = "scheduled_messages")
data class ScheduledMessageEntity(
    @PrimaryKey val id: String,
    val type: MessageType,
    val contentText: String,
    val fireAtMs: Long,
    val intervalMs: Long?,
    val remainingFires: Int,
    val recipientUserId: String?,
    val createdAtMs: Long,
)
