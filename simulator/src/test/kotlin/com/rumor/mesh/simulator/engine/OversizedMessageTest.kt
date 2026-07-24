package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MAX_BROADCAST_CONTENT_BYTES
import com.rumor.mesh.core.model.MAX_OFFER_BATCH_BYTES
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.approxWireBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O132 — the broadcast size cap + head-of-line-block fix.
 *
 * Field-confirmed bug: an oversized (>4 MB) unchunked broadcast in a node's
 * store is re-offered every round; on the real TCP transport its frame trips the
 * GossipSession 4 MB read guard, resets the session, and wedges ALL sync to the
 * peer (a tiny follow-up broadcast reached none of 3 phones). The fix: refuse to
 * compose oversized gossip text, drop it at ingest, and — the load-bearing part
 * — never place an un-offerable message in an offer batch (skip it, don't
 * send-and-reset).
 *
 * The in-memory SimTransport can't reproduce the real 4 MB frame reset (it passes
 * objects, not bytes), so this pins the *offer-batch skip logic* that prevents
 * the poison from ever entering a frame; the end-to-end frame behavior is
 * re-validated on the headless `:node` over real TCP.
 */
class OversizedMessageTest {

    private fun bcast(node: SimNode, id: String, text: String) = RumorMessage(
        id = id,
        senderId = node.userId,
        senderPublicKey = "unused-at-offer-layer",
        sequenceNumber = 1,
        sentAtMs = 1_000L,
        type = MessageType.BROADCAST,
        hopsToLive = 5,
        payload = MessagePayload(ContentType.TEXT, text),
        signature = "seeded",
    )

    @Test
    fun `composeBroadcast refuses oversized text but accepts normal`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val node = SimNode(0, scope)
        try {
            assertNotNull("a normal broadcast composes", node.gossipEngine.composeBroadcast("hello mesh"))
            val huge = "A".repeat(MAX_BROADCAST_CONTENT_BYTES + 1)
            assertNull("an oversized broadcast must be refused", node.gossipEngine.composeBroadcast(huge))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an oversized message in the store is skipped from offer batches while normals are offered`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val node = SimNode(0, scope)
        val peer = SimNode(1, scope)
        try {
            // Simulate a legacy/pre-cap oversized message already sitting in the
            // store, flanked by two normal broadcasts.
            node.seedMessage(bcast(node, "normal-1", "first"))
            // Just over MAX_OFFERABLE_MESSAGE_BYTES (1 MB) — un-offerable. Kept
            // modest so the whole sim suite doesn't OOM a shared worker.
            node.seedMessage(bcast(node, "poison", "B".repeat(1_100_000)))
            node.seedMessage(bcast(node, "normal-2", "second"))

            val offered = node.gossipEngine.messagesForExchange(peer.userId).map { it.id }.toSet()

            // The fix: the poison is skipped (so it can never form a >4 MB frame
            // that wedges the link), the normals are still offered.
            assertFalse("the oversized message must be skipped from the offer batch", "poison" in offered)
            assertTrue("normal messages must still be offered — no head-of-line block", "normal-1" in offered)
            assertTrue("normal messages must still be offered — no head-of-line block", "normal-2" in offered)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `offer batch is trimmed to the cumulative byte budget, not just the per-message cap`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val node = SimNode(0, scope)
        val peer = SimNode(1, scope)
        try {
            // Each ~60 KB message is individually offerable (< 1 MB) and under the
            // 64 KB broadcast cap, but their cumulative wire size far exceeds the
            // 3 MB offer-batch budget — so the batch must stop early (the `break`
            // path in budgetOfferBatch), not offer all of them.
            val body = "C".repeat(60_000)
            val count = 70
            repeat(count) { i -> node.seedMessage(bcast(node, "m$i", body)) }

            val offered = node.gossipEngine.messagesForExchange(peer.userId)
            val cumulative = offered.sumOf { it.approxWireBytes().toLong() }

            assertTrue("batch must stay within the cumulative byte budget", cumulative <= MAX_OFFER_BATCH_BYTES)
            assertTrue("not every seeded message fits — the batch is trimmed", offered.size < count)
            assertTrue("but a meaningful batch is still offered", offered.isNotEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `NEGATIVE CONTROL - without the poison the harness offers everything (skip is real, not a blanket drop)`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val node = SimNode(0, scope)
        val peer = SimNode(1, scope)
        try {
            node.seedMessage(bcast(node, "normal-1", "first"))
            node.seedMessage(bcast(node, "normal-2", "second"))
            val offered = node.gossipEngine.messagesForExchange(peer.userId).map { it.id }.toSet()
            assertTrue("both normals offered when nothing is oversized", offered.containsAll(setOf("normal-1", "normal-2")))
        } finally {
            scope.cancel()
        }
    }
}
