package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.Chunk
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.TransferMetadata
import com.rumor.mesh.core.platform.Base64Codec
import com.rumor.mesh.core.transfer.Chunker
import com.rumor.mesh.core.wire.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * O159 — first coverage for `TransferAssembler`'s receive-side hardening. Every
 * defence here (O108 metadata plausibility, the O108 per-sender concurrency cap,
 * index/size bounds, the O100 per-chunk hash check) shipped with **zero** tests;
 * O158's chunk-BLOB leak was the proof that that gap was live.
 *
 * Per docs/SIMULATOR_TESTING.md the hostile input is driven through the REAL
 * transport: each case composes a genuinely signed TRANSFER_METADATA / CHUNK on a
 * sender node via `composeOutbound` and ships it over `SimTransport`, so the
 * assembler sees it exactly as it would on the wire — not as a hand-fed object.
 *
 * **Every case carries its own discrimination control.** "Nothing was stored" is
 * the expected outcome of a working defence AND of a test whose message never
 * arrived, so each rejection is paired with an accepted twin that differs only in
 * the field under test. Without that pairing none of these assertions mean
 * anything.
 */
class TransferAssemblerHardeningTest {

    /** Small, legal metadata: 2 chunks of 10 bytes, hashes included. */
    private fun legalMetadata(
        transferId: String,
        payload: ByteArray = ByteArray(20) { it.toByte() },
        chunkSize: Int = 10,
    ): Pair<TransferMetadata, List<Chunk>> {
        val slices = payload.toList().chunked(chunkSize).map { it.toByteArray() }
        val meta = TransferMetadata(
            transferId = transferId,
            contentType = ContentType.FILE,
            mimeType = "application/octet-stream",
            title = "t",
            totalBytes = payload.size.toLong(),
            totalChunks = slices.size,
            chunkSize = chunkSize,
            contentHash = Chunker.chunkHash(payload),
            chunkHashes = slices.map { Chunker.chunkHash(it) },
            recipientId = null,
        )
        val chunks = slices.mapIndexed { i, b ->
            Chunk(transferId, i, slices.size, Base64Codec.encode(b), meta.contentHash)
        }
        return meta to chunks
    }

    private suspend fun ship(from: SimNode, to: SimNode, type: MessageType, json: String) {
        from.gossipEngine.composeOutbound(
            type = type,
            payload = MessagePayload(ContentType.CONTROL, json),
        ) ?: error("composeOutbound returned null")
        from.flushSchedulerToRepo()
        SimTransport(from, to).exchange(kotlin.random.Random(7))
    }

    private suspend fun shipMeta(from: SimNode, to: SimNode, meta: TransferMetadata) =
        ship(from, to, MessageType.TRANSFER_METADATA, WireJson.encodeToString(meta))

    private suspend fun shipChunk(from: SimNode, to: SimNode, chunk: Chunk) =
        ship(from, to, MessageType.CHUNK, WireJson.encodeToString(chunk))

    @Test
    fun `implausible metadata is rejected while its plausible twin registers`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val s = SimNode(0, scope); val r = SimNode(1, scope)

            // O108: totalChunks that does not match totalBytes/chunkSize. The
            // pre-O108 bug was committing the receive path to the CLAIM.
            val (good, _) = legalMetadata("tx-plausible")
            val lying = good.copy(transferId = "tx-lying", totalChunks = Int.MAX_VALUE)

