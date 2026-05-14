package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.data.MessageRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Contact
import com.rumor.mesh.core.model.RumorMessage
import kotlinx.coroutines.flow.Flow

private const val TAG = "MessageStore"
private const val DEFAULT_BROADCAST_TTL = 7
private const val MANUAL_RELAY_BOOST = 2
private const val MAX_MESSAGES = 50_000
private const val EVICT_BATCH = 500

class MessageStore(
    private val messageRepo: MessageRepository,
    private val contactRepo: ContactRepository,
    private val duplicateFilter: DuplicateFilter,
) {
    /**
     * Ingest a message from a gossip exchange.
     * Returns true if the message was new and should be forwarded/flooded.
     */
    suspend fun ingest(msg: RumorMessage): Boolean {
        if (!duplicateFilter.recordAndCheck(msg.id)) return false

        val pubKeyBytes = msg.senderPublicKey.fromBase64()
        val payload = signableBytes(msg)
        val sigBytes = msg.signature.fromBase64()
        if (!CryptoManager.verify(payload, sigBytes, pubKeyBytes)) {
            RumorLog.w(TAG, "Dropping message ${msg.id.take(8)}: invalid signature")
            duplicateFilter.recordAndCheck(msg.id)
            return false
        }

        ensureContact(msg.senderId, msg.senderPublicKey)

        messageRepo.insert(msg)

        val count = messageRepo.count()
        if (count > MAX_MESSAGES) {
            messageRepo.evictOldest(EVICT_BATCH)
        }

        return true
    }

    fun decrementTtl(msg: RumorMessage): RumorMessage? {
        if (msg.ttl <= 0) return null
        return msg.copy(ttl = msg.ttl - 1)
    }

    fun boostTtlForManualRelay(msg: RumorMessage): RumorMessage =
        msg.copy(
            ttl = minOf(DEFAULT_BROADCAST_TTL, msg.ttl + MANUAL_RELAY_BOOST),
            wasRelayed = true,
        )

    fun observeBroadcasts(): Flow<List<RumorMessage>> = messageRepo.observeBroadcasts()
    fun observeThread(localUserId: String, peerId: String): Flow<List<RumorMessage>> =
        messageRepo.observeThread(localUserId, peerId)
    fun observeUnread(localUserId: String): Flow<List<RumorMessage>> =
        messageRepo.observeUnread(localUserId)
    fun observeAllDirect(localUserId: String): Flow<List<RumorMessage>> =
        messageRepo.observeAllDirect(localUserId)

    suspend fun markRead(id: String) = messageRepo.markRead(id)
    suspend fun markRelayed(id: String) = messageRepo.markRelayed(id)

    private suspend fun ensureContact(userId: String, publicKeyB64: String) {
        val existing = contactRepo.getById(userId)
        if (existing == null) {
            contactRepo.upsert(
                Contact(
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
            contactRepo.updateLastSeen(userId, System.currentTimeMillis())
        }
    }

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
