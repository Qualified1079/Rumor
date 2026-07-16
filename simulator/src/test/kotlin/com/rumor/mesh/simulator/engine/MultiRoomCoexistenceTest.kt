package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.data.RoomSubscription
import com.rumor.mesh.core.data.RoomSubscriptionMode
import com.rumor.mesh.core.protocol.RoomRoutingTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O79 sanity for multi-room coexistence + subscription lifecycle:
 *
 *  - A node subscribed to multiple rooms emits messages from each
 *    to its inbox without mixing them up.
 *  - Unsubscribing from a room stops emission from that room while
 *    leaving other subscriptions intact.
 *
 * These are integration regressions worth pinning because the
 * RoomTagMatcher walks every subscription on every inbound; an
 * O(N) loop misbehaving (e.g. returning a match for the wrong
 * roomId) would surface here, not in unit tests.
 */
class MultiRoomCoexistenceTest {

    @Test
    fun `node subscribed to two rooms receives messages from each in its own bucket`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val alice = SimNode(0, scope)  // sender for room A
        val carol = SimNode(1, scope)  // sender for room B
        val bob = SimNode(2, scope)    // subscribed to BOTH rooms

        val roomA = "room-A"
        val roomB = "room-B"
        bob.roomSubscriptionRepo.upsert(RoomSubscription(roomA, RoomSubscriptionMode.OPEN, ByteArray(0), 0L))
        bob.roomSubscriptionRepo.upsert(RoomSubscription(roomB, RoomSubscriptionMode.OPEN, ByteArray(0), 0L))

        val bobInbox = MutableStateFlow<List<String>>(emptyList())
        scope.launch { bob.gossipEngine.incomingMessages.collect { m -> bobInbox.update { it + m.id } } }
        delay(20)

        val msgA = alice.gossipEngine.composeRoomMessage(
            routingTag = RoomRoutingTag.openRoomTag(roomA),
            plaintext = "A-message",
            recipients = emptyList(),
        ) ?: error("alice compose failed")
        val msgB = carol.gossipEngine.composeRoomMessage(
            routingTag = RoomRoutingTag.openRoomTag(roomB),
            plaintext = "B-message",
            recipients = emptyList(),
        ) ?: error("carol compose failed")
        alice.flushSchedulerToRepo()
        carol.flushSchedulerToRepo()

        // Both senders deliver to bob via independent exchanges.
        SimTransport(alice, bob).exchange(kotlin.random.Random(1))
        delay(50)
        SimTransport(carol, bob).exchange(kotlin.random.Random(2))
        delay(50)

        assertTrue("bob received message from room A",
            bobInbox.value.contains(msgA.id))
        assertTrue("bob received message from room B",
            bobInbox.value.contains(msgB.id))
    }

    @Test
    fun `unsubscribing from one room stops emit for that room only`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val alice = SimNode(0, scope)
        val bob = SimNode(1, scope)

        val roomKeep = "keep-this"
        val roomDrop = "drop-this"
        bob.roomSubscriptionRepo.upsert(RoomSubscription(roomKeep, RoomSubscriptionMode.OPEN, ByteArray(0), 0L))
        bob.roomSubscriptionRepo.upsert(RoomSubscription(roomDrop, RoomSubscriptionMode.OPEN, ByteArray(0), 0L))

        val bobInbox = MutableStateFlow<List<String>>(emptyList())
        scope.launch { bob.gossipEngine.incomingMessages.collect { m -> bobInbox.update { it + m.id } } }
        delay(20)

        // Phase 1: subscribed to both. Alice sends to roomDrop; bob should emit.
        val before = alice.gossipEngine.composeRoomMessage(
            routingTag = RoomRoutingTag.openRoomTag(roomDrop),
            plaintext = "pre-unsubscribe",
            recipients = emptyList(),
        ) ?: error("compose failed")
        alice.flushSchedulerToRepo()
        SimTransport(alice, bob).exchange(kotlin.random.Random(10))
        delay(50)
        assertTrue("bob emitted message from roomDrop while subscribed",
            bobInbox.value.contains(before.id))

        // Phase 2: bob unsubscribes from roomDrop. Alice sends ANOTHER
        // message to roomDrop. Bob's inbox MUST NOT receive it.
        bob.roomSubscriptionRepo.delete(roomDrop)
        delay(20)
        val after = alice.gossipEngine.composeRoomMessage(
            routingTag = RoomRoutingTag.openRoomTag(roomDrop),
            plaintext = "post-unsubscribe",
            recipients = emptyList(),
        ) ?: error("compose failed")
        alice.flushSchedulerToRepo()
        SimTransport(alice, bob).exchange(kotlin.random.Random(11))
        delay(50)
        assertFalse("bob's inbox MUST NOT contain the post-unsubscribe message",
            bobInbox.value.contains(after.id))

        // Phase 3: roomKeep still works — Alice sends there, bob still
        // emits, proving the unsubscribe didn't blow away unrelated state.
        val keep = alice.gossipEngine.composeRoomMessage(
            routingTag = RoomRoutingTag.openRoomTag(roomKeep),
            plaintext = "still subscribed to keep",
            recipients = emptyList(),
        ) ?: error("compose failed")
        alice.flushSchedulerToRepo()
        SimTransport(alice, bob).exchange(kotlin.random.Random(12))
        delay(50)
        assertTrue("roomKeep subscription survived the roomDrop unsubscribe",
            bobInbox.value.contains(keep.id))
    }

    @Test
    fun `subscription count grows and shrinks correctly`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val node = SimNode(0, scope)
        assertEquals(0, node.roomSubscriptionRepo.getAll().size)

        for (i in 1..5) {
            node.roomSubscriptionRepo.upsert(
                RoomSubscription("room-$i", RoomSubscriptionMode.OPEN, ByteArray(0), 0L),
            )
        }
        assertEquals(5, node.roomSubscriptionRepo.getAll().size)

        node.roomSubscriptionRepo.delete("room-3")
        node.roomSubscriptionRepo.delete("room-5")
        assertEquals(3, node.roomSubscriptionRepo.getAll().size)
        assertEquals(
            setOf("room-1", "room-2", "room-4"),
            node.roomSubscriptionRepo.getAll().map { it.roomId }.toSet(),
        )
    }
}
