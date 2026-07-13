package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.routing.OnlineStatusTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the O29 Tier-1 breadcrumb-recording substrate through SimNode's
 * real gossip path. After a message originated by A is forwarded through B
 * to C, C's BreadcrumbCache must contain "to reach A, go via B" — the
 * primitive every other Tier-1 routing decision will consume.
 */
class BreadcrumbSubstrateTest {

    @Test
    fun `relayed message records breadcrumb pointing back through the delivering peer`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)
        val c = SimNode(2, scope)

        // A originates a broadcast and lets it land in A's own message store
        // (composeBroadcast both signs and ingests locally).
        val msg = a.gossipEngine.composeBroadcast("hello from A")
            ?: error("composeBroadcast returned null — identity unlocked?")
        a.flushSchedulerToRepo()  // make it visible to SimTransport's exchange

        // A → B exchange: B sees the message, hand-delivered by A.
        SimTransport(a, b).exchange(kotlin.random.Random(1))
        awaitUntil { b.knownMessages().any { it.id == msg.id } }
        b.flushSchedulerToRepo()

        // Sanity: B knows the message.
        assertTrue("B should hold A's broadcast after the exchange",
            b.knownMessages().any { it.id == msg.id })

        // B → C exchange: C sees the message, hand-delivered by B.
        SimTransport(b, c).exchange(kotlin.random.Random(2))

        // Ingest + breadcrumb recording on C are asynchronous.
        awaitUntil { c.breadcrumbs.candidatePeersSync(a.userId).isNotEmpty() }

        // C's breadcrumb cache should now point "to reach A, go via B".
        val candidates = c.breadcrumbs.candidatePeers(a.userId)
        assertEquals(
            "C must have one breadcrumb for A pointing at B",
            listOf(b.userId), candidates,
        )
    }

    @Test
    fun `breadcrumb is NOT recorded when the message comes directly from the sender`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)

        a.gossipEngine.composeBroadcast("hello")
            ?: error("composeBroadcast returned null")
        a.flushSchedulerToRepo()
        SimTransport(a, b).exchange(kotlin.random.Random(3))
        delay(50)

        // B received from A directly — recording a breadcrumb (targetUserId=A,
        // fromPeerId=A) would be useless and the engine skips it.
        val candidates = b.breadcrumbs.candidatePeers(a.userId)
        assertTrue(
            "B should not record a self-breadcrumb for A",
            candidates.isEmpty(),
        )
    }
}
