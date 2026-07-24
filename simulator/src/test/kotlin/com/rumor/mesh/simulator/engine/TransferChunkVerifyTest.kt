package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.Chunk
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.TransferMetadata
import com.rumor.mesh.core.transfer.Chunker
import com.rumor.mesh.core.platform.Base64Codec
import com.rumor.mesh.core.wire.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * O100 — per-chunk hash verification on the real receive path.
 *
 * The assembler subscribes to `GossipEngine.incomingMessages`; these tests drive
 * genuinely-signed TRANSFER_METADATA + CHUNK messages sender→receiver over the
 * SimTransport exchange (not asserting on bare objects — the O129 trap) and check
 * that the assembler reassembles a clean transfer, drops a chunk whose bytes don't
 * match the signed per-chunk hash, and completes once a correct repair chunk for
 * that same content group + index arrives (mechanism-B repair on the new
 * content-addressed substrate).
 */
class TransferChunkVerifyTest {

    // 150 KB → three 60/60/30 KB chunks; index 1 is a full 60 KB chunk we poison.
    private val payload = ByteArray(150_000) { (it % 251).toByte() }

    private suspend fun compose(sender: SimNode, receiverId: String, type: MessageType, content: String) {
        sender.gossipEngine.composeOutbound(
            type = type,
            payload = MessagePayload(ContentType.CONTROL, content),
            recipientId = receiverId,
        )
    }

    private suspend fun sendMeta(sender: SimNode, receiverId: String, meta: TransferMetadata) =
        compose(sender, receiverId, MessageType.TRANSFER_METADATA, WireJson.encodeToString(meta))

    private suspend fun sendChunk(sender: SimNode, receiverId: String, chunk: Chunk) =
        compose(sender, receiverId, MessageType.CHUNK, WireJson.encodeToString(chunk))

    @Test
    fun `clean transfer reassembles end-to-end`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sender = SimNode(0, scope)
        val receiver = SimNode(1, scope)
        try {
            val (meta, chunks) = Chunker.chunk(payload, ContentType.FILE, recipientId = receiver.userId)
            sendMeta(sender, receiver.userId, meta)
            chunks.forEach { sendChunk(sender, receiver.userId, it) }
            sender.flushSchedulerToRepo()

            SimTransport(sender, receiver).exchange(kotlin.random.Random(1))
            awaitUntil(5_000) { receiver.reassembledTransfers.value == 1L }
            assertEquals("a clean transfer must reassemble", 1L, receiver.reassembledTransfers.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `poisoned chunk is dropped and the transfer completes only after a good repair`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sender = SimNode(0, scope)
        val receiver = SimNode(1, scope)
        try {
            // One content group; every chunk here shares the same transferId.
            val (meta, chunks) = Chunker.chunk(payload, ContentType.FILE, recipientId = receiver.userId)
            // A genuinely-poisoned chunk 1: same index + declared group, bytes that
            // don't hash to the signed metadata.chunkHashes[1].
            val poisoned = chunks[1].copy(data = Base64Codec.encode(ByteArray(60_000) { 0x11 }))

            sendMeta(sender, receiver.userId, meta)
            sendChunk(sender, receiver.userId, chunks[0])
            sendChunk(sender, receiver.userId, poisoned)
            sendChunk(sender, receiver.userId, chunks[2])
            sender.flushSchedulerToRepo()
            SimTransport(sender, receiver).exchange(kotlin.random.Random(1))

            // Poisoned chunk 1 must be dropped at receipt, so only 0 and 2 are held
            // and reassembly can't complete. Settle, then assert it did NOT.
            delay(400)
            assertEquals(
                "a poisoned chunk (hash mismatch) must be dropped, blocking completion",
                0L, receiver.reassembledTransfers.value,
            )

            // Mechanism-B repair: the CORRECT chunk 1 for the same transferId.
            sendChunk(sender, receiver.userId, chunks[1])
            sender.flushSchedulerToRepo()
            SimTransport(sender, receiver).exchange(kotlin.random.Random(2))
            awaitUntil(5_000) { receiver.reassembledTransfers.value == 1L }
            assertEquals(
                "a correct repair chunk must complete the transfer",
                1L, receiver.reassembledTransfers.value,
            )
        } finally {
            scope.cancel()
        }
    }
}
