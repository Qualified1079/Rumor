package com.rumor.mesh.core.transfer

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.data.memory.InMemoryBlockEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryBlocklistEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryChunkRepository
import com.rumor.mesh.core.data.memory.InMemoryContactRepository
import com.rumor.mesh.core.data.memory.InMemoryMessageRepository
import com.rumor.mesh.core.data.memory.InMemoryRouteRepository
import com.rumor.mesh.core.data.memory.InMemorySubscribedBlocklistRepository
import com.rumor.mesh.core.data.memory.InMemoryTransferRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.model.Chunk
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.TransferMetadata
import com.rumor.mesh.core.model.TransferStatus
import com.rumor.mesh.core.platform.Base64Codec
import com.rumor.mesh.core.policy.PermissiveInboxFilter
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.scheduler.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O159 — first direct coverage of [TransferAssembler].
 *
 * `Chunker` (the pure half) is well tested, but every piece of O108/O109's
 * resource-exhaustion hardening lives in this stateful class and had none:
 * the plausibility gate, the per-sender concurrency cap, evidence-gated
 * watchdog arming, index/size bounds at ingest, and chunk-row release on
 * completion. O158 (chunk BLOBs leaked on the happy path) is proof the gap was
 * live — the last test here is the one that would have caught it.
 *
 * Ingest is driven through the `internal` handleMetadata/handleChunk rather
 * than through `gossipEngine.incomingMessages`, so each case is deterministic
 * with no flow timing and no waiting out the 60 s NACK delay.
 */
class TransferAssemblerTest {

    private class FakeIdentity(initial: LocalIdentity?) : IdentityProvider {
        private val flow = MutableStateFlow(initial)
        override val identity: StateFlow<LocalIdentity?> = flow
        override val isUnlocked: Boolean get() = flow.value != null
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val transferRepo = InMemoryTransferRepository()
    private val chunkRepo = InMemoryChunkRepository()

    private fun engine(): GossipEngine {
        val contactRepo = InMemoryContactRepository()
        val dedup = DuplicateFilter()
        return GossipEngine(
            messageStore = MessageStore(InMemoryMessageRepository(), contactRepo, dedup),
            duplicateFilter = dedup,
            identityProvider = FakeIdentity(null),
            onlineStatusTracker = OnlineStatusTracker(),
            topologyTracker = TopologyTracker(InMemoryRouteRepository()),
            contactRepo = contactRepo,
            blockManager = BlockManager(
                InMemoryBlockEntryRepository(),
                InMemorySubscribedBlocklistRepository(),
                InMemoryBlocklistEntryRepository(),
            ),
            scheduler = Scheduler(),
            inboxFilter = PermissiveInboxFilter(),
            breadcrumbs = null,
            scope = scope,
            relayBatchMinWindowMs = 1L,
            relayBatchSpreadMs = 1L,
        )
    }

    private val assembler = TransferAssembler(engine(), transferRepo, chunkRepo, scope)

    @After fun tearDown() = scope.cancel()

    /** One-chunk transfer whose metadata is internally consistent. */
    private fun meta(
        id: String,
        payload: ByteArray = byteArrayOf(1, 2, 3, 4),
        totalChunks: Int = 1,
        chunkSize: Int = 4,
        totalBytes: Long = payload.size.toLong(),
        withChunkHashes: Boolean = false,
    ) = TransferMetadata(
        transferId = id,
        contentType = ContentType.FILE,
        totalBytes = totalBytes,
        totalChunks = totalChunks,
        chunkSize = chunkSize,
        contentHash = Chunker.chunkHash(payload),
        // One hash per chunk; index 0 is `payload`, later indices are filler
        // this test never sends (it only needs index 0 verified).
        chunkHashes = if (withChunkHashes) {
            List(totalChunks) { i -> if (i == 0) Chunker.chunkHash(payload) else Chunker.chunkHash(byteArrayOf(i.toByte())) }
        } else null,
    )

    private fun chunk(id: String, index: Int, bytes: ByteArray, totalChunks: Int = 1) = Chunk(
        transferId = id,
        chunkIndex = index,
        totalChunks = totalChunks,
        data = Base64Codec.encode(bytes),
    )

    // ── O108: plausibility gate ──────────────────────────────────────────────

    @Test
    fun `implausible metadata commits no state`() = runBlocking {
        // The cheap OOM trigger O108 was filed for: one packet claiming a
        // yottabyte of chunks. Must not produce a row to hang work off.
        assembler.handleMetadata(
            meta("a".repeat(32), totalChunks = Int.MAX_VALUE, chunkSize = 64_000, totalBytes = Long.MAX_VALUE),
            senderUserId = "sender",
        )
        assertNull("implausible metadata must not register a transfer", transferRepo.getById("a".repeat(32)))
    }

    @Test
    fun `plausible metadata registers the transfer`() = runBlocking {
        // Discrimination control for the test above — the gate rejects absurd
        // claims, not all claims.
        val id = "b".repeat(32)
        assembler.handleMetadata(meta(id), senderUserId = "sender")
        assertNotNull("a consistent metadata must register", transferRepo.getById(id))
    }

    // ── O108: evidence-gated watchdog arming ─────────────────────────────────

