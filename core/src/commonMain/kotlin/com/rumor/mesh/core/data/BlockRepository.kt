package com.rumor.mesh.core.data

import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.model.BlockEntry
import com.rumor.mesh.core.model.BlocklistEntry
import com.rumor.mesh.core.model.BlocklistMode
import com.rumor.mesh.core.model.SubscribedBlocklist

interface BlockEntryRepository {
    suspend fun upsert(entry: BlockEntry)
    suspend fun delete(userId: String)
    suspend fun getActive(now: Long = SystemClock.now()): List<BlockEntry>
    suspend fun getActiveIds(now: Long = SystemClock.now()): List<String>
    suspend fun pruneExpired(now: Long = SystemClock.now()): Int
}

interface SubscribedBlocklistRepository {
    suspend fun upsert(sub: SubscribedBlocklist)
    suspend fun delete(publisherId: String)
    suspend fun get(publisherId: String): SubscribedBlocklist?
    suspend fun getByMode(mode: BlocklistMode): List<SubscribedBlocklist>
    suspend fun getAll(): List<SubscribedBlocklist>
}

interface BlocklistEntryRepository {
    suspend fun insertAll(entries: List<BlocklistEntry>)
    suspend fun deleteAllForPublisher(publisherId: String)
    suspend fun deleteEntries(publisherId: String, userIds: List<String>)
    suspend fun getAllBlockedIds(): List<String>
    suspend fun getBlockedIdsForPublisher(publisherId: String): List<String>
}
