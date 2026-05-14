package com.rumor.mesh.data.adapter

import com.rumor.mesh.core.data.ChunkRecord
import com.rumor.mesh.core.data.ChunkRepository
import com.rumor.mesh.core.data.TransferRecord
import com.rumor.mesh.core.data.TransferRepository
import com.rumor.mesh.core.model.TransferStatus
import com.rumor.mesh.data.ChunkDao
import com.rumor.mesh.data.ChunkEntity
import com.rumor.mesh.data.TransferDao
import com.rumor.mesh.data.TransferEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TransferRepositoryAdapter(private val dao: TransferDao) : TransferRepository {
    override suspend fun upsert(transfer: TransferRecord) = dao.upsert(transfer.toEntity())
    override suspend fun getById(transferId: String): TransferRecord? = dao.getById(transferId)?.toModel()
    override fun observeByStatus(status: TransferStatus): Flow<List<TransferRecord>> =
        dao.observeByStatus(status).map { it.map(TransferEntity::toModel) }
    override fun observeRecent(limit: Int): Flow<List<TransferRecord>> =
        dao.observeRecent(limit).map { it.map(TransferEntity::toModel) }
    override suspend fun updateStatus(transferId: String, status: TransferStatus, completedAtMs: Long?) =
        dao.updateStatus(transferId, status, completedAtMs)
    override suspend fun delete(transferId: String) = dao.delete(transferId)
    override suspend fun pruneOlderThan(beforeMs: Long) = dao.pruneOlderThan(beforeMs)
}

class ChunkRepositoryAdapter(private val dao: ChunkDao) : ChunkRepository {
    override suspend fun insertOrReplace(chunk: ChunkRecord) = dao.insertOrReplace(chunk.toEntity())
    override suspend fun insertAll(chunks: List<ChunkRecord>) = dao.insertAll(chunks.map(ChunkRecord::toEntity))
    override suspend fun getByTransfer(transferId: String): List<ChunkRecord> =
        dao.getByTransfer(transferId).map(ChunkEntity::toModel)
    override suspend fun getReceivedIndices(transferId: String): List<Int> = dao.getReceivedIndices(transferId)
    override suspend fun markAcked(transferId: String, chunkIndex: Int, ackedAtMs: Long) =
        dao.markAcked(transferId, chunkIndex, ackedAtMs)
    override suspend fun deleteAllForTransfer(transferId: String) = dao.deleteAllForTransfer(transferId)
}

private fun TransferRecord.toEntity() = TransferEntity(
    transferId, direction, contentType, mimeType, title, totalBytes, totalChunks,
    chunkSize, contentHash, recipientId, senderId, startedAtMs, completedAtMs, status,
)
private fun TransferEntity.toModel() = TransferRecord(
    transferId, direction, contentType, mimeType, title, totalBytes, totalChunks,
    chunkSize, contentHash, recipientId, senderId, startedAtMs, completedAtMs, status,
)
private fun ChunkRecord.toEntity() = ChunkEntity(transferId, chunkIndex, data, receivedAtMs, ackedAtMs)
private fun ChunkEntity.toModel() = ChunkRecord(transferId, chunkIndex, data, receivedAtMs, ackedAtMs)
