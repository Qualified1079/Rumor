package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.data.RoomSubscription
import com.rumor.mesh.core.data.RoomSubscriptionMode
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.RoomRoutingTag
import com.rumor.mesh.core.wire.roomRoutingTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O79 — multi-node scenario exercising ROOM_MESSAGE relay + receive-side
 * subscription dispatch through SimNode's real gossip path.
 *
 * Drives the same wire and code paths a production exchange would, plus
 * verifies the SimNode `roomSubscriptionProvider` wiring works (subscribed
 * nodes emit to inbox; non-subscribed nodes relay but skip inbox emission).
 */
class RoomMessageScenarioTest {

    @Test
    fun `OPEN room - message reaches every node via relay, subscribed nodes emit to inbox`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val alice = SimNode(0, scope)
        val bob = SimNode(1, scope)
        val charlie = SimNode(2, scope)
        val dave = SimNode(3, scope)

        val roomId = "neighborhood-watch"
        val tag = RoomRoutingTag.openRoomTag(roomId)

        // Bob and Charlie subscribe to the room; Dave does NOT.
        bob.roomSubscriptionRepo.upsert(RoomSubscription(roomId, RoomSubscriptionMode.OPEN, ByteArray(0), 0L))
        charlie.roomSubscriptionRepo.upsert(RoomSubscription(roomId, RoomSubscriptionMode.OPEN, ByteArray(0), 0L))

        // Collectors capture inbox emissions per node.
        val bobInbox = MutableStateFlow<List<String>>(emptyList())
        val charlieInbox = MutableStateFlow<List<String>>(emptyList())
        val daveInbox = MutableStateFlow<List<String>>(emptyList())
        scope.launch { bob.gossipEngine.incomingMessages.collect { m -> bobInbox.update { it + m.id } } }
        scope.launch { charlie.gossipEngine.incomingMessages.collect { m -> charlieInbox.update { it + m.id } } }
        scope.launch { dave.gossipEngine.incomingMessages.collect { m -> daveInbox.update { it + m.id } } }
        delay(20)  // let collectors attach

        // Alice composes a room message addressed to the OPEN room.
        val msg = alice.gossipEngine.composeRoomMessage(
            routingTag = tag,
            plaintext = "house fire on 3rd ave",
            recipients = emptyList(),  // OPEN = no encryption, no per-recipient wraps
        ) ?: error("composeRoomMessage returned null — identity unlocked?")
        alice.flushSchedulerToRepo()
        delay(20)

        // Sanity: the message has the routing tag stamped in _ext.rt.
        val composedTag = msg.roomRoutingTag
        assertNotNull("composeRoomMessage must stamp _ext.rt", composedTag)
        assertEquals(tag.toBase64(), composedTag)

        // Propagate through the mesh: alice -> bob -> charlie -> dave.
        SimTransport(alice, bob).exchange(kotlin.random.Random(1))
        delay(50)
        bob.flushSchedulerToRepo()

        SimTransport(bob, charlie).exchange(kotlin.random.Random(2))
        delay(50)
        charlie.flushSchedulerToRepo()

        SimTransport(charlie, dave).exchange(kotlin.random.Random(3))
        delay(50)
        dave.flushSchedulerToRepo()

        // RELAY correctness: every node holds the message in its store
        // (relay propagates regardless of subscription).
        assertTrue("Bob holds the room message after A->B exchange",
            bob.knownMessages().any { it.id == msg.id })
        assertTrue("Charlie holds the room message after B->C exchange",
            charlie.knownMessages().any { it.id == msg.id })
        assertTrue("Dave holds the room message after C->D exchange (relay even though Dave isn't subscribed)",
            dave.knownMessages().any { it.id == msg.id })

        // ROUTING-TAG integrity: the tag survives every relay hop unchanged.
        val tagAtDave = dave.knownMessages().first { it.id == msg.id }.roomRoutingTag
        assertEquals("routing tag must survive every relay hop unchanged",
            tag.toBase64(), tagAtDave)

        // INBOX dispatch: bob and charlie emitted to their inboxes; dave did not.
        assertTrue("Bob (subscribed) emits the room message to inbox",
            bobInbox.value.contains(msg.id))
        assertTrue("Charlie (subscribed) emits the room message to inbox",
            charlieInbox.value.contains(msg.id))
        assertTrue("Dave (NOT subscribed) does NOT emit the room message to inbox (relay only)",
            !daveInbox.value.contains(msg.id))
    }

    @Test
    fun `non-subscribed node still relays room messages for the mesh`() = runBlocking {
        // Pure-relay sanity: a node that has zero room subscriptions still
        // propagates ROOM_MESSAGE traffic for downstream peers. The receive-
        // dispatch is a no-op (provider returns empty lists) but relay is
        // unconditional — room messages are broadcast-tier.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val alice = SimNode(0, scope)
        val bob = SimNode(1, scope)   // not subscribed to anything
        val charlie = SimNode(2, scope)
        val roomId = "downstream-room"
        charlie.roomSubscriptionRepo.upsert(
            RoomSubscription(roomId, RoomSubscriptionMode.OPEN, ByteArray(0), 0L),
        )
        val charlieInbox = MutableStateFlow<List<String>>(emptyList())
        scope.launch { charlie.gossipEngine.incomingMessages.collect { m -> charlieInbox.update { it + m.id } } }
        delay(20)

        val msg = alice.gossipEngine.composeRoomMessage(
            routingTag = RoomRoutingTag.openRoomTag(roomId),
            plaintext = "broadcast through bob",
            recipients = emptyList(),
        ) ?: error("compose failed")
        alice.flushSchedulerToRepo()

        // Alice -> Bob (Bob isn't subscribed) -> Charlie (subscribed).
        SimTransport(alice, bob).exchange(kotlin.random.Random(10))
        delay(50)
        bob.flushSchedulerToRepo()
        SimTransport(bob, charlie).exchange(kotlin.random.Random(11))
        delay(50)
        charlie.flushSchedulerToRepo()

        assertTrue("Bob relayed without inbox emission", bob.knownMessages().any { it.id == msg.id })
        assertTrue("Charlie received via Bob's relay and emitted to inbox",
            charlieInbox.value.contains(msg.id))
    }

    @Test
    fun `ROOM_MESSAGE is identified by MessageType at relay time`() = runBlocking {
        // Verifies the new MessageType.ROOM_MESSAGE enum value survives the
        // wire round-trip — a regression here would mean the SerialName
        // 'room_message' got lost somewhere in serialization.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val alice = SimNode(0, scope)
        val bob = SimNode(1, scope)

        val msg = alice.gossipEngine.composeRoomMessage(
            routingTag = ByteArray(16) { 0x42 },
            plaintext = "type check",
            recipients = emptyList(),
        ) ?: error("compose failed")
        alice.flushSchedulerToRepo()

        SimTransport(alice, bob).exchange(kotlin.random.Random(20))
        delay(50)
        bob.flushSchedulerToRepo()

        val received: RumorMessage = bob.knownMessages().first { it.id == msg.id }
        assertEquals("type must round-trip", MessageType.ROOM_MESSAGE, received.type)
    }
}
