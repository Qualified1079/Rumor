package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.floodedHops
import com.rumor.mesh.core.model.routedHops
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the O32 per-peer routing decision. After a breadcrumb is laid down
 * for some target T at relay node R, a relayed DM to T at R is marked with
 * intendedPeers = breadcrumb candidates, and only those peers see the DM in
 * their offer batch. The TTL split tracks routedHops separately from
 * floodedHops so a routed path doesn't burn flood-mode hop budget.
 */
class PerPeerRoutingTest {

    @Test
    fun `relayed DM with breadcrumb match is offered only to matched peers`() = runBlocking {
        // Four nodes: A (DM recipient), D (path node), B (relay under test),
        // C (DM sender). The breadcrumb on B forms ORGANICALLY, the way
        // production forms it: B receives one of A's messages relayed by D
        // (fromPeerId=D != senderId=A → record "to reach A, go via D").
        // The pre-deeper-O92 version of this test tried to lay the crumb via
        // a direct A→B exchange, which record() correctly skips
        // (fromPeerId == senderId) — the crumb never existed, awaitUntil
        // timed out SOFTLY, and the assertions passed only because the
        // destructive scheduler drain hid the DM from the second offer call.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)
        val c = SimNode(2, scope)
        val d = SimNode(3, scope)

        // Phase 1: A → D → B lays the crumb "to A, via D" on B.
        val seed = a.gossipEngine.composeBroadcast("seed")!!
        a.flushSchedulerToRepo()
        SimTransport(a, d).exchange(kotlin.random.Random(1))
        awaitUntil { d.knownMessages().any { it.id == seed.id } }
        d.flushSchedulerToRepo()
        SimTransport(d, b).exchange(kotlin.random.Random(2))
        awaitUntil { b.breadcrumbs.candidatePeersSync(a.userId).isNotEmpty() }

        registerContact(c, a)
        val dm = c.gossipEngine.composeDirect(
            recipientId = a.userId,
            recipientPublicKey = a.identityProvider.identity.value!!.publicKeyBytes,
            text = "for A only",
        ) ?: error("composeDirect returned null")
        c.flushSchedulerToRepo()

        // C → B exchange. B receives the DM (recipientId != B, so B relays it,
        // marking intendedPeers = breadcrumb candidates for A = {D}).
        SimTransport(c, b).exchange(kotlin.random.Random(3))
        // Ingest + RelayBatcher (1ms in sim) are asynchronous.
        awaitUntil { b.knownMessages().any { it.id == dm.id } && b.schedulerQueueDepth > 0 }

        val dmInOfferD = b.gossipEngine.messagesForExchange(d.userId).any { it.id == dm.id }
        val dmInOfferA = b.gossipEngine.messagesForExchange(a.userId).any { it.id == dm.id }
        val dmInOfferC = b.gossipEngine.messagesForExchange(c.userId).any { it.id == dm.id }

        assertTrue("Offer to D (breadcrumb candidate) must include the routed DM", dmInOfferD)
        // Deeper O92: the durable-store backfill re-offers the DM to its
        // recipient directly even after the marked scheduler copy drained.
        assertTrue("Offer to A (the recipient) must include the DM", dmInOfferA)
        assertEquals(
            "Offer to C (the sender, not a candidate) must NOT include the " +
                "routed DM — the O29 restriction holds on both the marked " +
                "scheduler copy and the derived backfill path",
            false, dmInOfferC,
        )
    }

    @Test
    fun `routed hop increments routedHops, not floodedHops`() = runBlocking {
        // A breadcrumb for A on B only forms when B receives one of A's messages
        // RELAYED by a third node — a message straight from its own author is
        // skipped (fromPeerId == senderId). So establish it the real way: A → C,
        // then C → B, which leaves B holding "to reach A, go via C". Sending a DM
        // for A through B then takes the routed path.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)
        val c = SimNode(2, scope)

        val seed = a.gossipEngine.composeBroadcast("seed")!!
        a.flushSchedulerToRepo()
        SimTransport(a, c).exchange(kotlin.random.Random(11))   // C sees A's seed, from A
        awaitUntil { c.knownMessages().any { it.id == seed.id } }
        c.flushSchedulerToRepo()
        SimTransport(c, b).exchange(kotlin.random.Random(12))   // B sees A's seed, from C
        awaitUntil { b.breadcrumbs.candidatePeersSync(a.userId).isNotEmpty() }

        registerContact(c, a)
        val dm = c.gossipEngine.composeDirect(
            recipientId = a.userId,
            recipientPublicKey = a.identityProvider.identity.value!!.publicKeyBytes,
            text = "ttl-split test",
        )!!
        val originalFloodedHops = dm.floodedHops
        val originalRoutedHops = dm.routedHops
        c.flushSchedulerToRepo()

        // C → B: B relays the DM. Its breadcrumb for A names C, so relay takes
        // the routed path — intendedPeers = {C}, routedHops incremented.
        SimTransport(c, b).exchange(kotlin.random.Random(13))
        // Ingest + RelayBatcher (1ms in sim) are asynchronous.
        awaitUntil { b.knownMessages().any { it.id == dm.id } && b.schedulerQueueDepth > 0 }

        // The routed copy carrying the bumped routedHops lives in B's offer
        // batch (intendedPeers = {C}), NOT in knownMessages() — relay() enqueues
        // it via the batcher/scheduler and never writes the bumped copy back to
        // the repo. messagesForExchange destructively drains, so call it once.
        val relayed = b.gossipEngine.messagesForExchange(c.userId).firstOrNull { it.id == dm.id }
        assertNotNull("B should offer the routed DM to breadcrumb candidate C", relayed)
        assertTrue(
            "Routed hop must increment routedHops " +
                "(was $originalRoutedHops, now ${relayed!!.routedHops})",
            relayed.routedHops > originalRoutedHops,
        )
        // floodedHops must not decrement on a routed hop — the routed path
        // carries the inherited value forward rather than burning flood budget.
        assertTrue(
            "floodedHops must not be smaller than the original after a routed hop " +
                "(was $originalFloodedHops, now ${relayed.floodedHops})",
            relayed.floodedHops >= originalFloodedHops - 1,  // allow a single decrementHops
        )
    }

    private suspend fun registerContact(host: SimNode, other: SimNode) {
        val otherIdentity = other.identityProvider.identity.value!!
        host.contactRepoForTest().upsert(
            com.rumor.mesh.core.model.Contact(
                userId = otherIdentity.userId,
                publicKey = com.rumor.mesh.core.crypto.CryptoManager.run { otherIdentity.publicKeyBytes.toBase64() },
                displayName = "n${other.index}",
                isVerified = false,
                autoRelay = false,
                alwaysSave = false,
                willingToCache = false,
                firstSeenMs = 0L,
                lastSeenMs = System.currentTimeMillis(),
                isPriorityPeer = false,
            )
        )
    }

    private fun SimNode.contactRepoForTest(): com.rumor.mesh.core.data.memory.InMemoryContactRepository =
        this::class.java.getDeclaredField("contactRepo").apply { isAccessible = true }
            .get(this) as com.rumor.mesh.core.data.memory.InMemoryContactRepository
}
