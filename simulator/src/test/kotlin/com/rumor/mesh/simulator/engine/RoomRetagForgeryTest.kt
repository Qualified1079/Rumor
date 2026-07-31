package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.data.RoomSubscription
import com.rumor.mesh.core.data.RoomSubscriptionMode
import com.rumor.mesh.core.protocol.RoomRoutingTag
import com.rumor.mesh.core.wire.roomRoutingTag
import com.rumor.mesh.core.wire.withRoomRoutingTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O157 — keyless room-retag forgery must fail.
 *
 * The routing tag for an OPEN room is a pure public function of the roomId
 * (`openRoomTag(roomId)` — no secret), and before O157 it rode unsigned in
 * `_ext.rt`. So a relay could take any validly-signed OPEN room message, swap
 * `_ext.rt` for the (equally public) tag of a DIFFERENT room, and rebroadcast:
 * the outer signature still verified, the tag matched, and the sender's content
 * displayed to a room they never posted to — attributed to them, no key needed.
 *
 * O157 binds the tag into the signed transcript, so the retag now breaks the
 * signature and is dropped at verify. This drives the attack through a real
 * SimTransport exchange with a well-formed discrimination control, per
 * docs/SIMULATOR_TESTING.md (no bare-object assertions).
 */
class RoomRetagForgeryTest {

    @Test
    fun `retagging a signed room message into another room is dropped at verify`() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val alice = SimNode(0, scope)   // honest author
            val mallory = SimNode(1, scope) // relay that retags
            val carol = SimNode(2, scope)   // subscriber of the TARGET room

            val roomA = "alice-home-room"
            val roomB = "carols-room"
            carol.roomSubscriptionRepo.upsert(
                RoomSubscription(roomB, RoomSubscriptionMode.OPEN, ByteArray(0), 0L),
            )
            val carolInbox = MutableStateFlow<List<String>>(emptyList())
            scope.launch { carol.gossipEngine.incomingMessages.collect { m -> carolInbox.update { it + m.id } } }
            delay(20)

            // Discrimination control: alice honestly posts to roomB. MUST arrive.
            val good = alice.gossipEngine.composeRoomMessage(
                routingTag = RoomRoutingTag.openRoomTag(roomB),
                plaintext = "honest post to B",
                recipients = emptyList(),
            ) ?: error("compose failed")

            // Attack: alice's roomA post, retagged to roomB's (public) tag.
            val roomAPost = alice.gossipEngine.composeRoomMessage(
                routingTag = RoomRoutingTag.openRoomTag(roomA),
                plaintext = "private-ish post meant for A",
                recipients = emptyList(),
            ) ?: error("compose failed")
            val retagged = roomAPost.withRoomRoutingTag(good.roomRoutingTag!!)

            // Mallory relays both toward carol.
            mallory.seedMessage(good)
            mallory.seedMessage(retagged)

            SimTransport(mallory, carol).exchange(kotlin.random.Random(1))
            delay(60)

            // Teeth: the honest roomB post reached carol.
            assertTrue(
                "discrimination control: honest roomB post MUST reach the subscriber",
                carolInbox.value.contains(good.id),
            )
            // The property under test: the retagged post did NOT.
            assertFalse(
                "retagged roomA->roomB post MUST NOT reach the target room's subscriber",
                carolInbox.value.contains(retagged.id),
            )
            // Stronger than unmatched: the forgery is dropped at the verify gate,
            // so it never even enters carol's store.
            assertFalse(
                "retagged post MUST be dropped at verify, not stored",
                carol.knownMessages().any { it.id == retagged.id },
            )
        } finally {
            scope.cancel()
        }
    }
}
