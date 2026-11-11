package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A relay holding more broadcasts than one offer batch must still offer the DM
 * it is carrying. The store backfill kept the freshest 200 messages by sender
 * clock across every type, so a DM older than 200 broadcasts (a slow sender
 * clock is enough, as is a flood) dropped out of the window and was offered
 * once, from the scheduler, to whichever peer came first.
 */
class RelayedDmOfferSliceTest {

    private fun msg(id: String, type: MessageType, sentAtMs: Long, sender: String, recipient: String? = null) = RumorMessage(
        id = id,
        senderId = sender,
        senderPublicKey = "cGs=",
        sequenceNumber = 1,
        sentAtMs = sentAtMs,
        type = type,
        hopsToLive = 5,
        payload = if (type == MessageType.BROADCAST) MessagePayload(ContentType.TEXT, "junk") else null,
        encryptedPayload = if (type == MessageType.DIRECT) "ciphertext" else null,
        recipientId = recipient,
        signature = "c2ln",
    )

    private fun offeredAfterFlood(dmSentAtMs: Long, broadcasts: Int): Pair<Set<String>, String> = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val relay = SimNode(0, scope)
            val peer = SimNode(1, scope)
            val dm = msg("d".repeat(32), MessageType.DIRECT, dmSentAtMs, "a".repeat(64), "b".repeat(64))
            relay.seedMessage(dm)
            repeat(broadcasts) { i ->
                relay.seedMessage(msg("%032x".format(i + 1), MessageType.BROADCAST, 2_000_000L + i, "c".repeat(64)))
            }
            relay.gossipEngine.messagesForExchange(peer.userId).map { it.id }.toSet() to dm.id
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aDmInsideTheFreshestWindowIsOffered() {
        // Baseline: with few enough broadcasts the old window already carried it.
        val (offered, dm) = offeredAfterFlood(dmSentAtMs = 1_000L, broadcasts = 50)
        assertTrue("control failed: the DM is not offered even without a flood", dm in offered)
    }

    @Test
    fun aDmOlderThanAFullBatchOfBroadcastsIsStillOffered() {
        val (offered, dm) = offeredAfterFlood(dmSentAtMs = 1_000L, broadcasts = 400)
        assertTrue("the relayed DM fell out of the offer behind the broadcasts", dm in offered)
        assertFalse("the offer is not a blackhole for broadcasts either", (offered - dm).isEmpty())
    }
}
