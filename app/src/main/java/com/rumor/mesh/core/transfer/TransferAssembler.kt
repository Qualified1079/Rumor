package com.rumor.mesh.core.transfer

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Chunk
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.TransferMetadata
import com.rumor.mesh.core.model.TransferStatus
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.data.ChunkDao
import com.rumor.mesh.data.ChunkEntity
import com.rumor.mesh.data.TransferDao
import com.rumor.mesh.data.TransferDirection
import com.rumor.mesh.data.TransferEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "TransferAssembler"

/** Initial NACK backoff delay in ms. Doubles each retry, capped at [NACK_DELAY_CAP_MS]. */
private const val NACK_INITIAL_DELAY_MS = 60_000L
private const val NACK_DELAY_CAP_MS = 600_000L   // 10 minutes
private const val NACK_MAX_RETRIES = 5

/**
 * Buffers incoming TRANSFER_METADATA and CHUNK messages, detects missing chunks,
 * and fires CHUNK_REQUEST NACKs with exponential backoff until all chunks arrive
 * or the retry limit is exhausted.
 *
 * Subscribes to [GossipEngine.incomingMessages] and calls back into
 * [GossipEngine.composeChunkRequest] for NACK transmission.
 *
 * On successful reassembly [assembledTransfers] emits the metadata + raw bytes.
 * Callers (UI, plugins) subscribe to this flow to receive completed files.
 */
class TransferAssembler(
    private val gossipEngine: GossipEngine,
    private val transferDao: TransferDao,
    private val chunkDao: ChunkDao,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** In-progress NACK watchdog jobs, keyed by transferId. */
    private val watchdogs = ConcurrentHashMap<String, WatchdogState>()

    private val _assembledTransfers = MutableSharedFlow<AssembledTransfer>(extraBufferCapacity = 32)
    val assembledTransfers: SharedFlow<AssembledTransfer> = _assembledTransfers

    init {
        scope.launch {
            gossipEngine.incomingMessages.collect { msg ->
                when (msg.type) {
                    MessageType.TRANSFER_METADATA -> {
                        val meta = runCatching {
                            Json.decodeFromString<TransferMetadata>(msg.payload?.content ?: return@collect)
                        }.getOrNull() ?: return@collect
                        handleMetadata(meta, senderUserId = msg.senderId)
                    }
                    MessageType.CHUNK -> {
                        val chunk = runCatching {
                            Json.decodeFromString<Chunk>(msg.payload?.content ?: return@collect)
                        }.getOrNull() ?: return@collect
                        handleChunk(chunk)
                    }
                    else -> {}
                }
            }
        }
    }

    // ── Incoming handlers ─────────────────────────────────────────────────────

    private suspend fun handleMetadata(meta: TransferMetadata, senderUserId: String) {
        val existing = transferDao.getById(meta.transferId)
        if (existing != null) return  // already tracking

        transferDao.upsert(
            TransferEntity(
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
        RumorLog.i(TAG, "Transfer ${meta.transferId.take(8)}… registered (${meta.totalChunks} chunks, ${meta.totalBytes} bytes)")
        armWatchdog(meta.transferId, meta.totalChunks, senderUserId)
    }

    private suspend fun handleChunk(chunk: Chunk) {
        val transfer = transferDao.getById(chunk.transferId) ?: return
        if (transfer.status != TransferStatus.IN_PROGRESS) return

        chunkDao.insertOrReplace(
            ChunkEntity(
                transferId = chunk.transferId,
                chunkIndex = chunk.chunkIndex,
                data = Base64.getDecoder().decode(chunk.data),
                receivedAtMs = System.currentTimeMillis(),
                ackedAtMs = null,
            )
        )

        val received = chunkDao.getReceivedIndices(chunk.transferId).toSet()
        if (received.size >= transfer.totalChunks) {
            attemptReassembly(chunk.transferId)
        }
    }

    // ── Reassembly ────────────────────────────────────────────────────────────

    private suspend fun attemptReassembly(transferId: String) {
        val transfer = transferDao.getById(transferId) ?: return
        val rawChunks = chunkDao.getByTransfer(transferId)

        val chunks = rawChunks.map { entity ->
            Chunk(
                transferId = entity.transferId,
                chunkIndex = entity.chunkIndex,
                totalChunks = transfer.totalChunks,
                data = Base64.getEncoder().encodeToString(entity.data),
            )
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

        val data = Chunker.reassemble(chunks, meta)
        if (data == null) {
            RumorLog.w(TAG, "Reassembly failed for ${transferId.take(8)}… — hash mismatch or missing chunks")
            return
        }

        cancelWatchdog(transferId)
        transferDao.updateStatus(transferId, TransferStatus.COMPLETE, System.currentTimeMillis())
        RumorLog.i(TAG, "Transfer ${transferId.take(8)}… complete (${data.size} bytes)")
        _assembledTransfers.emit(AssembledTransfer(meta, data))
    }

    // ── NACK watchdog ─────────────────────────────────────────────────────────

    private fun armWatchdog(transferId: String, totalChunks: Int, senderUserId: String) {
        val job = scope.launch {
            var delayMs = NACK_INITIAL_DELAY_MS
            var retries = 0
            while (retries < NACK_MAX_RETRIES) {
                delay(delayMs)

                val transfer = transferDao.getById(transferId)
                if (transfer == null || transfer.status != TransferStatus.IN_PROGRESS) break

                val received = chunkDao.getReceivedIndices(transferId).toSet()
                if (received.size >= totalChunks) {
                    attemptReassembly(transferId)
                    break
                }

                val missing = Chunker.missingIndices(received, totalChunks)
                RumorLog.d(TAG, "NACK retry $retries for ${transferId.take(8)}…: ${missing.size} missing")
                gossipEngine.composeChunkRequest(transferId, missing, senderUserId)

                retries++
                delayMs = minOf(delayMs * 2, NACK_DELAY_CAP_MS)
            }

            // Max retries exhausted — give up.
            val transfer = transferDao.getById(transferId)
            if (transfer?.status == TransferStatus.IN_PROGRESS) {
                RumorLog.w(TAG, "Transfer ${transferId.take(8)}… abandoned after $NACK_MAX_RETRIES retries")
                transferDao.updateStatus(transferId, TransferStatus.ABANDONED, System.currentTimeMillis())
            }
            watchdogs.remove(transferId)
        }
        watchdogs[transferId] = WatchdogState(job)
    }

    private fun cancelWatchdog(transferId: String) {
        watchdogs.remove(transferId)?.job?.cancel()
    }

    private class WatchdogState(val job: Job)
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
