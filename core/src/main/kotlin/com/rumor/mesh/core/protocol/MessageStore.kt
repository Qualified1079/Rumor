package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.data.MessageRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Contact
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.mode.ModeState
import kotlinx.coroutines.flow.Flow
import com.rumor.mesh.core.platform.AtomicCounter

private const val TAG = "MessageStore"
private const val DEFAULT_BROADCAST_HOPS = 7
private const val MANUAL_RELAY_BOOST = 2
private const val MAX_MESSAGES = 50_000
private const val EVICT_BATCH = 500
// Upper bound on distinct per-sender rate buckets held at once (§2 memory-DoS fix).
private const val MAX_RATE_BUCKETS = 10_000
// The cache-depth multiplier now lives per-mode in ModeEnvelope
// (storageCacheBoost: MOBILE=1, STATIC=4 [= the old STATIC_CACHE_BOOST], FREE=8).

/**
 * Persistence + ingest gateway between the wire and the application
 * layer. Three jobs:
 *
 *  1. **Dedup-gated ingest.** Inbound messages pass through
 *     [DuplicateFilter] before signature verification or persistence
 *     happens — saves the most expensive work on already-seen ids.
 *  2. **Signature verification.** Every non-bridge message gets its
 *     Ed25519 signature verified against the embedded
 *     `senderPublicKey` before storage. Failures bump
 *     [sigFailureCount] and the message is dropped — never persisted.
 *  3. **Size-capped persistence + eviction.** Holds up to
 *     [MAX_MESSAGES] (boosted by [STATIC_CACHE_BOOST] for static
 *     nodes that serve longer-offline peers); on overflow,
 *     [evictOldest] drops [EVICT_BATCH] of the oldest non-always-save
 *     records.
 *
 * Also exposes a per-sender [INGEST_BUDGET_PER_SEC] token bucket (O16)
 * so a single sender can't burn unbounded CPU + storage at this node.
 * The relay path NEVER goes through this rate gate — only ingest.
 *
 * Relay path uses [decrementHops] which is rate-unconstrained: relays
 * are shared infrastructure; we propagate everything, just trim the
 * TTL by one.
 */
