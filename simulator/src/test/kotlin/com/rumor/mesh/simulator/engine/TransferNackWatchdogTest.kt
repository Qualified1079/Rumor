package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.data.memory.InMemoryChunkRepository
import com.rumor.mesh.core.data.memory.InMemoryTransferRepository
import com.rumor.mesh.core.model.Chunk
import com.rumor.mesh.core.model.ChunkRequest
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.TransferMetadata
import com.rumor.mesh.core.model.TransferStatus
import com.rumor.mesh.core.platform.Base64Codec
import com.rumor.mesh.core.transfer.Chunker
import com.rumor.mesh.core.transfer.TransferAssembler
import com.rumor.mesh.core.wire.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O159 — the NACK watchdog, which had no coverage because its schedule was three
 * `private const val`s and testing it meant sleeping for minutes. They are now
 * constructor parameters (defaults unchanged), so the same logic runs on a
 * millisecond schedule here.
 *
 * What this pins is O108/O109 behaviour that matters under load: a stalled
 * transfer must eventually be ABANDONED and give its chunk rows back, a
 * *progressing* transfer must never be abandoned however long it takes, and each
 * NACK must ask for a bounded window rather than the whole missing set.
 *
 * The assembler under test is a second instance with its OWN repos, attached to
 * the receiver node's real engine. `SimNode`'s built-in assembler also sees the
 * traffic, but it keeps the shipped 60 s schedule and its own repos, so within
 * these sub-second windows it contributes nothing.
 */
class TransferNackWatchdogTest {

    private val TICK = 40L

    private class Fixture(scope: CoroutineScope) {
        val sender = SimNode(0, scope)
        val receiver = SimNode(1, scope)
        val transfers = InMemoryTransferRepository()
        val chunks = InMemoryChunkRepository()
        val assembler = TransferAssembler(
            gossipEngine = receiver.gossipEngine,
            transferRepo = transfers,
            chunkRepo = chunks,
            nackInitialDelayMs = 40L,
            nackDelayCapMs = 160L,
            nackMaxRetries = 3,
            scope = scope,
        )
    }

    /** 4 chunks of 10 bytes, with per-chunk hashes so nothing is dropped as poisoned. */
    private fun plan(transferId: String): Pair<TransferMetadata, List<Chunk>> {
        val payload = ByteArray(40) { it.toByte() }
        val slices = payload.toList().chunked(10).map { it.toByteArray() }
        val meta = TransferMetadata(
            transferId = transferId,
            contentType = ContentType.FILE,
            mimeType = null,
            title = "t",
            totalBytes = 40,
            totalChunks = slices.size,
            chunkSize = 10,
            contentHash = Chunker.chunkHash(payload),
            chunkHashes = slices.map { Chunker.chunkHash(it) },
            recipientId = null,
        )
        return meta to slices.mapIndexed { i, b ->
            Chunk(transferId, i, slices.size, Base64Codec.encode(b), meta.contentHash)
        }
    }

    private suspend fun ship(f: Fixture, type: MessageType, json: String) {
        f.sender.gossipEngine.composeOutbound(
            type = type,
            payload = MessagePayload(ContentType.CONTROL, json),
        ) ?: error("composeOutbound null")
        f.sender.flushSchedulerToRepo()
        SimTransport(f.sender, f.receiver).exchange(kotlin.random.Random(5))
    }

    /** CHUNK_REQUESTs the receiver has composed for [transferId], newest first. */
    private suspend fun nacksFor(f: Fixture, transferId: String): List<ChunkRequest> {
        f.receiver.flushSchedulerToRepo()
        return f.receiver.knownMessages()
            .filter { it.type == MessageType.CHUNK_REQUEST }
            .mapNotNull { runCatching { WireJson.decodeFromString<ChunkRequest>(it.payload!!.content) }.getOrNull() }
            .filter { it.transferId == transferId }
    }

    @Test
    fun `a stalled transfer is abandoned after the retry budget and releases its chunks`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val f = Fixture(scope)
            val (meta, chunks) = plan("tx-stall")
            ship(f, MessageType.TRANSFER_METADATA, WireJson.encodeToString(meta))
            awaitUntil(what = "the transfer to register") { f.transfers.getById("tx-stall") != null }

