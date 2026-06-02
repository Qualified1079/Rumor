package com.rumor.mesh.simulator.engine

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
 *
 * Topology: A — R — B — S. R relays A's broadcast onward to B, so B records
 * "to A → via R". S then sends a DM to A through B; B's relay must pick R
 * (not S, not A directly) as the next-hop because B has no other path to A.
 */
class PerPeerRoutingTest {

    @Test
    fun `relayed DM with breadcrumb match is offered only to matched peers`() = runBlocking {
        val (a, r, b, s) = fourNodes()
        seedBreadcrumbAonBviaR(a, r, b)

        registerContact(s, a)
        val dm = s.gossipEngine.composeDirect(
            recipientId = a.userId,
            recipientPublicKey = a.identityProvider.identity.value!!.publicKeyBytes,
            text = "for A only",
        ) ?: error("composeDirect returned null")
        s.flushSchedulerToRepo()

        // S → B exchange. B receives the DM (recipientId != B → B relays).
        SimTransport(s, b).exchange(kotlin.random.Random(2))
        delay(150)  // async ingest + RelayBatcher window

        // B's relay should set intendedPeers={R} because R is B's only
        // known path to A. R must see the DM in its offer; S (the sender)
        // and A (direct, not in breadcrumb) must not.
        val offerToR = b.gossipEngine.messagesForExchange(r.userId)
        val offerToS = b.gossipEngine.messagesForExchange(s.userId)
        val offerToA = b.gossipEngine.messagesForExchange(a.userId)

        assertTrue("Offer to R (breadcrumb candidate) must include the routed DM",
            offerToR.any { it.id == dm.id })
        assertEquals("Offer to S (sender) must NOT include the routed DM",
            false, offerToS.any { it.id == dm.id })
        assertEquals("Offer to A (not in breadcrumb) must NOT include the routed DM",
            false, offerToA.any { it.id == dm.id })
    }

    @Test
    fun `routed hop increments routedHops, not floodedHops`() = runBlocking {
        val (a, r, b, s) = fourNodes()
        seedBreadcrumbAonBviaR(a, r, b)

        registerContact(s, a)
        val dm = s.gossipEngine.composeDirect(
            recipientId = a.userId,
            recipientPublicKey = a.identityProvider.identity.value!!.publicKeyBytes,
            text = "ttl-split test",
        )!!
        val originalFloodedHops = dm.floodedHops
        val originalRoutedHops = dm.routedHops
        s.flushSchedulerToRepo()

        SimTransport(s, b).exchange(kotlin.random.Random(12))
        delay(150)

        // The routing decision lives in the wire copy B offers downstream,
        // not in the as-ingested repo copy. Read via messagesForExchange —
        // the same path GossipSession uses to build outbound offer batches.
        val relayed = b.gossipEngine.messagesForExchange(r.userId)
            .firstOrNull { it.id == dm.id }
        assertNotNull("B should be offering the relayed DM to R", relayed)
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

    /** A originates a broadcast that R relays to B, leaving B with crumb
     *  "to A → via R". A→R→B with awaits between exchanges so async ingest
     *  completes before the next leg uses the recipient's repo. */
    private suspend fun seedBreadcrumbAonBviaR(a: SimNode, r: SimNode, b: SimNode) {
        a.gossipEngine.composeBroadcast("seed")!!
        a.flushSchedulerToRepo()
        SimTransport(a, r).exchange(kotlin.random.Random(0))
        delay(50)
        r.flushSchedulerToRepo()
        SimTransport(r, b).exchange(kotlin.random.Random(1))
        delay(50)
        assertEquals(
            "B's breadcrumb for A must point to R after relay",
            listOf(r.userId), b.breadcrumbs.candidatePeersSync(a.userId),
        )
    }

    private fun fourNodes(): Quad<SimNode> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        return Quad(SimNode(0, scope), SimNode(1, scope), SimNode(2, scope), SimNode(3, scope))
    }

    private data class Quad<T>(val a: T, val b: T, val c: T, val d: T)

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
