package com.rumor.mesh.core.transfer

import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Chunk
import com.rumor.mesh.core.model.ChunkRequest
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.TransferStatus
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.data.ChunkDao
import com.rumor.mesh.data.ChunkEntity
import com.rumor.mesh.data.TransferDao
import com.rumor.mesh.data.TransferDirection
import com.rumor.mesh.data.TransferEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

private const val TAG = "TransferSender"

/**
 * Outbound side of chunked transfers. Splits payloads via [Chunker], signs and
 * enqueues metadata + each chunk, persists chunks for re-transmission, and
 * responds to incoming CHUNK_REQUEST NACKs by re-emitting cached chunks.
 *
 * Pairs with [TransferAssembler] (which handles the receive side).
 */
class TransferSender(
    private val gossipEngine: GossipEngine,
    private val identityManager: IdentityManager,
    private val transferDao: TransferDao,
    private val chunkDao: ChunkDao,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Listen for incoming CHUNK_REQUEST NACKs addressed to us and retransmit.
        scope.launch {
            gossipEngine.incomingMessages.collect { msg ->
                if (msg.type != MessageType.CHUNK_REQUEST) return@collect
                val localId = identityManager.identity.value?.userId ?: return@collect
                if (msg.recipientId != localId) return@collect

                val req = runCatching {
                    Json.decodeFromString<ChunkRequest>(msg.payload?.content ?: return@collect)
                }.getOrNull() ?: return@collect

                handleChunkRequest(req, requesterId = msg.senderId)
            }
        }
    }

    /**
     * Compose an outbound chunked transfer. Returns the transferId or null if
     * identity is locked / the input is empty.
     */
    suspend fun sendFile(
        recipientId: String?,
        contentType: ContentType,
        data: ByteArray,
        mimeType: String? = null,
        title: String? = null,
    ): String? {
        if (data.isEmpty()) return null
        val identity = identityManager.identity.value ?: return null

        val (metadata, chunks) = Chunker.chunk(
            data = data,
            contentType = contentType,
            mimeType = mimeType,
            title = title,
            recipientId = recipientId,
        )

        // Persist outbound transfer + cache chunks for future NACK responses.
        transferDao.upsert(
            TransferEntity(
                transferId = metadata.transferId,
                direction = TransferDirection.SENDING,
                contentType = metadata.contentType,
                mimeType = metadata.mimeType,
                title = metadata.title,
                totalBytes = metadata.totalBytes,
                totalChunks = metadata.totalChunks,
                chunkSize = metadata.chunkSize,
                contentHash = metadata.contentHash,
                recipientId = recipientId,
                senderId = identity.userId,
                startedAtMs = System.currentTimeMillis(),
                completedAtMs = null,
                status = TransferStatus.IN_PROGRESS,
            )
        )
        val now = System.currentTimeMillis()
        chunkDao.insertAll(chunks.map { c ->
            ChunkEntity(
                transferId = metadata.transferId,
                chunkIndex = c.chunkIndex,
                data = Base64.getDecoder().decode(c.data),
                receivedAtMs = null,
                ackedAtMs = null,
            )
        })

        // Sign + enqueue metadata first (TRANSFER_METADATA).
        gossipEngine.composeOutbound(
            type = MessageType.TRANSFER_METADATA,
            payload = MessagePayload(ContentType.CONTROL, Json.encodeToString(metadata)),
            recipientId = recipientId,
        )

        // Sign + enqueue every chunk.
        for (chunk in chunks) {
            gossipEngine.composeOutbound(
                type = MessageType.CHUNK,
                payload = MessagePayload(ContentType.CONTROL, Json.encodeToString(chunk)),
                recipientId = recipientId,
            )
        }

        RumorLog.i(TAG, "Sending transfer ${metadata.transferId.take(8)}… (${metadata.totalChunks} chunks, ${data.size} bytes)")
        return metadata.transferId
    }

    private suspend fun handleChunkRequest(req: ChunkRequest, requesterId: String) {
        val transfer = transferDao.getById(req.transferId) ?: return
        if (transfer.direction != TransferDirection.SENDING) return
        if (transfer.status == TransferStatus.COMPLETE || transfer.status == TransferStatus.ABANDONED) return

        val cached = chunkDao.getByTransfer(req.transferId).associateBy { it.chunkIndex }
        val missing = req.missingIndices.filter { it in cached }
        RumorLog.i(TAG, "NACK from ${requesterId.take(8)}… for ${req.transferId.take(8)}…: re-sending ${missing.size} chunks")

        for (index in missing) {
            val entity = cached[index] ?: continue
            val chunk = Chunk(
                transferId = req.transferId,
                chunkIndex = index,
                totalChunks = transfer.totalChunks,
                data = Base64.getEncoder().encodeToString(entity.data),
            )
            gossipEngine.composeOutbound(
                type = MessageType.CHUNK,
                payload = MessagePayload(ContentType.CONTROL, Json.encodeToString(chunk)),
                recipientId = transfer.recipientId,
            )
        }
    }
}
