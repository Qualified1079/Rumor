package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.TransferDirection
import com.rumor.mesh.core.model.TransferStatus
import kotlinx.coroutines.flow.Flow

/**
 * Per-transfer header record. One row per chunked file/binary
 * transfer (inbound or outbound), with the metadata that lets the
 * receiver verify the assembly result (`contentHash`) and the
 * sender/UI track progress. The actual bytes live in [ChunkRecord]s,
 * keyed by `(transferId, chunkIndex)`.
 *
 * Status state machine: ANNOUNCED → IN_PROGRESS → (COMPLETE |
 * ABANDONED | FAILED). G16 / O18 adds pause / resume / cancel
 * controls; cancel transitions to ABANDONED, pause stays
 * IN_PROGRESS but the [com.rumor.mesh.core.transfer.TransferAssembler]
 * stops accepting chunks.
 */
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

/**
 * One chunk of a chunked transfer. Equality is by
 * `(transferId, chunkIndex, data)` so storage layers can dedup
 * identical re-sends after a NACK without touching the
 * `receivedAtMs` / `ackedAtMs` lifecycle fields. The chunker
 * splits at `chunkSize` from the parent [TransferRecord]; reassembly
 * iterates by index in ascending order and concatenates [data].
 */
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

/**
 * Header table for chunked transfers. Used by both the sender side
 * ([com.rumor.mesh.core.transfer.TransferSender]) and the receiver
 * side ([com.rumor.mesh.core.transfer.TransferAssembler]). UI reads
 * [observeByStatus] / [observeRecent] for the per-transfer progress
 * surface; cleanup uses [pruneOlderThan] to bound storage growth.
 */
interface TransferRepository {
    suspend fun upsert(transfer: TransferRecord)
    suspend fun getById(transferId: String): TransferRecord?
    fun observeByStatus(status: TransferStatus): Flow<List<TransferRecord>>
    fun observeRecent(limit: Int = 50): Flow<List<TransferRecord>>
    suspend fun updateStatus(transferId: String, status: TransferStatus, completedAtMs: Long?)
    suspend fun delete(transferId: String)
    suspend fun pruneOlderThan(beforeMs: Long)
}

/**
 * Per-chunk bytes table. Receiver side accumulates via
 * [insertOrReplace] until [getReceivedIndices] covers the full set,
 * then assembles. NACK / retransmission is the only reason a chunk
 * gets replaced (idempotent re-receive); ACK timestamping via
 * [markAcked] lets the sender side know which chunks the receiver
 * has confirmed without waiting for full assembly.
 */
interface ChunkRepository {
    suspend fun insertOrReplace(chunk: ChunkRecord)
    suspend fun insertAll(chunks: List<ChunkRecord>)
    suspend fun getByTransfer(transferId: String): List<ChunkRecord>
    suspend fun getReceivedIndices(transferId: String): List<Int>
    suspend fun markAcked(transferId: String, chunkIndex: Int, ackedAtMs: Long)
    suspend fun deleteAllForTransfer(transferId: String)
}
