package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.sync.RbsrItem
import kotlinx.coroutines.flow.Flow

/**
 * The persisted store of [RumorMessage]s — every signed message the
 * local node has ingested (locally composed OR received from a peer
 * OR injected from a bridge plugin). The engine consults this repo
 * on every receive (dedup), every offer (RBSR / bloom / id-list
 * summary), every UI render, and every relay-deletion (O40 / G28).
 *
 * **Three impls in tree, one contract:**
 *  - `:app` → Room/SQLite-backed `MessageRepositoryAdapter`
 *  - `:simulator` → in-memory `InMemoryMessageRepository`
 *  - tests → either of the above as fixtures
 *
 * **Eviction is the load-bearing piece** for the prolonged-disconnection
 * design target (O55): months of accumulated messages on a phone with
 * no upstream means the working set must stay bounded without losing
 * the freshest content. [evictOldest] is the lever; production policy
 * lives in [com.rumor.mesh.core.protocol.MessageStore].
 *
 * **Observers ([observeBroadcasts] / [observeThread] / [observeUnread] /
 * [observeAllDirect]) are the UI's read surface** — every Compose
 * screen reads from a flow off this repo. Adapters must emit a new
 * snapshot per upsert / delete / markRead within a few hundred ms; the
 * Room-backed impl does this via Flow + invalidation, the in-memory
 * impl does it via a MutableStateFlow update.
 */
interface MessageRepository {
    suspend fun insert(msg: RumorMessage)
    suspend fun getById(id: String): RumorMessage?
    suspend fun count(): Int
    suspend fun evictOldest(count: Int)
    suspend fun markRead(id: String)
    suspend fun markRelayed(id: String)
    /** O40 — purge a specific id (signed-DELETE flow). No-op if id is absent. */
    suspend fun deleteById(id: String)

    /**
     * Purge every message of one type. Start-time repair for ephemeral types
     * that older builds persisted (SELF_PRESENCE beacon bloat — the echo-loop
     * fix stops new ones persisting; this clears the accumulated rows).
     */
    suspend fun deleteByType(type: MessageType)

    /**
     * Offer-eligible content for gossip reseed (O92): [OFFERABLE_TYPES] with
     * hops left, freshest first. Every store implements exactly that set.
     */
    suspend fun offerable(limit: Int): List<RumorMessage>

    /**
     * Recent message ids for dedup reseed (O92). The DuplicateFilter is volatile,
     * so after a restart our "what we hold" summary is empty and peers re-offer
     * everything; seeding it from the store restores an accurate summary.
     */
    suspend fun knownIds(limit: Int): List<String>

    /**
     * `(sentAtMs, id)` snapshot of everything we hold, for RBSR reconciliation
     * (O42). Must mirror the dedup summary's semantics — the whole store, not
     * just offer-eligible content — or the two sync paths would advertise
     * different knowledge sets.
     */
    suspend fun rbsrItems(limit: Int): List<RbsrItem>
    fun observeBroadcasts(limit: Int = 200): Flow<List<RumorMessage>>
    fun observeThread(localUserId: String, peerId: String, limit: Int = 500): Flow<List<RumorMessage>>
    fun observeUnread(userId: String): Flow<List<RumorMessage>>
    fun observeAllDirect(userId: String, limit: Int = 500): Flow<List<RumorMessage>>
}

/**
 * Types the store re-offers to peers once the volatile scheduler has been drained
 * (O92 backfill). One list for every store — the Room DAO takes it as a query
 * parameter — so the two can't drift.
 *
 * Room lifecycle and room posts belong here: the scheduler's take() is
 * destructive, so on a node with two peers whichever session ran first drained
 * the relayed copy, and a type missing from this set then never reached the
 * other peer (field-hit 2026-11-08: a ROOM_CREATE stopped one hop out on a
 * three-node line while broadcasts crossed it). A roster change must reach
 * members who were offline (O187). Presence, transfer and other control traffic
 * stay out: time-sensitive, and a stale re-offer misleads.
 */
val OFFERABLE_TYPES: Set<com.rumor.mesh.core.model.MessageType> = setOf(
    com.rumor.mesh.core.model.MessageType.BROADCAST,
    com.rumor.mesh.core.model.MessageType.DIRECT,
    com.rumor.mesh.core.model.MessageType.DIRECT_ACK,
    com.rumor.mesh.core.model.MessageType.ROOM_CREATE,
    com.rumor.mesh.core.model.MessageType.ROOM_INVITE,
    com.rumor.mesh.core.model.MessageType.ROOM_ACTION,
    com.rumor.mesh.core.model.MessageType.ROOM_MESSAGE,
)

/** [OFFERABLE_TYPES] that go to every peer; DMs and their receipts are routed per peer instead. */
val FLOODED_OFFERABLE_TYPES: Set<com.rumor.mesh.core.model.MessageType> =
    OFFERABLE_TYPES - setOf(com.rumor.mesh.core.model.MessageType.DIRECT, com.rumor.mesh.core.model.MessageType.DIRECT_ACK)
