package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.TrustLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O162 defense-in-depth: a BRIDGED message that reaches the durable store must
 * never be offered to peers. relay() already refuses BRIDGED, but the
 * store-backfill serve path (messagesForExchange → offerable) and the manual
 * Feed-relay path bypass relay() — re-broadcasting unsigned bridge traffic
 * there would launder a foreign message onto the signed mesh. The trustLevel
 * only survives a store round-trip because O162 added the MessageEntity column;
 * without it every reloaded message reads back VERIFIED and this guard is blind.
 *
 * Synthetic BRIDGED broadcasts stand in for the future persisted-bridge case
 * (O161/O17): the guard keys on trustLevel and is type-agnostic, so a BROADCAST
 * exercises the serve path exactly as a persisted bridged message would.
 */
class BridgedNotServedTest {

    private fun SimNode.broadcast(id: String, trust: TrustLevel) = RumorMessage(
        id = id,
        senderId = userId,
        senderPublicKey = "cGs=",
        sequenceNumber = 1,
        sentAtMs = 1_000L,
        type = MessageType.BROADCAST,
        hopsToLive = 5,
        payload = MessagePayload(ContentType.TEXT, "hello"),
        signature = "c2ln",
        trustLevel = trust,
    )

    @Test
    fun `BRIDGED messages in the store are never offered to peers`() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val a = SimNode(0, scope)
            val peer = SimNode(1, scope)

            // Identical broadcasts; only trustLevel differs.
            val verified = a.broadcast("11".repeat(16), TrustLevel.VERIFIED)
            val bridged = a.broadcast("22".repeat(16), TrustLevel.BRIDGED)
            a.seedMessage(verified)
            a.seedMessage(bridged)

            val offeredIds = a.gossipEngine.messagesForExchange(peer.userId).map { it.id }.toSet()

            // Discrimination control — the filter is not a blackhole: the
            // VERIFIED broadcast of identical shape IS offered.
            assertTrue("VERIFIED broadcast must still be offered", offeredIds.contains(verified.id))
            // The defense — BRIDGED is never served.
            assertFalse("BRIDGED must never be offered to peers", offeredIds.contains(bridged.id))
            // Teeth — prove the assertion above can actually fail.
            assertThrows(AssertionError::class.java) {
                assertTrue(offeredIds.contains(bridged.id))
            }
        } finally {
            scope.cancel()
        }
    }
}
