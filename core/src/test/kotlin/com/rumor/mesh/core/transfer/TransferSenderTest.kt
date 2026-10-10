package com.rumor.mesh.core.transfer

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.crypto.CryptoManager
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
import com.rumor.mesh.core.model.ChunkRequest
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.TransferDirection
import com.rumor.mesh.core.model.TransferStatus
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O159 — the TransferSender half (companion to [TransferAssemblerTest]).
 *
 * Outbound was equally untested. What is actually observable here is the
 * [Scheduler]: `composeOutbound` ends in `enqueueImmediate`, so draining the
 * scheduler tells us exactly what the sender put on the wire — which is how
 * "a cancelled transfer resends nothing" gets asserted rather than assumed.
 */
class TransferSenderTest {

    private class FakeIdentity(initial: LocalIdentity?) : IdentityProvider {
        private val flow = MutableStateFlow(initial)
        override val identity: StateFlow<LocalIdentity?> = flow
        override val isUnlocked: Boolean get() = flow.value != null
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val transferRepo = InMemoryTransferRepository()
    private val chunkRepo = InMemoryChunkRepository()
    private val scheduler = Scheduler()

    private val kp = CryptoManager.generateEd25519KeyPair()
    private val identity = LocalIdentity(
        userId = CryptoManager.publicKeyToUserId(kp.publicKeyBytes),
        deviceId = "device",
        publicKeyBytes = kp.publicKeyBytes,
        privateKeyBytes = kp.privateKeyBytes,
    )
    private val identityProvider = FakeIdentity(identity)

    private val engine: GossipEngine = run {
        val contactRepo = InMemoryContactRepository()
        val dedup = DuplicateFilter()
        GossipEngine(
            messageStore = MessageStore(InMemoryMessageRepository(), contactRepo, dedup),
            duplicateFilter = dedup,
            identityProvider = identityProvider,
            onlineStatusTracker = OnlineStatusTracker(),
            topologyTracker = TopologyTracker(InMemoryRouteRepository()),
            contactRepo = contactRepo,
            blockManager = BlockManager(
                InMemoryBlockEntryRepository(),
                InMemorySubscribedBlocklistRepository(),
                InMemoryBlocklistEntryRepository(),
            ),
            scheduler = scheduler,
            inboxFilter = PermissiveInboxFilter(),
            breadcrumbs = null,
            scope = scope,
            relayBatchMinWindowMs = 1L,
            relayBatchSpreadMs = 1L,
        )
    }

    private val sender = TransferSender(engine, identityProvider, transferRepo, chunkRepo, scope)
    private val peer = "a".repeat(64)

    @After fun tearDown() = scope.cancel()

    /** Everything the sender has put on the wire since the last drain. */
    private fun drain() = scheduler.take(1_000)

    @Test
    fun `sendFile records a SENDING transfer, caches chunks, and emits metadata plus chunks`() = runBlocking {
        val id = sender.sendFile(peer, ContentType.FILE, ByteArray(100) { it.toByte() }, "application/octet-stream", "f.bin")
        assertNotNull("sendFile must return a transferId", id)

        val record = transferRepo.getById(id!!)
        assertNotNull("a transfer record must exist", record)
        assertEquals(TransferDirection.SENDING, record!!.direction)
        assertEquals(TransferStatus.IN_PROGRESS, record.status)

        // Chunks are cached so a later NACK can be served without re-reading the
        // source — that cache IS the retransmit path handleChunkRequest uses.
        assertTrue("chunk rows must be cached for retransmit", chunkRepo.getReceivedIndices(id).isNotEmpty())

        val sent = drain()
        assertEquals(
            "exactly one TRANSFER_METADATA must be emitted",
            1,
            sent.count { it.type == MessageType.TRANSFER_METADATA },
        )
        assertEquals(
            "one CHUNK per cached chunk must be emitted",
            chunkRepo.getReceivedIndices(id).size,
            sent.count { it.type == MessageType.CHUNK },
        )
    }

    @Test
    fun `sendFile refuses empty data and commits nothing`() = runBlocking {
        assertNull("empty payload must not start a transfer", sender.sendFile(peer, ContentType.FILE, ByteArray(0)))
        assertTrue("and must not enqueue anything", drain().isEmpty())
    }

    @Test
    fun `a receiver cancel marks the transfer ABANDONED`() = runBlocking {
        val id = sender.sendFile(peer, ContentType.FILE, ByteArray(50) { 1 })!!
        drain()
        sender.onTransferCancel(id)
        assertEquals(
            "an in-progress transfer must become ABANDONED on receiver cancel",
            TransferStatus.ABANDONED,
            transferRepo.getById(id)?.status,
        )
    }

    @Test
    fun `after a cancel, a chunk request resends nothing`() = runBlocking {
        val id = sender.sendFile(peer, ContentType.FILE, ByteArray(50) { 1 })!!
        drain()
        sender.onTransferCancel(id)

        sender.handleChunkRequest(ChunkRequest(id, listOf(0)), requesterId = peer)
        assertTrue(
            "a cancelled transfer must not serve retransmits — the sender stops paying for it",
            drain().isEmpty(),
        )
    }

    /**
     * Discrimination control for the test above: the same request on a live
     * transfer DOES resend. Without this, "resends nothing" could pass because
     * the retransmit path is broken outright rather than because cancel worked.
     */
    @Test
    fun `a chunk request on a live transfer does resend the requested chunk`() = runBlocking {
        val id = sender.sendFile(peer, ContentType.FILE, ByteArray(50) { 1 })!!
        drain()

        sender.handleChunkRequest(ChunkRequest(id, listOf(0)), requesterId = peer)
        val resent = drain()
        assertEquals("the requested chunk must be resent", 1, resent.count { it.type == MessageType.CHUNK })
    }

    @Test
    fun `a chunk request for an unknown transfer is ignored`() = runBlocking {
        sender.handleChunkRequest(ChunkRequest("b".repeat(32), listOf(0, 1, 2)), requesterId = peer)
        assertTrue("an unknown transferId must not produce traffic", drain().isEmpty())
    }
}
