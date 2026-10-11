package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.Chunk
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.TransferStatus
import com.rumor.mesh.core.wire.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O159 — `TransferSender` had no coverage at all. Its two reactive paths are the
 * ones that matter: retransmitting exactly the chunks a receiver NACK'd (O109),
 * and shutting up when the receiver cancels (O18).
 *
 * Driven end to end: the NACK is a real `CHUNK_REQUEST` composed by the receiver's
 * engine and carried over `SimTransport`, so the sender decides from a signed
 * inbound message, not from a direct method call.
 */
class TransferSenderTest {

    /** New CHUNK message ids the sender composed since [before], with their indices. */
    private suspend fun retransmittedIndices(sender: SimNode, before: Set<String>): List<Int> {
        sender.flushSchedulerToRepo()
        return sender.knownMessages()
            .filter { it.type == MessageType.CHUNK && it.id !in before }
            .mapNotNull { runCatching { WireJson.decodeFromString<Chunk>(it.payload!!.content) }.getOrNull() }
            .map { it.chunkIndex }
            .sorted()
    }

    private suspend fun chunkIds(sender: SimNode): Set<String> {
        sender.flushSchedulerToRepo()
        return sender.knownMessages().filter { it.type == MessageType.CHUNK }.map { it.id }.toSet()
    }

    @Test
    fun `the sender retransmits exactly the NACKd chunks and nothing else`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val sender = SimNode(0, scope)
            val receiver = SimNode(1, scope)
            // 200 KB, because Chunker.chunk uses DEFAULT_CHUNK_SIZE = 60_000 and
            // sendFile does not take an override: a small payload is ONE chunk, and a
            // NACK for indices 1 and 3 of a 1-chunk transfer is correctly ignored —
            // which is how the first version of this test "passed nothing".
            val payload = ByteArray(200_000) { it.toByte() }
            val transferId = sender.transferSender.sendFile(
                recipientId = receiver.userId,
                contentType = ContentType.FILE,
                data = payload,
            ) ?: error("sendFile null")
            awaitUntil(what = "the sender to have cached 4 chunks for the transfer") {
                sender.chunkRepo.getByTransfer(transferId).size == 4
            }
            val before = chunkIds(sender)

            // The receiver asks for two specific chunks.
            receiver.gossipEngine.composeChunkRequest(transferId, listOf(1, 3), sender.userId)
            receiver.flushSchedulerToRepo()
            SimTransport(receiver, sender).exchange(kotlin.random.Random(3))

            awaitUntil(what = "the sender to retransmit the two requested chunks") {
                retransmittedIndices(sender, before).containsAll(listOf(1, 3))
            }
            assertEquals(
                "exactly the requested indices must be resent — a sender that replies to a " +
                    "NACK with the whole file hands the receiver's NACK an amplification lever",
                listOf(1, 3),
                retransmittedIndices(sender, before),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a NACK for an unknown transfer is ignored`() = runBlocking {
        // Control for the test above: the retransmit is driven by the sender's own
        // record of a transfer it is SENDING, not by the request alone. Otherwise any
        // peer could make this node emit chunk traffic for transfers it never had.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val sender = SimNode(0, scope)
            val receiver = SimNode(1, scope)
            val before = chunkIds(sender)

            receiver.gossipEngine.composeChunkRequest("tx-never-existed", listOf(0, 1, 2), sender.userId)
            receiver.flushSchedulerToRepo()
            SimTransport(receiver, sender).exchange(kotlin.random.Random(4))
            awaitUntil(what = "the request to have been delivered to the sender") {
                sender.gossipEngine.knownMessageIds().isNotEmpty()
            }
            kotlinx.coroutines.delay(200) // settle: absence assertion

            assertEquals(
                "no chunks may be emitted for a transfer this node never sent",
                emptyList<Int>(),
                retransmittedIndices(sender, before),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a receiver cancel abandons the transfer and silences further retransmits`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val sender = SimNode(0, scope)
            val receiver = SimNode(1, scope)
            val transferId = sender.transferSender.sendFile(
                recipientId = receiver.userId,
                contentType = ContentType.FILE,
                data = ByteArray(200_000) { it.toByte() },   // 4 chunks at 60 KB each
            ) ?: error("sendFile null")
            awaitUntil(what = "the transfer to be recorded as SENDING") {
                sender.transferRepo.getById(transferId) != null
            }

            // Positive control FIRST: before the cancel, a NACK does retransmit. Without
            // this, "nothing was resent after the cancel" could just mean the NACK path
            // never worked in this fixture at all.
            val before = chunkIds(sender)
            receiver.gossipEngine.composeChunkRequest(transferId, listOf(2), sender.userId)
            receiver.flushSchedulerToRepo()
            SimTransport(receiver, sender).exchange(kotlin.random.Random(5))
            awaitUntil(what = "the pre-cancel NACK to be served") {
                retransmittedIndices(sender, before).contains(2)
            }

            // Now cancel, then NACK again.
            receiver.gossipEngine.composeTransferCancel(transferId, sender.userId)
            receiver.flushSchedulerToRepo()
            SimTransport(receiver, sender).exchange(kotlin.random.Random(6))
            awaitUntil(what = "the sender to mark the cancelled transfer ABANDONED") {
                sender.transferRepo.getById(transferId)?.status == TransferStatus.ABANDONED
            }

            val afterCancel = chunkIds(sender)
            receiver.gossipEngine.composeChunkRequest(transferId, listOf(0, 1), sender.userId)
            receiver.flushSchedulerToRepo()
            SimTransport(receiver, sender).exchange(kotlin.random.Random(7))
            kotlinx.coroutines.delay(300) // settle: absence assertion

            assertTrue(
                "after a receiver cancel the sender must stop serving NACKs — it kept " +
                    "queueing chunks at a receiver that asked it to stop",
                retransmittedIndices(sender, afterCancel).isEmpty(),
            )
        } finally {
            scope.cancel()
        }
    }
}
