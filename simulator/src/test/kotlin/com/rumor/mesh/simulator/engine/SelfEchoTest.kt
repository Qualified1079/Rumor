package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.UserMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Regression for the O98-phase3 field bug: once SELF_PRESENCE beacons fire on a
 * timer, a node's own authored messages loop back through a relaying peer. Two
 * self-inclusion paths must be closed:
 *
 *  1. **Message echo** — a peer relays our own beacon back to us. It is never
 *     news; [com.rumor.mesh.core.protocol.GossipEngine] drops any PEER-sourced
 *     message whose senderId is our own. (SELF_PRESENCE is ephemeral and, unlike
 *     broadcasts, isn't `ingestOwn`'d, so without the guard it re-ingests.)
 *  2. **Online-status echo** — a peer's exchange-time online snapshot contains
 *     *us* (it just saw us). Merging it verbatim marks our own userId ONLINE in
 *     our own tracker, surfacing self as an unnamed contact.
 */
class SelfEchoTest {

    @Test
    fun `a node drops its own beacon relayed back and never records self in its mesh view`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)

        val beacon = a.gossipEngine.composeSelfPresence(UserMode.STATIC)!!
        a.flushSchedulerToRepo()

        // A → B: B learns and stores A's beacon.
        SimTransport(a, b).exchange(Random(1))
        awaitUntil { b.knownMessages().any { it.id == beacon.id } }

        // B → A: B offers A's own beacon back. handleSelfPresence would record A
        // into A's own MeshView if the self-echo guard didn't drop it first. A
        // never beacons about itself, so a self entry here means the echo leaked in.
        repeat(6) { SimTransport(b, a).exchange(Random(it + 2)) }
        awaitUntil { b.knownMessages().any { it.id == beacon.id } } // settle handlers

        assertFalse(
            "A's own userId must not appear in its assembled view via a relayed self-beacon",
            a.userId in a.meshView.assembleView().modes.keys,
        )
    }

    @Test
    fun `a node never lists its own userId as an online peer`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)

        // Both compose so every round delivers a message each way (the harness
        // only emits an exchange result when ≥1 message moves). That makes each
        // node record the other as a direct contact and then share its whole
        // snapshot — which now contains itself — back on the next round.
        a.gossipEngine.composeBroadcast("hello from a")
        b.gossipEngine.composeBroadcast("hello from b")
        a.flushSchedulerToRepo()
        b.flushSchedulerToRepo()
        repeat(4) {
            SimTransport(a, b).exchange(Random(it))
            SimTransport(b, a).exchange(Random(it + 100))
        }
        awaitUntil { b.userId in a.onlineTracker.statuses.value }

        assertTrue(
            "A should know B is online",
            a.userId != b.userId && b.userId in a.onlineTracker.statuses.value,
        )
        assertFalse(
            "A must never record its own userId as an online peer",
            a.userId in a.onlineTracker.statuses.value,
        )
    }
}