            // Order matters, and it is the whole basis of the absence assertion: the
            // lying metadata goes FIRST and the plausible twin SECOND. The assembler
            // has a single collector on `incomingMessages` and processes emissions in
            // order, so once the twin has registered, the liar has already been seen
            // and decided on. Awaiting something weaker (e.g. "R has seen any
            // message") lets the assertion run before the liar is processed, and the
            // test then passes with the plausibility check DELETED — which is exactly
            // what the first version of this test did.
            shipMeta(s, r, lying)
            shipMeta(s, r, good)
            awaitUntil(what = "the plausible twin to register, proving the liar was already processed") {
                r.transferRepo.getById("tx-plausible") != null
            }
            assertEquals(2, r.transferRepo.getById("tx-plausible")!!.totalChunks)
            assertNull(
                "metadata whose totalChunks contradicts totalBytes/chunkSize must not create a transfer",
                r.transferRepo.getById("tx-lying"),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the per-sender cap stops one sender at eight and does not freeze other senders`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val s = SimNode(0, scope); val r = SimNode(1, scope); val other = SimNode(2, scope)

            // MAX_ACTIVE_PER_SENDER = 8. Announce 9 from one sender.
            repeat(9) { i ->
                val (meta, _) = legalMetadata("tx-flood-$i")
                shipMeta(s, r, meta)
            }
            awaitUntil(what = "the first eight of S's transfers to register") {
                (0..7).all { r.transferRepo.getById("tx-flood-$it") != null }
            }
            assertNull(
                "the 9th concurrent transfer from the same sender must be dropped, not queued",
                r.transferRepo.getById("tx-flood-8"),
            )

            // Control 1 — the cap is PER-SENDER. A different identity must still get
            // in; if this fails the cap has become a global denial-of-service that a
            // single sender can inflict on everyone else.
            val (fromOther, _) = legalMetadata("tx-other")
            shipMeta(other, r, fromOther)
            awaitUntil(what = "a different sender's transfer to register despite S being capped") {
                r.transferRepo.getById("tx-other") != null
            }

            // Control 2 — exactly eight of S's nine landed, so the number asserted
            // above is the cap and not an artifact of lost messages.
            assertEquals(8, (0..8).count { r.transferRepo.getById("tx-flood-$it") != null })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a chunk index outside the accepted metadata never becomes a row`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val s = SimNode(0, scope); val r = SimNode(1, scope)
            val (meta, chunks) = legalMetadata("tx-range")
            shipMeta(s, r, meta)
            awaitUntil(what = "the transfer to register") { r.transferRepo.getById("tx-range") != null }

            // Index 99 against a 2-chunk transfer.
            shipChunk(s, r, chunks[0].copy(chunkIndex = 99))
            // Control, shipped second: the in-range chunk DOES store. Awaiting it is
            // also the settle point that makes the absence assertion below honest —
            // both chunks travelled, so "index 99 is missing" is a decision, not a race.
            shipChunk(s, r, chunks[0])
            awaitUntil(what = "the in-range chunk 0 to be stored") {
                r.chunkRepo.getReceivedIndices("tx-range").contains(0)
            }
            assertEquals(
                "only the in-range chunk may be stored",
                listOf(0),
                r.chunkRepo.getReceivedIndices("tx-range").sorted(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a chunk larger than the transfer's own chunkSize is dropped`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val s = SimNode(0, scope); val r = SimNode(1, scope)
            val (meta, chunks) = legalMetadata("tx-size")
            shipMeta(s, r, meta)
            awaitUntil(what = "the transfer to register") { r.transferRepo.getById("tx-size") != null }

            // Same index, 10x the declared chunkSize — the storage-amplification shape.
            val fat = chunks[1].copy(data = Base64Codec.encode(ByteArray(10 * meta.chunkSize) { 9 }))
            shipChunk(s, r, fat)
            shipChunk(s, r, chunks[0])   // control: a right-sized chunk at index 0
            awaitUntil(what = "the right-sized control chunk to be stored") {
                r.chunkRepo.getReceivedIndices("tx-size").contains(0)
            }
            assertEquals(
                "the oversized chunk must not be stored at its index",
                listOf(0),
                r.chunkRepo.getReceivedIndices("tx-size").sorted(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a poisoned chunk is dropped and the honest bytes for that index still land`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val s = SimNode(0, scope); val r = SimNode(1, scope)
            val (meta, chunks) = legalMetadata("tx-poison")
            shipMeta(s, r, meta)
            awaitUntil(what = "the transfer to register") { r.transferRepo.getById("tx-poison") != null }

            // O100: right transfer, right index, wrong bytes — what a bad seeder
            // sends. Rejected on arrival by the signed per-chunk hash rather than
            // surfacing later as a whole-file mismatch after every chunk was paid for.
            val poisoned = chunks[0].copy(data = Base64Codec.encode(ByteArray(10) { 0x7f }))
            shipChunk(s, r, poisoned)
            awaitUntil(what = "R to have seen the poisoned chunk message") {
                r.gossipEngine.knownMessageIds().size >= 2
            }
            assertEquals(
                "a chunk failing its signed per-chunk hash must not be stored",
                emptyList<Int>(),
                r.chunkRepo.getReceivedIndices("tx-poison").sorted(),
            )

            // Control — the SAME index with the honest bytes stores fine. This is what
            // separates "the hash check works" from "chunk 0 can never be stored", and
            // proves the drop left the index repairable (what the NACK path relies on).
            shipChunk(s, r, chunks[0])
            awaitUntil(what = "the honest bytes for the same index to be stored") {
                r.chunkRepo.getReceivedIndices("tx-poison").contains(0)
            }
            assertNotNull(r.chunkRepo.getByTransfer("tx-poison").firstOrNull { it.chunkIndex == 0 })
        } finally {
            scope.cancel()
        }
    }
}
