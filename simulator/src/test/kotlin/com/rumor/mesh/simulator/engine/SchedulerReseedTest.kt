package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.policy.PermissiveInboxFilter
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.scheduler.Scheduler
import com.rumor.mesh.simulator.data.InMemoryBlockEntryRepository
import com.rumor.mesh.simulator.data.InMemoryBlocklistEntryRepository
import com.rumor.mesh.simulator.data.InMemoryContactRepository
import com.rumor.mesh.simulator.data.InMemoryMessageRepository
import com.rumor.mesh.simulator.data.InMemoryRouteRepository
import com.rumor.mesh.simulator.data.InMemorySubscribedBlocklistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O92 regression: the outbound Scheduler and DuplicateFilter are volatile, so a
 * restarted process must rebuild them from the durable store — otherwise a phone
 * full of buffered messages offers "0 sent, 0 received" on its next exchange
 * (field-observed). Models restart as a fresh [GossipEngine] (new scheduler +
 * dedup) over a store that already holds messages from a prior session.
 */
class SchedulerReseedTest {

    /** A fresh engine over a caller-owned repo = a restarted process over the same store. */
    private fun engineOver(repo: InMemoryMessageRepository, scope: CoroutineScope): GossipEngine {
        val contactRepo = InMemoryContactRepository()
        val duplicateFilter = DuplicateFilter()
        val store = MessageStore(repo, contactRepo, duplicateFilter)
        return GossipEngine(
            messageStore = store,
            duplicateFilter = duplicateFilter,
            identityProvider = SimIdentityProvider(0),
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

    private fun msg(id: String, type: MessageType, hops: Int, seq: Int) = RumorMessage(
        id = id,
        senderId = "author",
        senderPublicKey = "pk",
        sequenceNumber = seq.toLong(),
        sentAtMs = 1_000L + seq,
        type = type,
        hopsToLive = hops,
        payload = MessagePayload(ContentType.TEXT, "content-$id"),
        signature = "sig",
    )

    @Test
    fun `restart reseeds buffered content into the scheduler`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val repo = InMemoryMessageRepository()
        val peer = SimIdentityProvider(1).identity.value!!.userId

        // Prior session left these in the durable store.
        repo.insert(msg("b0", MessageType.BROADCAST, hops = 5, seq = 0))
        repo.insert(msg("b1", MessageType.BROADCAST, hops = 5, seq = 1))
        repo.insert(msg("d0", MessageType.DIRECT, hops = 5, seq = 2))
        // Exclusions: expired broadcast + time-sensitive control chatter.
        repo.insert(msg("expired", MessageType.BROADCAST, hops = 0, seq = 3))
        repo.insert(msg("presence", MessageType.SELF_PRESENCE, hops = 5, seq = 4))

        val engine = engineOver(repo, scope)

        // Deeper O92 note: the offer path now backfills from the durable store,
        // so even pre-reseed the offer is non-empty — the original "0 sent"
        // restart bug is dead twice over. The dedup summary, however, is still
        // volatile and only reseedFromStore repopulates it.
        assertTrue("pre-reseed summary must be empty", engine.knownMessageIds().isEmpty())

        engine.reseedFromStore()

        val offeredIds = engine.messagesForExchange(peer).map { it.id }.toSet()
        assertTrue("live broadcasts re-offered", offeredIds.containsAll(setOf("b0", "b1")))
        assertTrue("DMs re-offered", offeredIds.contains("d0"))
        assertFalse("expired (hopsToLive=0) not offered", offeredIds.contains("expired"))
        assertFalse("stale presence chatter not offered", offeredIds.contains("presence"))

        // Dedup summary reflects everything we hold — including excluded-from-offer
        // types — so peers don't re-offer us content we already have.
        val summary = engine.knownMessageIds()
        assertEquals(
            setOf("b0", "b1", "d0", "expired", "presence"),
            summary,
        )
    }

    /**
     * Deeper O92: scheduler.take() is destructive, so before the repo backfill
     * the first peer drained the queue and the second peer was offered nothing.
     * Now every exchange serves the durable store — both sequential peers see
     * the same locally-composed broadcast.
     */
    @Test
    fun `sequential peers are both offered a locally-composed broadcast`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val repo = InMemoryMessageRepository()
        val engine = engineOver(repo, scope)
        val peerA = SimIdentityProvider(1).identity.value!!.userId
        val peerB = SimIdentityProvider(2).identity.value!!.userId

        val composed = engine.composeBroadcast("hello both")
            ?: error("composeBroadcast returned null")
        // ingestOwn runs async on the engine scope — wait for the durable copy.
        var tries = 0
        while (repo.getById(composed.id) == null && tries++ < 100) kotlinx.coroutines.delay(10)

        val offerA = engine.messagesForExchange(peerA).map { it.id }
        assertTrue("first peer sees the broadcast", composed.id in offerA)

        // Pre-backfill this was empty: the take() above drained the scheduler.
        val offerB = engine.messagesForExchange(peerB).map { it.id }
        assertTrue("second peer must ALSO see the broadcast (repo backfill)", composed.id in offerB)
    }
}
