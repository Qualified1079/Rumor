package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.Contact
import com.rumor.mesh.core.model.routedHops
import com.rumor.mesh.core.crypto.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A relay must offer a routed DM to its **recipient** even when no breadcrumb
 * names them.
 *
 * Found 2026-10-11 by root-causing a `PerPeerRoutingTest` flake. `relay()` marked a
 * routed DM with `intendedPeers = <breadcrumb candidates>`, and the serve path
 * excludes any peer outside that set — so a relay holding a DM for A, with A
 * standing right there as a direct peer, would not offer it to A if its crumb for A
 * named some other node D. The DM reached A only on a later store-backfill re-offer
 * (whose re-derivation special-cases the recipient), which made the bug look like
 * mere delay and hid it: the old test's outcome depended purely on whether the
 * marked copy was still queued at the moment the recipient was offered to.
 *
 * It is not mere delay under O55. A duty-cycled node's contact window may be the
 * only one for hours, and "offered on some later round" can mean never.
 *
 * Topology (deliberately the adversarial case): B's only crumb for A names D, and
 * the DM's recipient A is NOT in that crumb set.
 */
class RoutedDmReachesRecipientTest {

    @Test
    fun `a relay offers a routed DM to its recipient even when no crumb names them`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val a = SimNode(0, scope)   // the DM's recipient
            val b = SimNode(1, scope)   // the relay under test
            val c = SimNode(2, scope)   // the DM's sender
            val d = SimNode(3, scope)   // the only node B's crumb for A names
            // Isolate the O29 crumb restriction from O198 liveness, which would flood
            // (and so trivially satisfy this test) because nothing is recently-exchanged.
            listOf(a, b, c, d).forEach { it.gossipEngine.livenessRouting = false }

            // Crumb "to reach A, go via D" forms organically: A → D → B.
            val seed = a.gossipEngine.composeBroadcast("seed")!!
            a.flushSchedulerToRepo()
            SimTransport(a, d).exchange(kotlin.random.Random(1))
            awaitUntil(what = "D to hold A's seed broadcast") {
                d.knownMessages().any { it.id == seed.id }
            }
            d.flushSchedulerToRepo()
            SimTransport(d, b).exchange(kotlin.random.Random(2))
            awaitUntil(what = "B's crumb for A to form") {
                b.breadcrumbs.candidatePeersSync(a.userId).isNotEmpty()
            }

            registerContact(c, a)
            val dm = c.gossipEngine.composeDirect(
                recipientId = a.userId,
                recipientPublicKey = a.identityProvider.identity.value!!.publicKeyBytes,
                text = "for A, via nobody in particular",
            )!!
            c.flushSchedulerToRepo()
            SimTransport(c, b).exchange(kotlin.random.Random(3))
            awaitUntil(what = "B to relay the DM into its outbound queue") {
                b.knownMessages().any { it.id == dm.id } && b.schedulerQueueDepth > 0
            }
            delay(200) // the marked relay copy must certainly be queued, not racing

            // Precondition that makes this the adversarial case and not a tautology:
            // B's crumb for A must be D, and must NOT already be A itself.
            val crumbs = b.breadcrumbs.candidatePeersSync(a.userId)
            assertTrue("the crumb for A must name D", d.userId in crumbs)

            // Offer to the recipient FIRST, while the marked copy is still queued —
            // the exact ordering under which the bug bites.
            val offerToRecipient = b.gossipEngine.messagesForExchange(a.userId)
            val offered = offerToRecipient.firstOrNull { it.id == dm.id }
            assertTrue(
                "B must offer the DM to A, its recipient, while the routed copy is queued",
                offered != null,
            )
            assertTrue(
                "the offered copy must be the ROUTED one (marked, routedHops bumped), " +
                    "not an unrouted store copy — otherwise this passes for the wrong reason",
                offered!!.intendedPeers != null && offered.routedHops > dm.routedHops,
            )
            assertTrue(
                "the recipient must be in the routed copy's intendedPeers",
                a.userId in offered.intendedPeers!!,
            )

            // Teeth / discrimination control: the fix must not have degraded routing
            // into flooding. C is the sender — neither the recipient nor a crumb
            // candidate — and must still NOT be offered the routed DM.
            val offerToSender = b.gossipEngine.messagesForExchange(c.userId)
            assertEquals(
                "the sender C is neither recipient nor crumb candidate and must still " +
                    "be excluded — if this now passes the DM, intendedPeers stopped " +
                    "restricting anything and the whole O29 property is gone",
                false,
                offerToSender.any { it.id == dm.id },
            )
            assertFalse(
                "sanity: A and C must be distinct nodes for the control to mean anything",
                a.userId == c.userId,
            )
        } finally {
            scope.cancel()
        }
    }

    private suspend fun registerContact(host: SimNode, other: SimNode) {
        val id = other.identityProvider.identity.value!!
        host.contactRepoForTest().upsert(
            Contact(
                userId = id.userId,
                publicKey = CryptoManager.run { id.publicKeyBytes.toBase64() },
                displayName = "n${other.index}", isVerified = false, autoRelay = false,
                alwaysSave = false, willingToCache = false, firstSeenMs = 0L,
                lastSeenMs = 0L, isPriorityPeer = false,
            )
        )
    }

    private fun SimNode.contactRepoForTest(): com.rumor.mesh.core.data.memory.InMemoryContactRepository =
        this::class.java.getDeclaredField("contactRepo").apply { isAccessible = true }
            .get(this) as com.rumor.mesh.core.data.memory.InMemoryContactRepository
}
