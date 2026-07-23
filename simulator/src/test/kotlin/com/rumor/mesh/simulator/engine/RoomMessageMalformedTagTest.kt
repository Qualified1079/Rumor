package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.data.RoomSubscription
import com.rumor.mesh.core.data.RoomSubscriptionMode
import com.rumor.mesh.core.protocol.RoomRoutingTag
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
 * O79 receive-side dispatch robustness (O129 fix — was vacuous).
 *
 * A malformed routing tag must NOT crash, NOT emit to inbox, and NOT leak
 * state — a clean drop while the relay/store path still runs. The previous
 * version of this test built the tampered message as a bare in-memory object
 * and asserted it wasn't in the inbox WITHOUT ever sending it — trivially
 * true regardless of whether the real drop path worked (the exact O129 trap
 * documented in docs/SIMULATOR_TESTING.md). This version drives both the
 * attack message AND a well-formed discrimination control through a real
 * SimTransport exchange so the assertion has teeth.
 */
class RoomMessageMalformedTagTest {

    @Test
    fun `malformed routing tag drops from inbox but still stores - via real transport`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val alice = SimNode(0, scope)
        val bob = SimNode(1, scope)

        val room = "real-room"
        bob.roomSubscriptionRepo.upsert(
            RoomSubscription(room, RoomSubscriptionMode.OPEN, ByteArray(0), 0L),
        )
        val bobInbox = MutableStateFlow<List<String>>(emptyList())
        scope.launch { bob.gossipEngine.incomingMessages.collect { m -> bobInbox.update { it + m.id } } }
        delay(20)

        // Discrimination control: a well-formed message to the room bob is
        // subscribed to. It MUST reach the inbox — otherwise a false "drop"
        // would pass this test vacuously.
        val good = alice.gossipEngine.composeRoomMessage(
            routingTag = RoomRoutingTag.openRoomTag(room),
            plaintext = "well-formed",
            recipients = emptyList(),
        ) ?: error("compose failed")

        // Attack: the same compose, then the routing tag tampered to a
        // non-base64 value. The outer Ed25519 signature does NOT cover _ext,
        // so the sig stays valid — exactly what a confused relay could emit.
        val base = alice.gossipEngine.composeRoomMessage(
            routingTag = RoomRoutingTag.openRoomTag(room),
            plaintext = "tag tamper",
            recipients = emptyList(),
        ) ?: error("compose failed")
        val tampered = base.withRoomRoutingTag("%%% not base64 %%%")

        // Seed BOTH into alice's repo so the exchange offers them. seedMessage
        // records the id in the dup filter so every knowledge view agrees.
        alice.flushSchedulerToRepo()          // 'good' rides the scheduler
        alice.seedMessage(tampered)           // 'tampered' injected directly

        SimTransport(alice, bob).exchange(kotlin.random.Random(1))
        delay(60)

        // Teeth: the good message reached the inbox.
        assertTrue(
            "discrimination control: well-formed room message MUST emit to inbox",
            bobInbox.value.contains(good.id),
        )
        // The property under test: the malformed-tag message did NOT.
        assertFalse(
            "malformed routing tag MUST NOT emit to inbox",
            bobInbox.value.contains(tampered.id),
        )
        // Relay-never-filters: the malformed message still propagated into
        // bob's store (drop is inbox-only, the mesh keeps carrying it).
        assertTrue(
            "malformed message MUST still be stored/relayed (relay path is filter-blind)",
            bob.knownMessages().any { it.id == tampered.id },
        )

        scope.cancel()
    }

    @Test
    fun `composeRoomMessage with 0-byte routing tag still produces a valid signed message`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val alice = SimNode(0, scope)
        val msg = alice.gossipEngine.composeRoomMessage(
            routingTag = ByteArray(0),
            plaintext = "degenerate",
            recipients = emptyList(),
        )
        assertFalse("compose with degenerate tag returns non-null", msg == null)
        scope.cancel()
    }
}
