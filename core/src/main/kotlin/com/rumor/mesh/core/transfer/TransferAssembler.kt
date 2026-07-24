package com.rumor.mesh.core.transfer

import com.rumor.mesh.core.SystemClock
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
import com.rumor.mesh.core.platform.Base64Codec
import com.rumor.mesh.core.platform.ConcurrentMap
import com.rumor.mesh.core.platform.ConcurrentSet
import com.rumor.mesh.core.wire.WireJson

private const val TAG = "TransferAssembler"
private const val NACK_INITIAL_DELAY_MS = 60_000L
private const val NACK_DELAY_CAP_MS = 600_000L
private const val NACK_MAX_RETRIES = 5

// O108: per-sender concurrent receiving-transfer cap (process-scoped). Sybil
// identities defeat any per-identity cap (O27 floor) — this bounds the lazy
// single-identity flood; the aggregate bound is storage quota + eviction (O23).
private const val MAX_ACTIVE_PER_SENDER = 8

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
    private val watchdogs = ConcurrentMap<String, Job>()

    /**
     * O108: active receiving transferId → senderId, for the per-sender cap and
     * for evidence-gating (metadata alone registers here; the watchdog + NACK
     * machinery only arms when the first real chunk arrives).
     */
    private val activeBySender = ConcurrentMap<String, String>()

    /**
     * O18: transferIds the user paused locally. Incoming CHUNKs for these are
     * dropped at ingest; the watchdog is left running so a pending transfer
     * doesn't time out while paused. Resume re-enables ingest.
     */
    private val paused = ConcurrentSet<String>()

    /**
     * O100: per-chunk hashes from the (signed) TransferMetadata, kept in memory
     * for the transfer's lifetime so each inbound chunk is verified at its index
     * on receipt — a corrupt or poisoned chunk is dropped and re-NACK'd rather
     * than silently accepted and only caught by the whole-file hash after the
     * whole transfer has already been paid for (the multi-seeder attribution
     * primitive). Not persisted: on a mid-transfer process restart it's lost and
     * integrity falls back to the whole-file hash in [Chunker.reassemble].
     */
    private val chunkHashesByTransfer = ConcurrentMap<String, List<String>>()

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
        // senderId is nullable on the repo record (legacy: it can be unknown
        // for inbound transfers whose metadata was lost). Without a sender we
        // can't tell anyone to stop, but we still drop local state.
        activeBySender.remove(transferId)
        chunkHashesByTransfer.remove(transferId)
        transfer.senderId?.let { gossipEngine.composeTransferCancel(transferId, it) }
        transferRepo.upsert(transfer.copy(
            status = TransferStatus.ABANDONED,
            completedAtMs = SystemClock.now(),
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
        // O108: reject inconsistent or absurd claims before committing any state.
        // A signed-but-lying metadata (totalChunks = Int.MAX_VALUE) previously
        // armed a watchdog and sized the whole receive path off the claim.
        if (!Chunker.isPlausibleMetadata(meta.totalChunks, meta.chunkSize, meta.totalBytes)) {
            RumorLog.w(
                TAG,
                "Rejecting transfer ${meta.transferId.take(8)}…: implausible metadata " +
                    "(chunks=${meta.totalChunks} size=${meta.chunkSize} bytes=${meta.totalBytes})",
            )
            return
        }
        // O108: per-sender concurrency cap. Excess metadata is dropped, not
        // queued — the sender can re-announce once a slot frees up.
        if (activeBySender.values.count { it == senderUserId } >= MAX_ACTIVE_PER_SENDER) {
            RumorLog.w(TAG, "Per-sender transfer cap hit for ${senderUserId.take(8)}… — dropping metadata")
            return
        }
        activeBySender[meta.transferId] = senderUserId
        // O100: retain the signed per-chunk hashes for receipt-time verification.
        meta.chunkHashes?.let { chunkHashesByTransfer[meta.transferId] = it }
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
                startedAtMs = SystemClock.now(),
                completedAtMs = null,
                status = TransferStatus.IN_PROGRESS,
            )
        )
        RumorLog.i(TAG, "Transfer ${meta.transferId.take(8)}… registered (${meta.totalChunks} chunks)")
        // O108 evidence-gating: no watchdog yet — a metadata with no chunk
        // behind it costs one DB row and nothing else. armWatchdog fires on
        // the first real chunk (handleChunk).
    }

    private suspend fun handleChunk(chunk: Chunk) {
        val transfer = transferRepo.getById(chunk.transferId) ?: return
        if (transfer.status != TransferStatus.IN_PROGRESS) return
        // O108: bound each chunk by the transfer's own accepted metadata —
        // out-of-range indices and oversized payloads never become rows.
        if (chunk.chunkIndex !in 0 until transfer.totalChunks) return
        val bytes = runCatching { Base64Codec.decode(chunk.data) }.getOrNull() ?: return
        if (bytes.size > transfer.chunkSize) return
        // O100: verify this chunk against the signed per-chunk hash before storing
        // it. A poisoned chunk (from a bad seeder or a corrupted relay) is dropped
        // here and left for the watchdog to re-NACK, instead of poisoning the store
        // and only surfacing as a whole-file hash mismatch after every chunk landed.
        val expectedHash = chunkHashesByTransfer[chunk.transferId]?.getOrNull(chunk.chunkIndex)
        if (expectedHash != null && Chunker.chunkHash(bytes) != expectedHash) {
            RumorLog.w(
                TAG,
                "Dropping poisoned chunk ${chunk.chunkIndex} of ${chunk.transferId.take(8)}… " +
                    "from ${transfer.senderId?.take(8)}… (per-chunk hash mismatch)",
            )
            return
        }
        // O108 evidence-gating: first real chunk arms the NACK watchdog.
        if (watchdogs[chunk.transferId] == null && transfer.senderId != null) {
            armWatchdog(chunk.transferId, transfer.totalChunks, transfer.senderId)
        }
        chunkRepo.insertOrReplace(
            ChunkRecord(
                transferId = chunk.transferId,
                chunkIndex = chunk.chunkIndex,
                data = bytes,
                receivedAtMs = SystemClock.now(),
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
            Chunk(e.transferId, e.chunkIndex, transfer.totalChunks, Base64Codec.encode(e.data))
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
            chunkHashes = chunkHashesByTransfer[transferId],
            recipientId = transfer.recipientId,
        )
        val data = Chunker.reassemble(chunks, meta) ?: run {
            RumorLog.w(TAG, "Reassembly failed for ${transferId.take(8)}… — hash mismatch or missing chunks")
            return
        }
        watchdogs.remove(transferId)?.cancel()
        activeBySender.remove(transferId)
        chunkHashesByTransfer.remove(transferId)
        transferRepo.updateStatus(transferId, TransferStatus.COMPLETE, SystemClock.now())
        RumorLog.i(TAG, "Transfer ${transferId.take(8)}… complete (${data.size}B)")
        _assembledTransfers.emit(AssembledTransfer(meta, data))
    }

    private fun armWatchdog(transferId: String, totalChunks: Int, senderUserId: String) {
        val job = scope.launch {
            var delayMs = NACK_INITIAL_DELAY_MS
            var retriesLeft = NACK_MAX_RETRIES
            var lastCount = -1
            while (retriesLeft > 0) {
                delay(delayMs)
                val transfer = transferRepo.getById(transferId)
                if (transfer == null || transfer.status != TransferStatus.IN_PROGRESS) return@launch
                val received = chunkRepo.getReceivedIndices(transferId).toSet()
                if (received.size >= totalChunks) { attemptReassembly(transferId); return@launch }
                // Progress refills the retry budget — a large multi-window
                // transfer keeps going as long as chunks keep landing; only a
                // genuinely stalled one burns down to abandonment.
                if (lastCount in 0 until received.size) {
                    retriesLeft = NACK_MAX_RETRIES
                    delayMs = NACK_INITIAL_DELAY_MS
                } else {
                    retriesLeft--
                    delayMs = minOf(delayMs * 2, NACK_DELAY_CAP_MS)
                }
                lastCount = received.size
                if (retriesLeft <= 0) break
                // O109: NACK a bounded window, never the whole missing set.
                val missing = Chunker.missingIndicesWindowed(received, totalChunks)
                RumorLog.d(TAG, "NACK for ${transferId.take(8)}…: ${missing.size} missing (windowed)")
                gossipEngine.composeChunkRequest(transferId, missing, senderUserId)
            }
            val transfer = transferRepo.getById(transferId)
            if (transfer?.status == TransferStatus.IN_PROGRESS) {
                RumorLog.w(TAG, "Transfer ${transferId.take(8)}… abandoned after $NACK_MAX_RETRIES stalled retries")
                transferRepo.updateStatus(transferId, TransferStatus.ABANDONED, SystemClock.now())
                // O108: abandoned transfers release their chunk rows — the
                // watchdog path previously leaked them forever (cancel() was
                // the only cleanup).
                chunkRepo.deleteAllForTransfer(transferId)
            }
            watchdogs.remove(transferId)
            activeBySender.remove(transferId)
            chunkHashesByTransfer.remove(transferId)
        }
        watchdogs[transferId] = job
    }
}

/**
 * The output of a completed transfer: the verified metadata envelope plus the
 * reassembled raw bytes. Emitted on [TransferAssembler.assembledTransfers] once
 * every chunk for a given transferId has arrived and the SHA-256 hash matches.
 *
 * [data] is *unverified by content*; only the byte-level integrity is checked.
 * App-layer extraction (e.g. compressed archives, image decoding) still has to
 * validate the payload before using it — see O14 / O28 / O83.
 */
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
