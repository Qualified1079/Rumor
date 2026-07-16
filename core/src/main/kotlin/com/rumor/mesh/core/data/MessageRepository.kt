package com.rumor.mesh.core.data

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
     * Offer-eligible content for gossip reseed (O92): BROADCAST/DIRECT with
     * hops left, freshest first. Excludes control/transfer/presence traffic —
     * those are time-sensitive and re-offering a stale one would mislead peers.
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
