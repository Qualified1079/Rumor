package com.rumor.mesh.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rumor.mesh.core.model.BlocklistMode

@Dao
interface BlockEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: BlockEntryEntity)

    @Query("DELETE FROM block_entries WHERE userId = :userId")
    suspend fun delete(userId: String)

    @Query("SELECT * FROM block_entries WHERE expiresAtMs IS NULL OR expiresAtMs > :now")
    suspend fun getActive(now: Long = System.currentTimeMillis()): List<BlockEntryEntity>

    @Query("SELECT userId FROM block_entries WHERE expiresAtMs IS NULL OR expiresAtMs > :now")
    suspend fun getActiveIds(now: Long = System.currentTimeMillis()): List<String>

    @Query("DELETE FROM block_entries WHERE expiresAtMs IS NOT NULL AND expiresAtMs <= :now")
    suspend fun pruneExpired(now: Long = System.currentTimeMillis()): Int
}

@Dao
interface SubscribedBlocklistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sub: SubscribedBlocklistEntity)

    @Query("DELETE FROM subscribed_blocklists WHERE publisherId = :publisherId")
    suspend fun delete(publisherId: String)

    @Query("SELECT * FROM subscribed_blocklists WHERE publisherId = :publisherId")
    suspend fun get(publisherId: String): SubscribedBlocklistEntity?

    @Query("SELECT * FROM subscribed_blocklists WHERE mode = :mode")
    suspend fun getByMode(mode: BlocklistMode): List<SubscribedBlocklistEntity>

    @Query("SELECT * FROM subscribed_blocklists")
    suspend fun getAll(): List<SubscribedBlocklistEntity>
}

@Dao
interface BlocklistEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<BlocklistEntryEntity>)

    @Query("DELETE FROM blocklist_entries WHERE publisherId = :publisherId")
    suspend fun deleteAllForPublisher(publisherId: String)

    @Query("DELETE FROM blocklist_entries WHERE publisherId = :publisherId AND blockedUserId IN (:userIds)")
    suspend fun deleteEntries(publisherId: String, userIds: List<String>)

    @Query("SELECT DISTINCT blockedUserId FROM blocklist_entries")
    suspend fun getAllBlockedIds(): List<String>

    @Query("SELECT blockedUserId FROM blocklist_entries WHERE publisherId = :publisherId")
    suspend fun getBlockedIdsForPublisher(publisherId: String): List<String>
}
