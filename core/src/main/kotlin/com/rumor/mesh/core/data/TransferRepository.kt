package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.TransferDirection
import com.rumor.mesh.core.model.TransferStatus
import kotlinx.coroutines.flow.Flow

data class TransferRecord(
    val transferId: String,
    val direction: TransferDirection,
    val contentType: ContentType,
    val mimeType: String?,
    val title: String?,
    val totalBytes: Long,
    val totalChunks: Int,
    val chunkSize: Int,
    val contentHash: String,
    val recipientId: String?,
    val senderId: String?,
    val startedAtMs: Long,
    val completedAtMs: Long?,
    val status: TransferStatus,
)

data class ChunkRecord(
    val transferId: String,
    val chunkIndex: Int,
    val data: ByteArray,
    val receivedAtMs: Long?,
    val ackedAtMs: Long?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkRecord) return false
        return transferId == other.transferId &&
            chunkIndex == other.chunkIndex &&
            data.contentEquals(other.data)
    }
    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + data.contentHashCode()
        return result
    }
}

interface TransferRepository {
    suspend fun upsert(transfer: TransferRecord)
    suspend fun getById(transferId: String): TransferRecord?
    fun observeByStatus(status: TransferStatus): Flow<List<TransferRecord>>
    fun observeRecent(limit: Int = 50): Flow<List<TransferRecord>>
    suspend fun updateStatus(transferId: String, status: TransferStatus, completedAtMs: Long?)
    suspend fun delete(transferId: String)
    suspend fun pruneOlderThan(beforeMs: Long)
}

interface ChunkRepository {
    suspend fun insertOrReplace(chunk: ChunkRecord)
    suspend fun insertAll(chunks: List<ChunkRecord>)
    suspend fun getByTransfer(transferId: String): List<ChunkRecord>
    suspend fun getReceivedIndices(transferId: String): List<Int>
    suspend fun markAcked(transferId: String, chunkIndex: Int, ackedAtMs: Long)
    suspend fun deleteAllForTransfer(transferId: String)
}