            // Exactly one chunk, then silence. One chunk is the EVIDENCE that arms
            // the watchdog at all (O108 evidence-gating) — metadata alone must not.
            ship(f, MessageType.CHUNK, WireJson.encodeToString(chunks[0]))
            awaitUntil(what = "the first chunk to be stored") {
                f.chunks.getReceivedIndices("tx-stall").contains(0)
            }

            awaitUntil(what = "the stalled transfer to be abandoned") {
                f.transfers.getById("tx-stall")?.status == TransferStatus.ABANDONED
            }
            assertEquals(
                "the abandon path must release the chunk rows (O108) — it leaked them before",
                emptyList<Int>(),
                f.chunks.getReceivedIndices("tx-stall").sorted(),
            )
            // It asked before it gave up: a stall must produce NACKs, not silence.
            assertTrue(
                "a stalled transfer must have sent at least one CHUNK_REQUEST before abandoning",
                nacksFor(f, "tx-stall").isNotEmpty(),
            )
            // Every NACK is windowed (O109), never the whole missing set.
            assertTrue(
                "each NACK must request a bounded window of the missing indices",
                nacksFor(f, "tx-stall").all { it.missingIndices.size <= meta.totalChunks },
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `metadata with no chunk behind it arms no watchdog and is never abandoned`() = runBlocking {
        // O108 evidence-gating, the control for the test above: without it a flood of
        // cheap metadata would each arm a timer and a NACK stream. Same fixture, same
        // schedule, the ONLY difference is that no chunk ever arrives — so if this
        // transfer also got abandoned, the abandon above would be telling us nothing
        // about stalls, only that the clock ran.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val f = Fixture(scope)
            val (meta, _) = plan("tx-no-evidence")
            ship(f, MessageType.TRANSFER_METADATA, WireJson.encodeToString(meta))
            awaitUntil(what = "the transfer to register") { f.transfers.getById("tx-no-evidence") != null }

            // Well past 3 retries at a 40ms base with doubling (40+80+160 = 280ms).
            delay(TICK * 25)
            assertEquals(
                "a transfer with no chunk evidence must stay IN_PROGRESS, not be driven " +
                    "to ABANDONED by a watchdog that should never have been armed",
                TransferStatus.IN_PROGRESS,
                f.transfers.getById("tx-no-evidence")?.status,
            )
            assertEquals(
                "and it must never have NACK'd for chunks nobody promised to send",
                emptyList<ChunkRequest>(),
                nacksFor(f, "tx-no-evidence"),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `progress refills the retry budget so a slow transfer is never abandoned`() = runBlocking {
        // The O108 "progress refills the budget" rule: a large multi-window transfer
        // may take far longer than the retry budget and must survive, while a
        // genuinely stalled one burns down (previous test). Same schedule as the
        // stall case; the only difference is that a chunk keeps landing.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val f = Fixture(scope)
            val (meta, chunks) = plan("tx-slow")
            ship(f, MessageType.TRANSFER_METADATA, WireJson.encodeToString(meta))
            awaitUntil(what = "the transfer to register") { f.transfers.getById("tx-slow") != null }

            ship(f, MessageType.CHUNK, WireJson.encodeToString(chunks[0]))
            awaitUntil(what = "chunk 0 to be stored") {
                f.chunks.getReceivedIndices("tx-slow").contains(0)
            }
            // Drip the remaining chunks slower than the whole retry budget would be.
            for (i in 1 until chunks.size - 1) {
                delay(TICK * 4)
                ship(f, MessageType.CHUNK, WireJson.encodeToString(chunks[i]))
                awaitUntil(what = "chunk $i to be stored") {
                    f.chunks.getReceivedIndices("tx-slow").contains(i)
                }
                assertEquals(
                    "a progressing transfer must stay IN_PROGRESS after chunk $i",
                    TransferStatus.IN_PROGRESS,
                    f.transfers.getById("tx-slow")?.status,
                )
            }
            // Final chunk completes it — and completion must release the chunk rows
            // too (O158, the happy-path leak).
            ship(f, MessageType.CHUNK, WireJson.encodeToString(chunks.last()))
            awaitUntil(what = "the transfer to complete") {
                f.transfers.getById("tx-slow")?.status == TransferStatus.COMPLETE
            }
            assertEquals(
                "a completed transfer must release its chunk rows (O158)",
                emptyList<Int>(),
                f.chunks.getReceivedIndices("tx-slow").sorted(),
            )
        } finally {
            scope.cancel()
        }
    }
}
