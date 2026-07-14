package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.RumorMessage
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    suspend fun insert(msg: RumorMessage)
    suspend fun getById(id: String): RumorMessage?
    suspend fun count(): Int
    suspend fun evictOldest(count: Int)
    suspend fun markRead(id: String)
    suspend fun markRelayed(id: String)

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

    fun observeBroadcasts(limit: Int = 200): Flow<List<RumorMessage>>
    fun observeThread(localUserId: String, peerId: String, limit: Int = 500): Flow<List<RumorMessage>>
    fun observeUnread(userId: String): Flow<List<RumorMessage>>
    fun observeAllDirect(userId: String, limit: Int = 500): Flow<List<RumorMessage>>
}
