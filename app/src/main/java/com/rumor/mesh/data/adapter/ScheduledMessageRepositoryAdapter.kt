package com.rumor.mesh.data.adapter

import com.rumor.mesh.core.data.ScheduledMessageRepository
import com.rumor.mesh.core.model.ScheduledMessage
import com.rumor.mesh.data.ScheduledMessageDao
import com.rumor.mesh.data.ScheduledMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * O22 / G15 — Room adapter for [ScheduledMessageRepository]. Standard
 * pattern matching the existing adapters (Block, Contact, etc.). All
 * fields map 1:1 — no nested types, no JSON blob.
 */
class ScheduledMessageRepositoryAdapter(
    private val dao: ScheduledMessageDao,
) : ScheduledMessageRepository {

    override suspend fun upsert(message: ScheduledMessage) {
        dao.upsert(message.toEntity())
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    override suspend fun dueAt(nowMs: Long, limit: Int): List<ScheduledMessage> =
        dao.dueAt(nowMs, limit).map { it.toModel() }

    override suspend fun getById(id: String): ScheduledMessage? =
        dao.getById(id)?.toModel()

    override fun observeAll(): Flow<List<ScheduledMessage>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }
}

private fun ScheduledMessage.toEntity() = ScheduledMessageEntity(
    id = id,
    type = type,
    contentText = contentText,
    fireAtMs = fireAtMs,
    intervalMs = intervalMs,
    remainingFires = remainingFires,
    recipientUserId = recipientUserId,
    createdAtMs = createdAtMs,
)

private fun ScheduledMessageEntity.toModel() = ScheduledMessage(
    id = id,
    type = type,
    contentText = contentText,
    fireAtMs = fireAtMs,
    intervalMs = intervalMs,
    remainingFires = remainingFires,
    recipientUserId = recipientUserId,
    createdAtMs = createdAtMs,
)