class MessageStore(
    private val messageRepo: MessageRepository,
    private val contactRepo: ContactRepository,
    private val duplicateFilter: DuplicateFilter,
    private val modeState: ModeState? = null,
    private val clock: com.rumor.mesh.core.Clock = com.rumor.mesh.core.SystemClock,
) {
    /**
     * O40 — purge a specific id from the local store. The id stays in
     * the dedup set so the same ciphertext can't be re-ingested via
     * gossip exchange. No-op if the id isn't present.
     */
    suspend fun deleteById(id: String) {
        messageRepo.deleteById(id)
        // Dedup set already records the id (it was previously ingested).
    }

    /** Read access for callers that compose by id (O40 delete-on-ACK authorization check). */
    suspend fun getById(id: String): RumorMessage? = messageRepo.getById(id)
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
    // BoundedFifoMap, not an unbounded ConcurrentMap: a Sybil attacker minting a
    // fresh senderId per message would otherwise grow this without limit (one
    // Bucket per distinct sender string = a memory-growth DoS). FIFO eviction
    // caps it; an evicted sender just gets a fresh rate window, which is harmless.
    private val buckets = com.rumor.mesh.core.platform.BoundedFifoMap<String, Bucket>(MAX_RATE_BUCKETS)
    private val INGEST_BUDGET_PER_SEC = 100
    private val INGEST_WINDOW_MS = 1_000L

    private fun acceptForRate(senderId: String, nowMs: Long): Boolean {
        val b = buckets.get(senderId) ?: Bucket(nowMs, 0).also { buckets.put(senderId, it) }
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
    /**
     * [persist] = false runs the full verify pipeline and records the id as
     * seen but skips the durable store — for ephemeral control traffic
     * (SELF_PRESENCE) and verified self-echoes, where "known" matters for
     * summaries/want-sets but archiving is pure bloat (field-found 2026-07-18:
     * stores were 99% beacons, and unrecorded echoes re-shipped ~400KB/round
     * forever via the RBSR diff).
     */
    suspend fun ingest(msg: RumorMessage, persist: Boolean = true): Boolean {
        // §2 ORDERING IS SECURITY-CRITICAL. Nothing keyed on unverified wire data
        // may commit persistent state before the Ed25519 check. In particular the
        // dedup filter is only *checked* (not recorded) here — recording an id
        // before verification let a forged, bad-signature message carrying a
        // target's real id permanently mark that id "seen", so the genuine signed
        // message later dropped as a duplicate. A pure targeted-censorship
        // primitive. Order must stay: check-only dedup → verify sig → verify
        // identity → rate-limit → COMMIT (record dedup + persist).

        // 1. Check-only dedup. Commits no "seen" state — a later forgery can't be
        //    what burned the id.
        if (duplicateFilter.mightBeSeen(msg.id)) return false

        // 2. Signature verification. A forgery must never reach the record step.
        val pubKeyBytes = msg.senderPublicKey.fromBase64()
        val payload = signableBytes(msg)
        val sigBytes = msg.signature.fromBase64()
        if (!CryptoManager.verify(payload, sigBytes, pubKeyBytes)) {
            RumorLog.w(TAG, "Dropping message ${msg.id.take(8)}: invalid signature")
            _sigFailures.incrementAndGet()
            // Deliberately do NOT record the id — leave it available for the
            // genuine message that may still arrive via an honest path.
            return false
        }

        // 3. Identity binding. The signature proves the holder of senderPublicKey
        //    signed this, but not that senderId belongs to that key. Enforce the
        //    identity model (userId = SHA-256(pubkey)) so senderId is authenticated
        //    — otherwise the per-sender rate bucket below keys on a field the
        //    attacker can rotate freely with a single keypair.
        if (msg.senderId != CryptoManager.publicKeyToUserId(pubKeyBytes)) {
            RumorLog.w(TAG, "Dropping message ${msg.id.take(8)}: senderId does not match senderPublicKey")
            _sigFailures.incrementAndGet()
            return false
        }

        // 4. O16 per-sender token bucket, now on an AUTHENTICATED senderId: minting
        //    a fresh budget requires a fresh keypair (the Sybil floor per O27/O60),
        //    not merely a different string. Relay path is unaffected — this only
        //    caps what a sender consumes of OUR local ingest resources.
        if (!acceptForRate(msg.senderId, clock.now())) {
            _rateLimited.incrementAndGet()
            RumorLog.d(TAG, "Rate-limit drop from ${msg.senderId.take(8)}… (>$INGEST_BUDGET_PER_SEC/s)")
            return false
        }

        // 5. Commit: only now is it safe to mark the id permanently seen and store.
        duplicateFilter.recordAndCheck(msg.id)
        if (!persist) return true
        ensureContact(msg.senderId, msg.senderPublicKey)
        messageRepo.insert(msg)
        evictIfOverCap()

        return true
    }

    /**
     * Store a message we just composed locally so it shows up in our own feed/thread.
     * Unlike [ingest], skips signature re-verification (we just signed it ourselves)
     * and skips [ensureContact] (the sender is our own identity, not a peer contact).
     */
    suspend fun ingestOwn(msg: RumorMessage) {
        if (!duplicateFilter.recordAndCheck(msg.id)) return
        messageRepo.insert(msg)
        evictIfOverCap()
    }

    private suspend fun evictIfOverCap() {
        val boost = modeState?.envelope?.storageCacheBoost ?: 1
        val maxMessages = MAX_MESSAGES * boost
        val evictBatch = EVICT_BATCH * boost
        val count = messageRepo.count()
        if (count > maxMessages) {
            messageRepo.evictOldest(evictBatch)
        }
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

    /** O92 reseed sources: offer-eligible content, and recent ids for dedup. */
    suspend fun offerable(limit: Int): List<RumorMessage> = messageRepo.offerable(limit)
    suspend fun knownIds(limit: Int): List<String> = messageRepo.knownIds(limit)
    /** O42 RBSR snapshot — whole-store (sentAtMs, id), mirrors dedup-summary semantics. */
    suspend fun rbsrItems(limit: Int): List<com.rumor.mesh.core.sync.RbsrItem> =
        messageRepo.rbsrItems(limit)

    /**
     * Public because the engine also creates contacts from completed gossip
     * exchanges — a HELLO challenge-response is stronger identity provenance
     * than a relayed message signature, and a zero-message exchange (two fresh
     * installs meeting) must still produce a contact.
     */
    suspend fun ensureContact(userId: String, publicKeyB64: String) {
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