    @Test
    fun `metadata alone arms no watchdog - the first chunk arms it`() = runBlocking {
        val id = "c".repeat(32)
        val bytes = byteArrayOf(1, 2, 3, 4)
        assembler.handleMetadata(meta(id), senderUserId = "sender")
        assertFalse(
            "metadata with no chunk behind it must cost nothing but a row",
            assembler.isWatchdogArmed(id),
        )
        // Two chunks so the transfer stays incomplete and the watchdog survives.
        assembler.handleMetadata(meta("d".repeat(32), totalChunks = 2, totalBytes = 8), senderUserId = "sender")
        assembler.handleChunk(chunk("d".repeat(32), 0, bytes, totalChunks = 2))
        assertTrue("the first real chunk must arm the NACK watchdog", assembler.isWatchdogArmed("d".repeat(32)))
    }

    // ── O108: per-sender concurrency cap ─────────────────────────────────────

    @Test
    fun `a single sender cannot exceed the concurrent-transfer cap`() = runBlocking {
        repeat(10) { i -> assembler.handleMetadata(meta("%032x".format(i)), senderUserId = "spammer") }
        val registered = (0 until 10).count { transferRepo.getById("%032x".format(it)) != null }
        assertEquals("MAX_ACTIVE_PER_SENDER transfers admitted, the rest dropped", 8, registered)

        // Control: the cap is per sender, so a different identity is unaffected.
        // (Sybils defeat per-identity caps by design — the documented O27 floor.)
        assembler.handleMetadata(meta("e".repeat(32)), senderUserId = "someone-else")
        assertNotNull("the cap must not be global", transferRepo.getById("e".repeat(32)))
    }

    // ── O108: ingest bounds ──────────────────────────────────────────────────

    @Test
    fun `an out-of-range chunk index never becomes a row`() = runBlocking {
        val id = "f".repeat(32)
        assembler.handleMetadata(meta(id), senderUserId = "sender")
        assembler.handleChunk(chunk(id, 99, byteArrayOf(1, 2, 3, 4)))
        assembler.handleChunk(chunk(id, -1, byteArrayOf(1, 2, 3, 4)))
        assertTrue("out-of-range indices must be dropped", chunkRepo.getReceivedIndices(id).isEmpty())
    }

    @Test
    fun `an oversized chunk payload never becomes a row`() = runBlocking {
        val id = "1".repeat(32)
        assembler.handleMetadata(meta(id, chunkSize = 4), senderUserId = "sender")
        assembler.handleChunk(chunk(id, 0, ByteArray(64) { 7 }))
        assertTrue("a chunk larger than the declared chunkSize must be dropped", chunkRepo.getReceivedIndices(id).isEmpty())
    }

    // ── O100: per-chunk hash verification at receipt ─────────────────────────

    @Test
    fun `a chunk failing its signed per-chunk hash is dropped`() = runBlocking {
        // Two chunks deliberately: a 1-chunk transfer completes the moment the
        // honest chunk lands and then releases its rows (O158), so acceptance
        // would be indistinguishable from rejection at the repo. Leaving index 1
        // outstanding keeps the transfer in progress and the row observable.
        val id = "2".repeat(32)
        val honest = byteArrayOf(1, 2, 3, 4)
        assembler.handleMetadata(
            meta(id, payload = honest, totalChunks = 2, totalBytes = 8, withChunkHashes = true),
            senderUserId = "sender",
        )
        assembler.handleChunk(chunk(id, 0, byteArrayOf(9, 9, 9, 9), totalChunks = 2))
        assertTrue("a poisoned chunk must not be stored", chunkRepo.getReceivedIndices(id).isEmpty())

        // Control: the honest bytes at the same index are accepted, so the drop
        // above is the hash check firing — not the index or size bound.
        assembler.handleChunk(chunk(id, 0, honest, totalChunks = 2))
        assertEquals("the honest chunk must be accepted", listOf(0), chunkRepo.getReceivedIndices(id))
    }

    // ── O158: chunk rows released on the happy path ──────────────────────────

    @Test
    fun `a completed transfer releases its chunk rows`() = runBlocking {
        // The O159 row names this case explicitly: a single assertion here would
        // have caught O158, where every normally-completed transfer left its raw
        // chunk bytes in the repo forever.
        val id = "3".repeat(32)
        val payload = byteArrayOf(1, 2, 3, 4)
        assembler.handleMetadata(meta(id, payload = payload), senderUserId = "sender")
        assembler.handleChunk(chunk(id, 0, payload))

        assertEquals(
            "the transfer must complete",
            TransferStatus.COMPLETE,
            transferRepo.getById(id)?.status,
        )
        assertTrue(
            "chunk rows must be released once the transfer completes (O158)",
            chunkRepo.getReceivedIndices(id).isEmpty(),
        )
    }

    // ── O18: pause drops chunks at ingest ────────────────────────────────────

    @Test
    fun `pause and resume gate chunk ingest`() = runBlocking {
        val id = "4".repeat(32)
        val payload = byteArrayOf(1, 2, 3, 4)
        assembler.handleMetadata(meta(id, totalChunks = 2, totalBytes = 8), senderUserId = "sender")
        assembler.pause(id)
        assertTrue(assembler.isPaused(id))
        assembler.resume(id)
        assertFalse(assembler.isPaused(id))
        assembler.handleChunk(chunk(id, 0, payload, totalChunks = 2))
        assertEquals("after resume, chunks land again", listOf(0), chunkRepo.getReceivedIndices(id))
    }
}
