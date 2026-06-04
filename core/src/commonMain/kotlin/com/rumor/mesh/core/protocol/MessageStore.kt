package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.data.MessageRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Contact
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.policy.StaticMode
import kotlinx.coroutines.flow.Flow
import com.rumor.mesh.core.platform.AtomicCounter
import com.rumor.mesh.core.platform.ConcurrentMap

private const val TAG = "MessageStore"
private const val DEFAULT_BROADCAST_HOPS = 7
private const val MANUAL_RELAY_BOOST = 2
private const val MAX_MESSAGES = 50_000
private const val EVICT_BATCH = 500
/** A static node holds a far deeper backlog so it can serve peers that were offline longer. */
private const val STATIC_CACHE_BOOST = 4

class MessageStore(
    private val messageRepo: MessageRepository,
    private val contactRepo: ContactRepository,
    private val duplicateFilter: DuplicateFilter,
    private val staticMode: StaticMode? = null,
    private val clock: com.rumor.mesh.core.Clock = com.rumor.mesh.core.SystemClock,
) {
    private val _sigFailures = AtomicCounter()
    private val _rateLimited = AtomicCounter()
    /** Count of messages dropped due to invalid Ed25519 signatures. */
    val sigFailureCount: Long get() = _sigFailures.get()
    /** Count of messages dropped at ingest because the sender exceeded the per-sender token bucket (O16). */
    val rateLimitedCount: Long get() = _rateLimited.get()

    /**
     * O16 per-sender token bucket. Caps the rate at which any single sender
     * can consume our ingest CPU + storage. Generous threshold ([INGEST_BUDGET_PER_SEC])
     * so high-traffic legitimate nodes aren't false-positived; tight enough that
     * a spammer or compromised plugin can't monopolise either resource.
     *
     * Relay path is NOT gated by this — only `MessageStore.ingest`. A sender
     * we rate-limit at ingest still has their messages relayed by others; this
     * only stops them consuming OUR local resources.
     */
    // SynchronizedObject so each Bucket can act as its own atomicfu monitor on
    // every platform. Mutations of windowStartMs / count are under that monitor;
    // happens-before via synchronized makes @Volatile redundant.
    private class Bucket(var windowStartMs: Long, var count: Int) : kotlinx.atomicfu.locks.SynchronizedObject()
    private val buckets = ConcurrentMap<String, Bucket>()
    private val INGEST_BUDGET_PER_SEC = 100
    private val INGEST_WINDOW_MS = 1_000L

    private fun acceptForRate(senderId: String, nowMs: Long): Boolean {
        val b = buckets.getOrPut(senderId) { Bucket(nowMs, 0) }
        return kotlinx.atomicfu.locks.synchronized(b) {
            if (nowMs - b.windowStartMs >= INGEST_WINDOW_MS) {
                b.windowStartMs = nowMs
                b.count = 0
            }
            if (b.count >= INGEST_BUDGET_PER_SEC) {
                false
            } else {
                b.count++
                true
            }
        }
    }

    /**
     * Ingest a message from a gossip exchange.
     * Returns true if the message was new and should be forwarded/flooded.
     */
    suspend fun ingest(msg: RumorMessage): Boolean {
        if (!duplicateFilter.recordAndCheck(msg.id)) return false

        // O16: per-sender token bucket gate. Skip ingest entirely for senders
        // exceeding INGEST_BUDGET_PER_SEC. Caller treats the return as a duplicate;
        // the relay path is unaffected so other peers still propagate.
        if (!acceptForRate(msg.senderId, clock.now())) {
            _rateLimited.incrementAndGet()
            RumorLog.d(TAG, "Rate-limit drop from ${msg.senderId.take(8)}… (>$INGEST_BUDGET_PER_SEC/s)")
            return false
        }

        val pubKeyBytes = msg.senderPublicKey.fromBase64()
        val payload = signableBytes(msg)
        val sigBytes = msg.signature.fromBase64()
        if (!CryptoManager.verify(payload, sigBytes, pubKeyBytes)) {
            RumorLog.w(TAG, "Dropping message ${msg.id.take(8)}: invalid signature")
            _sigFailures.incrementAndGet()
            duplicateFilter.recordAndCheck(msg.id)
            return false
        }

        ensureContact(msg.senderId, msg.senderPublicKey)

        messageRepo.insert(msg)

        val isStatic = staticMode?.enabled?.value == true
        val maxMessages = if (isStatic) MAX_MESSAGES * STATIC_CACHE_BOOST else MAX_MESSAGES
        val evictBatch = if (isStatic) EVICT_BATCH * STATIC_CACHE_BOOST else EVICT_BATCH
        val count = messageRepo.count()
        if (count > maxMessages) {
            messageRepo.evictOldest(evictBatch)
        }

        return true
    }

    fun decrementHops(msg: RumorMessage): RumorMessage? {
        if (msg.hopsToLive <= 0) return null
        return msg.copy(hopsToLive = msg.hopsToLive - 1)
    }

    fun boostHopsForManualRelay(msg: RumorMessage): RumorMessage =
        msg.copy(
            hopsToLive = minOf(DEFAULT_BROADCAST_HOPS, msg.hopsToLive + MANUAL_RELAY_BOOST),
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
                    firstSeenMs = clock.now(),
                    lastSeenMs = clock.now(),
                )
            )
        } else {
            contactRepo.updateLastSeen(userId, clock.now())
        }
    }

    /**
     * Canonical bytes the signature covers. Excludes hopsToLive (mutated on
     * every relay via decrementHops — if it were in the signed set, the
     * signature would become invalid after one hop and the message would
     * be dropped at every downstream node). Also excludes the signature
     * field itself and the forward-compat `_ext` map (see [RumorMessage.ext]).
     *
     * Prefixed with the `rumor-msg-v1:` domain tag so a sig produced here can
     * never be replayed as a sig in another protocol context (HELLO challenge,
     * blocklist record, future message-v2 envelope). Bump the tag only when
     * the canonical-byte layout itself changes, never for additive fields.
     */
    fun signableBytes(msg: RumorMessage): ByteArray = buildString {
        append("rumor-msg-v1:")
        append(msg.id)
        append(msg.senderId)
        append(msg.senderPublicKey)
        append(msg.sequenceNumber)
        append(msg.sentAtMs)
        append(msg.type.name)
        append(msg.payload?.content ?: "")
        append(msg.encryptedPayload ?: "")
        append(msg.recipientId ?: "")
    }.toByteArray(Charsets.UTF_8)
}
