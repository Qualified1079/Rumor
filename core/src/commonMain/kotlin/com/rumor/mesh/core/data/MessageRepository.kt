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
    fun observeBroadcasts(limit: Int = 200): Flow<List<RumorMessage>>
    fun observeThread(localUserId: String, peerId: String, limit: Int = 500): Flow<List<RumorMessage>>
    fun observeUnread(userId: String): Flow<List<RumorMessage>>
    fun observeAllDirect(userId: String, limit: Int = 500): Flow<List<RumorMessage>>
}
