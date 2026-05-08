package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.data.ContactDao
import com.rumor.mesh.data.ContactEntity
import com.rumor.mesh.data.MessageDao
import com.rumor.mesh.data.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "MessageStore"
private const val DEFAULT_BROADCAST_TTL = 7
private const val MANUAL_RELAY_BOOST = 2
private const val MAX_MESSAGES = 50_000
private const val EVICT_BATCH = 500
class MessageStore(
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val duplicateFilter: DuplicateFilter,
) {
    /**
     * Ingest a message from a gossip exchange.
     * Returns true if the message was new and should be forwarded/flooded.
     */
    suspend fun ingest(msg: RumorMessage): Boolean {
        if (!duplicateFilter.recordAndCheck(msg.id)) return false

        // Verify signature before storing
        val pubKeyBytes = msg.senderPublicKey.fromBase64()
        val payload = signableBytes(msg)
        val sigBytes = msg.signature.fromBase64()
        if (!CryptoManager.verify(payload, sigBytes, pubKeyBytes)) {
            RumorLog.w(TAG, "Dropping message ${msg.id.take(8)}: invalid signature")
            duplicateFilter.recordAndCheck(msg.id) // ensure it stays filtered
            return false
        }

        // Ensure sender's public key is recorded in contacts
        ensureContact(msg.senderId, msg.senderPublicKey)

        val entity = msg.toEntity()
        messageDao.insert(entity)

        // Evict oldest messages if we're over capacity
        val count = messageDao.count()
        if (count > MAX_MESSAGES) {
            messageDao.evictOldest(EVICT_BATCH)
        }

        return true
    }

    /** Decrement TTL for a message in transit. Returns null if exhausted. */
    fun decrementTtl(msg: RumorMessage): RumorMessage? {
        if (msg.ttl <= 0) return null
        return msg.copy(ttl = msg.ttl - 1)
    }

    /**
     * Boost TTL on manual relay. Capped at [DEFAULT_BROADCAST_TTL] so chained relays
     * across multiple users can't multiply hop count — a message that arrives near
     * exhaustion gets a small revival; one that's still fresh isn't amplified at all.
     */
    fun boostTtlForManualRelay(msg: RumorMessage): RumorMessage =
        msg.copy(
            ttl = minOf(DEFAULT_BROADCAST_TTL, msg.ttl + MANUAL_RELAY_BOOST),
            wasRelayed = true,
        )

    fun observeBroadcasts(): Flow<List<RumorMessage>> =
        messageDao.observeBroadcasts().map { list -> list.map { it.toMessage() } }

    fun observeThread(localUserId: String, peerId: String): Flow<List<RumorMessage>> =
        messageDao.observeThread(localUserId, peerId).map { list -> list.map { it.toMessage() } }

    fun observeUnread(localUserId: String): Flow<List<RumorMessage>> =
        messageDao.observeUnread(localUserId).map { list -> list.map { it.toMessage() } }

    suspend fun markRead(id: String) = messageDao.markRead(id)
    suspend fun markRelayed(id: String) = messageDao.markRelayed(id)

    private suspend fun ensureContact(userId: String, publicKeyB64: String) {
        val existing = contactDao.getById(userId)
        if (existing == null) {
            contactDao.upsert(
                ContactEntity(
                    userId = userId,
                    publicKey = publicKeyB64,
                    displayName = null,
                    isVerified = false,
                    autoRelay = false,
                    alwaysSave = false,
                    willingToCache = false,
                    firstSeenMs = System.currentTimeMillis(),
                    lastSeenMs = System.currentTimeMillis(),
                )
            )
        } else {
            contactDao.updateLastSeen(userId, System.currentTimeMillis())
        }
    }

    // ── Serialization helpers ─────────────────────────────────────────────────

    /** Canonical bytes that the signature covers — everything except the signature field. */
    fun signableBytes(msg: RumorMessage): ByteArray = buildString {
        append(msg.id)
        append(msg.senderId)
        append(msg.senderPublicKey)
        append(msg.sequenceNumber)
        append(msg.sentAtMs)
        append(msg.type.name)
        append(msg.ttl)
        append(msg.payload?.content ?: "")
        append(msg.encryptedPayload ?: "")
        append(msg.recipientId ?: "")
    }.toByteArray(Charsets.UTF_8)
}

private fun RumorMessage.toEntity() = MessageEntity(
    id = id,
    senderId = senderId,
    senderPublicKey = senderPublicKey,
    sequenceNumber = sequenceNumber,
    sentAtMs = sentAtMs,
    type = type,
    ttl = ttl,
    contentType = payload?.contentType,
    content = payload?.content,
    filename = payload?.filename,
    mimeType = payload?.mimeType,
    sizeBytes = payload?.sizeBytes ?: 0,
    encryptedPayload = encryptedPayload,
    recipientId = recipientId,
    signature = signature,
    receivedAtMs = receivedAtMs,
    isRead = isRead,
    wasRelayed = wasRelayed,
)

private fun MessageEntity.toMessage() = RumorMessage(
    id = id,
    senderId = senderId,
    senderPublicKey = senderPublicKey,
    sequenceNumber = sequenceNumber,
    sentAtMs = sentAtMs,
    type = type,
    ttl = ttl,
    payload = if (content != null && contentType != null) {
        com.rumor.mesh.core.model.MessagePayload(contentType, content, filename, mimeType, sizeBytes)
    } else null,
    encryptedPayload = encryptedPayload,
    recipientId = recipientId,
    signature = signature,
    receivedAtMs = receivedAtMs,
    isRead = isRead,
    wasRelayed = wasRelayed,
)
