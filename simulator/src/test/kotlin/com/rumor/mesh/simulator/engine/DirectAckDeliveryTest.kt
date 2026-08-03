package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * O202 — on-receipt end-to-end delivery ACK (DIRECT_ACK).
 *
 * Drives the REAL GossipEngine over the REAL SimTransport (no bare in-memory
 * object assertions — the O129 trap). Proves:
 *  1. When B receives a DIRECT DM addressed to it, B auto-composes a DIRECT_ACK
 *     that routes back to the original sender A, and A surfaces the acked
 *     messageId on `deliveryReceipts` (the money assertion).
 *  2. Discrimination control: a relay R that forwards a DM addressed to
 *     *someone else* must NOT emit an ACK (only the final recipient ACKs).
 *  3. assertThrows guard proving the receipt assertion actually has teeth.
 */
class DirectAckDeliveryTest {

    @Test
    fun `recipient ACKs a received DM and the sender gets a delivery receipt`() = kotlinx.coroutines.runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val a = SimNode(0, scope)
            val b = SimNode(1, scope)

            // Collect A's end-to-end delivery receipts.
            val receipts = ConcurrentHashMap.newKeySet<String>()
            scope.launch { a.gossipEngine.deliveryReceipts.collect { receipts.add(it) } }

            // A composes a DM to B.
            val dm = a.gossipEngine.composeDirect(
                recipientId = b.userId,
                recipientPublicKey = b.identityProvider.identity.value!!.publicKeyBytes,
                text = "delivered?",
            ) ?: error("composeDirect returned null")
            a.flushSchedulerToRepo()

            // A → B: B receives the DM (recipientId == B) and auto-ACKs.
            SimTransport(a, b).exchange(kotlin.random.Random(1))
            awaitUntil { b.knownMessages().any { it.id == dm.id } }

            // B's auto-composed ACK sits in B's scheduler; drain it into the repo
            // so the return exchange can offer it.
            b.flushSchedulerToRepo()
            val ackOnB = b.knownMessages().firstOrNull { it.type == MessageType.DIRECT_ACK }
            assertTrue("B must have auto-composed a DIRECT_ACK on receipt", ackOnB != null)
            assertEquals("ACK must reference the acked DM id", dm.id, ackOnB!!.payload?.content)
            assertEquals("ACK must be routed back to the original sender A", a.userId, ackOnB.recipientId)

            // B → A: the ACK returns to A, which raises a delivery receipt.
            SimTransport(b, a).exchange(kotlin.random.Random(2))
            awaitUntil { dm.id in receipts }

            assertTrue(
                "A must receive an end-to-end delivery receipt for its DM " +
                    "(receipts=$receipts, expected ${dm.id})",
                dm.id in receipts,
            )
            // The ACK is a control signal — it must NOT surface in A's inbox.
            // (B sent A nothing but the ACK, so B appearing as an inbox sender
            // would mean the ACK leaked into the user-visible stream.)
            assertTrue(
                "DIRECT_ACK must never land in the inbox (inboxSenderIds=${a.inboxSenderIds})",
                b.userId !in a.inboxSenderIds,
            )

            // Teeth: a bogus id was never acked.
            assertThrows(AssertionError::class.java) {
                assertTrue("no receipt for a fabricated id", "deadbeef" in receipts)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a relay that is not the recipient does NOT ACK`() = kotlinx.coroutines.runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val a = SimNode(0, scope)  // sender
            val r = SimNode(1, scope)  // pure relay
            val b = SimNode(2, scope)  // intended recipient (never comes online here)

            val dm = a.gossipEngine.composeDirect(
                recipientId = b.userId,
                recipientPublicKey = b.identityProvider.identity.value!!.publicKeyBytes,
                text = "for B, via R",
            ) ?: error("composeDirect returned null")
            a.flushSchedulerToRepo()

            // A → R: R relays the DM (recipientId == B != R) but must not ACK.
            SimTransport(a, r).exchange(kotlin.random.Random(3))
            awaitUntil { r.knownMessages().any { it.id == dm.id } }
            r.flushSchedulerToRepo()

            assertTrue(
                "A pure relay must NOT compose a DIRECT_ACK for a DM addressed to someone else",
                r.knownMessages().none { it.type == MessageType.DIRECT_ACK },
            )
        } finally {
            scope.cancel()
        }
    }
}
