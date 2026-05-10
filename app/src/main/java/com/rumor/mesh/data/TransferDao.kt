package com.rumor.mesh.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rumor.mesh.core.model.TransferStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transfer: TransferEntity)

    @Query("SELECT * FROM transfers WHERE transferId = :transferId")
    suspend fun getById(transferId: String): TransferEntity?

    @Query("SELECT * FROM transfers WHERE status = :status ORDER BY startedAtMs DESC")
    fun observeByStatus(status: TransferStatus): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers ORDER BY startedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<TransferEntity>>

    @Query("UPDATE transfers SET status = :status, completedAtMs = :completedAtMs WHERE transferId = :transferId")
    suspend fun updateStatus(transferId: String, status: TransferStatus, completedAtMs: Long?)

    @Query("DELETE FROM transfers WHERE transferId = :transferId")
    suspend fun delete(transferId: String)

    /** Purge completed/abandoned transfers older than [beforeMs] to reclaim storage. */
    @Query("DELETE FROM transfers WHERE status IN ('complete','abandoned','failed') AND completedAtMs < :beforeMs")
    suspend fun pruneOlderThan(beforeMs: Long)
}

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(chunk: ChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>)

    @Query("SELECT * FROM chunks WHERE transferId = :transferId ORDER BY chunkIndex")
    suspend fun getByTransfer(transferId: String): List<ChunkEntity>

    @Query("SELECT chunkIndex FROM chunks WHERE transferId = :transferId")
    suspend fun getReceivedIndices(transferId: String): List<Int>

    @Query("SELECT transferId, COUNT(*) AS received FROM chunks GROUP BY transferId")
    fun observeAllReceivedCounts(): Flow<List<TransferReceivedCount>>

    @Query("UPDATE chunks SET ackedAtMs = :ackedAtMs WHERE transferId = :transferId AND chunkIndex = :chunkIndex")
    suspend fun markAcked(transferId: String, chunkIndex: Int, ackedAtMs: Long)

    @Query("DELETE FROM chunks WHERE transferId = :transferId")
    suspend fun deleteAllForTransfer(transferId: String)
}
