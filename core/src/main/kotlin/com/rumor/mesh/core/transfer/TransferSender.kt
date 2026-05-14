package com.rumor.mesh.core.transfer

import com.rumor.mesh.core.data.ChunkRecord
import com.rumor.mesh.core.data.ChunkRepository
import com.rumor.mesh.core.data.TransferRecord
import com.rumor.mesh.core.data.TransferRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Chunk
import com.rumor.mesh.core.model.ChunkRequest
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.TransferDirection
import com.rumor.mesh.core.model.TransferStatus
import com.rumor.mesh.core.protocol.GossipEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

private const val TAG = "TransferSender"

/**
 * Outbound side of chunked transfers. Chunks data, enqueues metadata + chunks
 * through the gossip engine, and retransmits on CHUNK_REQUEST NACKs.
 *
 * Pairs with [TransferAssembler] on the receive side.
 */
class TransferSender(
    private val gossipEngine: GossipEngine,
    private val identityProvider: IdentityProvider,
    private val transferRepo: TransferRepository,
    private val chunkRepo: ChunkRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            gossipEngine.incomingMessages.collect { msg ->
                if (msg.type != MessageType.CHUNK_REQUEST) return@collect
                val localId = identityProvider.identity.value?.userId ?: return@collect
                if (msg.recipientId != localId) return@collect
                val req = runCatching {
                    Json.decodeFromString<ChunkRequest>(msg.payload?.content ?: return@collect)
                }.getOrNull() ?: return@collect
                handleChunkRequest(req, requesterId = msg.senderId)
            }
        }
    }

    suspend fun sendFile(
        recipientId: String?,
        contentType: ContentType,
        data: ByteArray,
        mimeType: String? = null,
        title: String? = null,
    ): String? {
        if (data.isEmpty()) return null
        val identity = identityProvider.identity.value ?: return null

        val (metadata, chunks) = Chunker.chunk(data, contentType, mimeType, title, recipientId)

        transferRepo.upsert(
            TransferRecord(
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
        chunkRepo.insertAll(chunks.map { c ->
            ChunkRecord(
                transferId = metadata.transferId,
                chunkIndex = c.chunkIndex,
                data = Base64.getDecoder().decode(c.data),
                receivedAtMs = null,
                ackedAtMs = null,
            )
        })

        gossipEngine.composeOutbound(
            type = MessageType.TRANSFER_METADATA,
            payload = MessagePayload(ContentType.CONTROL, Json.encodeToString(metadata)),
            recipientId = recipientId,
        )
        for (chunk in chunks) {
            gossipEngine.composeOutbound(
                type = MessageType.CHUNK,
                payload = MessagePayload(ContentType.CONTROL, Json.encodeToString(chunk)),
                recipientId = recipientId,
            )
        }

        RumorLog.i(TAG, "Sending ${metadata.transferId.take(8)}… (${metadata.totalChunks} chunks, ${data.size}B)")
        return metadata.transferId
    }

    private suspend fun handleChunkRequest(req: ChunkRequest, requesterId: String) {
        val transfer = transferRepo.getById(req.transferId) ?: return
        if (transfer.direction != TransferDirection.SENDING) return
        if (transfer.status == TransferStatus.COMPLETE || transfer.status == TransferStatus.ABANDONED) return

        val cached = chunkRepo.getByTransfer(req.transferId).associateBy { it.chunkIndex }
        val toResend = req.missingIndices.filter { it in cached }
        RumorLog.i(TAG, "NACK from ${requesterId.take(8)}…: resending ${toResend.size} chunks")

        for (index in toResend) {
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
