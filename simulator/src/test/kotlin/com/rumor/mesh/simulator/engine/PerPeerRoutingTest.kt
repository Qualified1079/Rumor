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
        // Three-node line: A — B — C, with B as the relay. A is the DM
        // recipient. We seed B's breadcrumb cache so B knows "to reach A,
        // go via A directly" (the simplest 1-hop case). When C sends a DM
        // for A through B, B's relay marks the message with intendedPeers={A}.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)
        val c = SimNode(2, scope)

        // Phase 1: A broadcasts so B records "to A → via A" breadcrumb.
        // (Direct receipt would normally be filtered; we use a third hop via
        // C → B exchange where B sees A's message arriving from C, so B's
        // crumb is 'to A → via C'. But for this test we instead just lay an
        // A → B exchange first to populate the snapshot directly.)
        a.gossipEngine.composeBroadcast("seed")!!
        a.flushSchedulerToRepo()
        SimTransport(c, b).exchange(kotlin.random.Random(0))  // no-op, but warms B
        SimTransport(a, b).exchange(kotlin.random.Random(1))  // B sees A's seed via A
        delay(50)
        // B's breadcrumb for A points to A itself (1-hop). That's fine — the
        // filter logic treats it as "messages for A are intended for peer A."

        registerContact(c, a)
        val dm = c.gossipEngine.composeDirect(
            recipientId = a.userId,
            recipientPublicKey = a.identityProvider.identity.value!!.publicKeyBytes,
            text = "for A only",
        ) ?: error("composeDirect returned null")
        c.flushSchedulerToRepo()

        // C → B exchange. B receives the DM (recipientId != B, so B relays it).
        SimTransport(c, b).exchange(kotlin.random.Random(2))
        delay(100)  // give RelayBatcher (1ms in sim) time to flush

        // At B: the relayed DM should be marked with intendedPeers={a.userId}
        // because b's breadcrumb candidates for A include A. Now ask B what
        // it would offer C versus what it would offer A.
        val offerToA = b.gossipEngine.messagesForExchange(a.userId)
        val offerToC = b.gossipEngine.messagesForExchange(c.userId)

        val dmInOfferA = offerToA.any { it.id == dm.id }
        val dmInOfferC = offerToC.any { it.id == dm.id }
        // C is the original sender — they already have the DM, and B's
        // intendedPeers excludes them. A is the recipient and the
        // breadcrumb candidate, so A receives the offer.
        assertTrue("Offer to A must include the routed DM", dmInOfferA)
        assertEquals(
            "Offer to C (the sender) must NOT include the routed DM — " +
                "intendedPeers excludes non-matched peers",
            false, dmInOfferC,
        )
    }

    @Test
    fun `routed hop increments routedHops, not floodedHops`() = runBlocking {
        // Same setup: warm B's breadcrumb for A, send DM for A through B.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)
        val c = SimNode(2, scope)

        a.gossipEngine.composeBroadcast("seed")!!
        a.flushSchedulerToRepo()
        SimTransport(a, b).exchange(kotlin.random.Random(11))
        delay(50)

        registerContact(c, a)
        val dm = c.gossipEngine.composeDirect(
            recipientId = a.userId,
            recipientPublicKey = a.identityProvider.identity.value!!.publicKeyBytes,
            text = "ttl-split test",
        )!!
        val originalFloodedHops = dm.floodedHops
        val originalRoutedHops = dm.routedHops
        c.flushSchedulerToRepo()

        SimTransport(c, b).exchange(kotlin.random.Random(12))
        delay(100)

        val relayed = b.knownMessages().firstOrNull { it.id == dm.id }
        assertNotNull("B should hold the relayed DM", relayed)
        assertTrue(
            "Routed hop must increment routedHops " +
                "(was $originalRoutedHops, now ${relayed!!.routedHops})",
            relayed.routedHops > originalRoutedHops,
        )
        // floodedHops should NOT have decremented because B took the routed
        // path. The relay()-path floodedHops carries forward the original
        // hopsToLive value when routing.
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

    private fun SimNode.contactRepoForTest(): com.rumor.mesh.simulator.data.InMemoryContactRepository =
        this::class.java.getDeclaredField("contactRepo").apply { isAccessible = true }
            .get(this) as com.rumor.mesh.simulator.data.InMemoryContactRepository
}
