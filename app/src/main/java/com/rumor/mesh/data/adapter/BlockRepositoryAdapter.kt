package com.rumor.mesh.data.adapter

import com.rumor.mesh.core.data.BlockEntryRepository
import com.rumor.mesh.core.data.BlocklistEntryRepository
import com.rumor.mesh.core.data.SubscribedBlocklistRepository
import com.rumor.mesh.core.model.BlockEntry
import com.rumor.mesh.core.model.BlocklistEntry
import com.rumor.mesh.core.model.BlocklistMode
import com.rumor.mesh.core.model.SubscribedBlocklist
import com.rumor.mesh.data.BlockEntryDao
import com.rumor.mesh.data.BlockEntryEntity
import com.rumor.mesh.data.BlocklistEntryDao
import com.rumor.mesh.data.BlocklistEntryEntity
import com.rumor.mesh.data.SubscribedBlocklistDao
import com.rumor.mesh.data.SubscribedBlocklistEntity

class BlockEntryRepositoryAdapter(private val dao: BlockEntryDao) : BlockEntryRepository {
    override suspend fun upsert(entry: BlockEntry) = dao.upsert(entry.toEntity())
    override suspend fun delete(userId: String) = dao.delete(userId)
    override suspend fun getActive(now: Long): List<BlockEntry> = dao.getActive(now).map(BlockEntryEntity::toModel)
    override suspend fun getActiveIds(now: Long): List<String> = dao.getActiveIds(now)
    override suspend fun pruneExpired(now: Long): Int = dao.pruneExpired(now)
}

class SubscribedBlocklistRepositoryAdapter(private val dao: SubscribedBlocklistDao) : SubscribedBlocklistRepository {
    override suspend fun upsert(sub: SubscribedBlocklist) = dao.upsert(sub.toEntity())
    override suspend fun delete(publisherId: String) = dao.delete(publisherId)
    override suspend fun get(publisherId: String): SubscribedBlocklist? = dao.get(publisherId)?.toModel()
    override suspend fun getByMode(mode: BlocklistMode): List<SubscribedBlocklist> =
        dao.getByMode(mode).map(SubscribedBlocklistEntity::toModel)
    override suspend fun getAll(): List<SubscribedBlocklist> = dao.getAll().map(SubscribedBlocklistEntity::toModel)
}

class BlocklistEntryRepositoryAdapter(private val dao: BlocklistEntryDao) : BlocklistEntryRepository {
    override suspend fun insertAll(entries: List<BlocklistEntry>) =
        dao.insertAll(entries.map { BlocklistEntryEntity(it.publisherId, it.blockedUserId) })
    override suspend fun deleteAllForPublisher(publisherId: String) = dao.deleteAllForPublisher(publisherId)
    override suspend fun deleteEntries(publisherId: String, userIds: List<String>) =
        dao.deleteEntries(publisherId, userIds)
    override suspend fun getAllBlockedIds(): List<String> = dao.getAllBlockedIds()
    override suspend fun getBlockedIdsForPublisher(publisherId: String): List<String> =
        dao.getBlockedIdsForPublisher(publisherId)
}

private fun BlockEntry.toEntity() = BlockEntryEntity(userId, createdAtMs, expiresAtMs, reason)
private fun BlockEntryEntity.toModel() = BlockEntry(userId, createdAtMs, expiresAtMs, reason)

private fun SubscribedBlocklist.toEntity() = SubscribedBlocklistEntity(
    publisherId, publisherPublicKey, mode, currentVersion, subscribedAtMs, lastAppliedAtMs,
)
private fun SubscribedBlocklistEntity.toModel() = SubscribedBlocklist(
    publisherId, publisherPublicKey, mode, currentVersion, subscribedAtMs, lastAppliedAtMs,
)
