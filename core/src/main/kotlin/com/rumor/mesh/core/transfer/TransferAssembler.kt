package com.rumor.mesh.core.transfer

import com.rumor.mesh.core.data.ChunkRecord
import com.rumor.mesh.core.data.ChunkRepository
import com.rumor.mesh.core.data.TransferRecord
import com.rumor.mesh.core.data.TransferRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Chunk
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.TransferDirection
import com.rumor.mesh.core.model.TransferMetadata
import com.rumor.mesh.core.model.TransferStatus
import com.rumor.mesh.core.protocol.GossipEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import com.rumor.mesh.core.wire.WireJson

private const val TAG = "TransferAssembler"
private const val NACK_INITIAL_DELAY_MS = 60_000L
private const val NACK_DELAY_CAP_MS = 600_000L
private const val NACK_MAX_RETRIES = 5

/**
 * Receive side of chunked transfers. Buffers incoming metadata and chunks,
 * fires CHUNK_REQUEST NACKs with exponential backoff for missing chunks,
 * and emits completed transfers on [assembledTransfers].
 */
class TransferAssembler(
    private val gossipEngine: GossipEngine,
    private val transferRepo: TransferRepository,
    private val chunkRepo: ChunkRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val watchdogs = ConcurrentHashMap<String, Job>()

    /**
     * O18: transferIds the user paused locally. Incoming CHUNKs for these are
     * dropped at ingest; the watchdog is left running so a pending transfer
     * doesn't time out while paused. Resume re-enables ingest.
     */
    private val paused: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val _assembledTransfers = MutableSharedFlow<AssembledTransfer>(extraBufferCapacity = 32)
    val assembledTransfers: SharedFlow<AssembledTransfer> = _assembledTransfers

    fun pause(transferId: String) { paused.add(transferId) }
    fun resume(transferId: String) { paused.remove(transferId) }
    fun isPaused(transferId: String): Boolean = transferId in paused

    /**
     * O18 receiver-initiated cancel: tells the original sender to stop
     * queuing chunks via [GossipEngine.composeTransferCancel], drops the
     * local transfer record, and cancels any watchdog.
     */
    suspend fun cancel(transferId: String) {
        val transfer = transferRepo.getById(transferId) ?: return
        watchdogs.remove(transferId)?.cancel()
        paused.remove(transferId)
        gossipEngine.composeTransferCancel(transferId, transfer.senderId)
        transferRepo.upsert(transfer.copy(
            status = TransferStatus.ABANDONED,
            completedAtMs = System.currentTimeMillis(),
        ))
        chunkRepo.deleteAllForTransfer(transferId)
    }

    init {
        scope.launch {
            gossipEngine.incomingMessages.collect { msg ->
                when (msg.type) {
                    MessageType.TRANSFER_METADATA -> {
                        val meta = runCatching {
                            WireJson.decodeFromString<TransferMetadata>(msg.payload?.content ?: return@collect)
                        }.getOrNull() ?: return@collect
                        handleMetadata(meta, senderUserId = msg.senderId)
                    }
                    MessageType.CHUNK -> {
                        val chunk = runCatching {
                            WireJson.decodeFromString<Chunk>(msg.payload?.content ?: return@collect)
                        }.getOrNull() ?: return@collect
                        // O18: paused transfers drop chunks at ingest. Watchdog
                        // keeps running so the transfer doesn't time out while paused.
                        if (chunk.transferId in paused) return@collect
                        handleChunk(chunk)
                    }
                    else -> {}
                }
            }
        }
    }

    private suspend fun handleMetadata(meta: TransferMetadata, senderUserId: String) {
        if (transferRepo.getById(meta.transferId) != null) return
        transferRepo.upsert(
            TransferRecord(
                transferId = meta.transferId,
                direction = TransferDirection.RECEIVING,
                contentType = meta.contentType,
                mimeType = meta.mimeType,
                title = meta.title,
                totalBytes = meta.totalBytes,
                totalChunks = meta.totalChunks,
                chunkSize = meta.chunkSize,
                contentHash = meta.contentHash,
                recipientId = meta.recipientId,
                senderId = senderUserId,
                startedAtMs = System.currentTimeMillis(),
                completedAtMs = null,
                status = TransferStatus.IN_PROGRESS,
            )
        )
        RumorLog.i(TAG, "Transfer ${meta.transferId.take(8)}… registered (${meta.totalChunks} chunks)")
        armWatchdog(meta.transferId, meta.totalChunks, senderUserId)
    }

    private suspend fun handleChunk(chunk: Chunk) {
        val transfer = transferRepo.getById(chunk.transferId) ?: return
        if (transfer.status != TransferStatus.IN_PROGRESS) return
        chunkRepo.insertOrReplace(
            ChunkRecord(
                transferId = chunk.transferId,
                chunkIndex = chunk.chunkIndex,
                data = Base64.getDecoder().decode(chunk.data),
                receivedAtMs = System.currentTimeMillis(),
                ackedAtMs = null,
            )
        )
        val received = chunkRepo.getReceivedIndices(chunk.transferId).toSet()
        if (received.size >= transfer.totalChunks) attemptReassembly(chunk.transferId)
    }

    private suspend fun attemptReassembly(transferId: String) {
        val transfer = transferRepo.getById(transferId) ?: return
        val rawChunks = chunkRepo.getByTransfer(transferId)
        val chunks = rawChunks.map { e ->
            Chunk(e.transferId, e.chunkIndex, transfer.totalChunks, Base64.getEncoder().encodeToString(e.data))
        }
        val meta = TransferMetadata(
            transferId = transfer.transferId,
            contentType = transfer.contentType,
            mimeType = transfer.mimeType,
            title = transfer.title,
            totalBytes = transfer.totalBytes,
            totalChunks = transfer.totalChunks,
            chunkSize = transfer.chunkSize,
            contentHash = transfer.contentHash,
            recipientId = transfer.recipientId,
        )
        val data = Chunker.reassemble(chunks, meta) ?: run {
            RumorLog.w(TAG, "Reassembly failed for ${transferId.take(8)}… — hash mismatch or missing chunks")
            return
        }
        watchdogs.remove(transferId)?.cancel()
        transferRepo.updateStatus(transferId, TransferStatus.COMPLETE, System.currentTimeMillis())
        RumorLog.i(TAG, "Transfer ${transferId.take(8)}… complete (${data.size}B)")
        _assembledTransfers.emit(AssembledTransfer(meta, data))
    }

    private fun armWatchdog(transferId: String, totalChunks: Int, senderUserId: String) {
        val job = scope.launch {
            var delayMs = NACK_INITIAL_DELAY_MS
            repeat(NACK_MAX_RETRIES) { retry ->
                delay(delayMs)
                val transfer = transferRepo.getById(transferId)
                if (transfer == null || transfer.status != TransferStatus.IN_PROGRESS) return@launch
                val received = chunkRepo.getReceivedIndices(transferId).toSet()
                if (received.size >= totalChunks) { attemptReassembly(transferId); return@launch }
                val missing = Chunker.missingIndices(received, totalChunks)
                RumorLog.d(TAG, "NACK retry $retry for ${transferId.take(8)}…: ${missing.size} missing")
                gossipEngine.composeChunkRequest(transferId, missing, senderUserId)
                delayMs = minOf(delayMs * 2, NACK_DELAY_CAP_MS)
            }
            val transfer = transferRepo.getById(transferId)
            if (transfer?.status == TransferStatus.IN_PROGRESS) {
                RumorLog.w(TAG, "Transfer ${transferId.take(8)}… abandoned after $NACK_MAX_RETRIES retries")
                transferRepo.updateStatus(transferId, TransferStatus.ABANDONED, System.currentTimeMillis())
            }
            watchdogs.remove(transferId)
        }
        watchdogs[transferId] = job
    }
}

data class AssembledTransfer(
    val metadata: TransferMetadata,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssembledTransfer) return false
        return metadata == other.metadata && data.contentEquals(other.data)
    }
    override fun hashCode(): Int = 31 * metadata.hashCode() + data.contentHashCode()
}
