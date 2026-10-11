package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.data.memory.InMemoryBlockEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryBlocklistEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryContactRepository
import com.rumor.mesh.core.data.memory.InMemoryMessageRepository
import com.rumor.mesh.core.data.memory.InMemoryRouteRepository
import com.rumor.mesh.core.data.memory.InMemorySubscribedBlocklistRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.policy.PermissiveInboxFilter
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.scheduler.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [GossipEngine.userSendQueued] wakes the transport's round loop early. It must
 * fire for user sends and must NOT fire for machine traffic: an auto-reply that
 * kicks a round which produces another auto-reply is an unbounded round loop.
 */
class UserSendSignalTest {

    private class FakeIdentity(id: LocalIdentity) : IdentityProvider {
        override val identity: StateFlow<LocalIdentity?> = MutableStateFlow(id)
        override val isUnlocked: Boolean get() = true
    }

    private val scope = CoroutineScope(SupervisorJob())

    @After fun tearDown() = scope.cancel()

    private fun engine(): GossipEngine {
        val (priv, pub) = CryptoManager.generateEd25519KeyPair()
        val id = LocalIdentity(CryptoManager.publicKeyToUserId(pub), "dev", pub, priv)
        val contactRepo = InMemoryContactRepository()
        val dedup = DuplicateFilter()
        return GossipEngine(
            messageStore = MessageStore(InMemoryMessageRepository(), contactRepo, dedup),
            duplicateFilter = dedup,
            identityProvider = FakeIdentity(id),
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

    @Test
    fun `a user broadcast fires the signal and a machine ACK does not`() = runBlocking {
        val engine = engine()
        var fired = 0
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.userSendQueued.collect { fired++ }
        }

        // Discrimination control first: machine traffic that IS queued must stay silent.
        assertNotNull(engine.composeDirectAck("a".repeat(64), "some-message-id"))
        yield()
        assertEquals(0, fired)

        assertNotNull(engine.composeBroadcast("hello"))
        yield()
        assertEquals(1, fired)

        collector.cancel()
    }
}
