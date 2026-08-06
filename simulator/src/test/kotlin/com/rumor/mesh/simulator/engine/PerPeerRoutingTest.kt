package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.floodedHops
import com.rumor.mesh.core.model.routedHops
import com.rumor.mesh.core.model.withTtlSplit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        // Characterises the pure O29 crumb-restriction on messagesForExchange, so it
        // opts OUT of now-default-on O198 liveness (which would flood a candidate not
        // yet recently-exchanged). Liveness is covered by SmartRoutingLivenessTest /
        // RoutingBandwidthSweepTest.
        listOf(a, b, c, d).forEach { it.gossipEngine.livenessRouting = false }

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
        listOf(a, b, c).forEach { it.gossipEngine.livenessRouting = false } // isolate O29 crumb path (see above)

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
        // O160: a routed hop must NOT burn the flood budget at all — floodedHops
        // is preserved exactly (the previous `>= original - 1` fudge masked the
        // bug where hopsToLive was decremented unconditionally, collapsing routed
        // reach to flood reach). It stays equal, and hopsToLive is untouched.
        assertEquals(
            "floodedHops must be unchanged after a routed hop " +
                "(was $originalFloodedHops, now ${relayed.floodedHops})",
            originalFloodedHops, relayed.floodedHops,
        )
        assertEquals(
            "hopsToLive (legacy flood budget) must be unchanged after a routed hop",
            dm.hopsToLive, relayed.hopsToLive,
        )
    }

    @Test
    fun `a deep routed path is not truncated by the flood budget`() = runBlocking {
        // O160/user-correction: routing is not a "2x flood" ration. A DM that has
        // already taken many routed hops (more than a flood would ever survive)
        // must STILL be forwarded along the breadcrumb — the routed odometer is
        // bounded only by MAX_ROUTED_HOPS, never coupled to the flood budget. The
        // pre-correction code dropped it (routedHops+floodedHops > 30); the fixed
        // code carries it on.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)
        val c = SimNode(2, scope)

        // Lay the breadcrumb "to reach A, go via C" on B (the real way: A→C→B).
        val seed = a.gossipEngine.composeBroadcast("seed")!!
        a.flushSchedulerToRepo()
        SimTransport(a, c).exchange(kotlin.random.Random(21))
        awaitUntil { c.knownMessages().any { it.id == seed.id } }
        c.flushSchedulerToRepo()
        SimTransport(c, b).exchange(kotlin.random.Random(22))
        awaitUntil { b.breadcrumbs.candidatePeersSync(a.userId).isNotEmpty() }

        registerContact(c, a)
        val dm = c.gossipEngine.composeDirect(
            recipientId = a.userId,
            recipientPublicKey = a.identityProvider.identity.value!!.publicKeyBytes,
            text = "deep routed",
        )!!
        // Stamp the DM as already 40 routed hops in — far past the old 30-hop
        // coupled ceiling, but with its flood budget fully intact. _ext is
        // unsigned, so the outer signature stays valid. Seed it into C's repo so
        // C offers this variant (dedup keeps the pristine scheduler copy out).
        val deep = dm.withTtlSplit(routedHops = 40, floodedHops = dm.floodedHops)
        c.seedMessage(deep)

        SimTransport(c, b).exchange(kotlin.random.Random(23))
        awaitUntil { b.knownMessages().any { it.id == dm.id } && b.schedulerQueueDepth > 0 }

        val relayed = b.gossipEngine.messagesForExchange(c.userId).firstOrNull { it.id == dm.id }
        assertNotNull("A deep routed DM must still be forwarded along the breadcrumb", relayed)
        assertTrue(
            "routedHops must advance past the old flood-coupled ceiling (now ${relayed!!.routedHops})",
            relayed.routedHops > 30,
        )
        assertEquals("flood budget must remain fully intact on a deep routed hop",
            dm.hopsToLive, relayed.hopsToLive)
        scope.cancel()
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
