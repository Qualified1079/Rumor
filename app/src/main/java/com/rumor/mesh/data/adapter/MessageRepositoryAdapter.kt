package com.rumor.mesh.data.adapter

import com.rumor.mesh.core.data.MessageRepository
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.sync.RbsrItem
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.data.MessageDao
import com.rumor.mesh.data.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MessageRepositoryAdapter(private val dao: MessageDao) : MessageRepository {

    override suspend fun insert(msg: RumorMessage) = dao.insert(msg.toEntity())
    override suspend fun getById(id: String): RumorMessage? = dao.getById(id)?.toMessage()
    override suspend fun count(): Int = dao.count()
    override suspend fun evictOldest(count: Int) = dao.evictOldest(count)
    override suspend fun markRead(id: String) = dao.markRead(id)
    override suspend fun markRelayed(id: String) = dao.markRelayed(id)

    override suspend fun offerable(limit: Int): List<RumorMessage> =
        dao.offerable(limit).map(MessageEntity::toMessage)
    override suspend fun knownIds(limit: Int): List<String> = dao.knownIds(limit)
    override suspend fun rbsrItems(limit: Int): List<RbsrItem> =
        dao.rbsrItems(limit).map { RbsrItem(it.sentAtMs, it.id) }

    override fun observeBroadcasts(limit: Int): Flow<List<RumorMessage>> =
        dao.observeBroadcasts(limit).map { it.map(MessageEntity::toMessage) }

    override fun observeThread(localUserId: String, peerId: String, limit: Int): Flow<List<RumorMessage>> =
        dao.observeThread(localUserId, peerId, limit).map { it.map(MessageEntity::toMessage) }

    override fun observeUnread(userId: String): Flow<List<RumorMessage>> =
        dao.observeUnread(userId).map { it.map(MessageEntity::toMessage) }

    override fun observeAllDirect(userId: String, limit: Int): Flow<List<RumorMessage>> =
        dao.observeAllDirect(userId, limit).map { it.map(MessageEntity::toMessage) }
}

private fun RumorMessage.toEntity() = MessageEntity(
    id = id, senderId = senderId, senderPublicKey = senderPublicKey,
    sequenceNumber = sequenceNumber, sentAtMs = sentAtMs, type = type, hopsToLive = hopsToLive,
    contentType = payload?.contentType, content = payload?.content,
    filename = payload?.filename, mimeType = payload?.mimeType,
    sizeBytes = payload?.sizeBytes ?: 0, encryptedPayload = encryptedPayload,
    recipientId = recipientId, signature = signature, receivedAtMs = receivedAtMs,
    isRead = isRead, wasRelayed = wasRelayed,
)

private fun MessageEntity.toMessage() = RumorMessage(
    id = id, senderId = senderId, senderPublicKey = senderPublicKey,
    sequenceNumber = sequenceNumber, sentAtMs = sentAtMs, type = type, hopsToLive = hopsToLive,
    payload = if (content != null && contentType != null)
        MessagePayload(contentType, content, filename, mimeType, sizeBytes) else null,
    encryptedPayload = encryptedPayload, recipientId = recipientId,
    signature = signature, receivedAtMs = receivedAtMs, isRead = isRead, wasRelayed = wasRelayed,
)
