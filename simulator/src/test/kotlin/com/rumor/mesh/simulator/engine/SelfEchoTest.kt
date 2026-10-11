package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.UserMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 *
 * **2026-10-11 — this test was vacuous and is now not.** It awaited the beacon
 * landing in B's *repo*, but `handleSelfPresence` ingests SELF_PRESENCE with
 * `persist = false` by design (ephemeral control traffic), so that condition
 * could never hold. `awaitUntil` returned silently on timeout back then, so the
 * test went on to assert "A's own id is absent from A's view" against a mesh
 * where **the beacon had never moved at all** — an absence that holds for free
 * whether the self-echo guard exists or not. Surfaced by making `awaitUntil`
 * fail loudly; it then failed 3/3 in isolation.
 *
 * The fix is the two positive controls now asserted before the absence: B really
 * received and *processed* the beacon (it appears in B's own MeshView), and B
 * really *offers it back* to A. Without those, "self never appeared" cannot
 * distinguish a working guard from a message that never arrived.
 */
class SelfEchoTest {

    @Test
    fun `a node drops its own beacon relayed back and never records self in its mesh view`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)

        val beacon = a.gossipEngine.composeSelfPresence(UserMode.STATIC)!!
        a.flushSchedulerToRepo()

        // A → B. Await the DEDUP set, not the repo: SELF_PRESENCE is ingested with
        // persist=false, so b.knownMessages() is the one signal that can never fire.
        SimTransport(a, b).exchange(Random(1))
        awaitUntil(what = "B to see A's beacon") { beacon.id in b.gossipEngine.knownMessageIds() }

        // Control 1 — B didn't just dedup the bytes, it ran handleSelfPresence on
        // them. If this fails the absence assertion below proves nothing.
        awaitUntil(what = "B to record A in its own MeshView") {
            a.userId in b.meshView.assembleView().modes.keys
        }

        // Control 2 — the echo is actually attempted. B relays the beacon, so its
        // offer back to A contains A's own message; that is the input the guard
        // has to drop. (Pre-2026-10-11 this test never got this far.)
        assertTrue(
            "B must offer A's own beacon back to A — otherwise there is no echo to test",
            b.gossipEngine.messagesForExchange(a.userId).any { it.id == beacon.id },
        )

        // B → A: handleSelfPresence would record A into A's own MeshView if the
        // self-echo guard didn't drop it first. A never beacons about itself, so a
        // self entry here means the echo leaked in.
        repeat(6) { SimTransport(b, a).exchange(Random(it + 2)) }
        delay(300) // absence assertion: settle, per docs/SIMULATOR_TESTING.md

        assertFalse(
            "A's own userId must not appear in its assembled view via a relayed self-beacon",
            a.userId in a.meshView.assembleView().modes.keys,
        )
        assertFalse(
            "…nor in A's own online tracker",
            a.userId in a.onlineTracker.statuses.value,
        )
        scope.cancel()
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
        awaitUntil(what = "A to see B online") { b.userId in a.onlineTracker.statuses.value }

        assertTrue(
            "A should know B is online",
            a.userId != b.userId && b.userId in a.onlineTracker.statuses.value,
        )
        assertFalse(
            "A must never record its own userId as an online peer",
            a.userId in a.onlineTracker.statuses.value,
        )
        scope.cancel()
    }
}
