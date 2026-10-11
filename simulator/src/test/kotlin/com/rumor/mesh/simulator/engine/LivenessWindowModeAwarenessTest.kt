package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.wire.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O205 — the O198 liveness window must scale with the mode's contact cadence instead
 * of being a fixed 30 s.
 *
 * Same diamond as [SmartRoutingLivenessTest]: crumb chain A→m1→m2→m3→B plus a live
 * bypass m1─r─m3. Here m2 is **alive but quiet** — every node exchanges on a 45 s
 * sim-clock beat, which is what a duty-cycled MOBILE device under O55 actually looks
 * like (it meets peers on the 30 s Wi-Fi-discovery beat, not the gossip beat).
 *
 * 45 s sits deliberately between the two derived windows, so the same topology and
 * the same traffic separate the modes:
 *  - MOBILE (window 90 s): m2's 45 s-old exchange still counts live → m1 keeps the
 *    DM on the trail, the bypass `r` never sees it. The pre-O205 fixed 30 s window
 *    would have false-flooded here — spraying the DM (and burning radio) on a link
 *    that was never down.
 *  - FREE (window 15 s): 45 s of silence is genuinely stale for a continuously-
 *    discovering anchor → m1 floods → `r` sees it.
 *
 * `r` holding the DM is the flood detector; B holding it is the discrimination
 * control (suppressing the false flood must not strand the message).
 */
class LivenessWindowModeAwarenessTest {

    private val A = 0; private val B = 1
    private val m1 = 2; private val m2 = 3; private val m3 = 4; private val r = 5

    /** 45 s: longer than FREE's 15 s window, shorter than MOBILE's 90 s. */
    private val beatMs = 45_000L

    @Test
    fun `engine window follows the live mode and an explicit override beats both`() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val n = SimNode(0, scope)
            assertEquals(90_000L, n.gossipEngine.livenessWindowMs)
            n.mode = UserMode.STATIC
            assertEquals(45_000L, n.gossipEngine.livenessWindowMs)
            n.mode = UserMode.FREE
            assertEquals(15_000L, n.gossipEngine.livenessWindowMs)
            n.gossipEngine.livenessWindowOverrideMs = 7_000L
            assertEquals(7_000L, n.gossipEngine.livenessWindowMs)
            n.mode = UserMode.MOBILE
            assertEquals("override must ignore the mode", 7_000L, n.gossipEngine.livenessWindowMs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a quiet-but-alive relay is not false-flooded under MOBILE but is under FREE`() = runBlocking<Unit> {
        val mobile = run(mode = UserMode.MOBILE, m2Alive = true)
        val free = run(mode = UserMode.FREE, m2Alive = true)
        println("O205-RESULT mobile=$mobile free=$free")

        assertFalse(
            "MOBILE's 90s window must treat a 45s-quiet relay as live → no flood to the bypass",
            mobile.bypassSawDm,
        )
        assertTrue("…and the DM must still reach B on the trail (not a blackhole)", mobile.delivered)

        assertTrue(
            "FREE's 15s window must treat 45s of silence as stale → flood to the bypass",
            free.bypassSawDm,
        )
        assertTrue("…and FREE still delivers", free.delivered)
    }

    @Test
    fun `the window VALUE is what drives the outcome — pinning the pre-O205 30s re-breaks it`() = runBlocking<Unit> {
        // Negative control for the test above. MOBILE-vs-FREE differ in more than the
        // window (gossip cadence, scan power, storage boost…), so "MOBILE doesn't
        // flood" alone does not prove the WINDOW is the cause. Here the mode is held
        // at MOBILE and only the window is pinned back to the old fixed 30 s: the
        // bypass sees the DM again. That is simultaneously (a) proof the assertion
        // keys on the window and not on some other mode difference, and (b) a
        // reproduction of the O205 bug itself.
        val pinnedToOldDefault = run(mode = UserMode.MOBILE, m2Alive = true, windowOverrideMs = 30_000L)
        println("O205-RESULT mobile-pinned-30s=$pinnedToOldDefault")
        assertTrue(
            "the pre-O205 fixed 30s window false-floods a 45s-quiet live link",
            pinnedToOldDefault.bypassSawDm,
        )
    }

    @Test
    fun `a genuinely dead relay still triggers the flood under the long MOBILE window`() = runBlocking<Unit> {
        // The risk of lengthening the window is under-reacting to a real dropout. It
        // doesn't apply: a never-exchanged peer has no recorded contact at all, so the
        // window length is irrelevant and the fallback still fires.
        val dead = run(mode = UserMode.MOBILE, m2Alive = false)
        println("O205-RESULT dead-relay-under-mobile=$dead")
        assertTrue("a dead next-hop must flood regardless of window length", dead.bypassSawDm)
        assertTrue("…and deliver via the bypass", dead.delivered)
    }

    private data class Outcome(val bypassSawDm: Boolean, val delivered: Boolean)

    private suspend fun run(mode: UserMode, m2Alive: Boolean, windowOverrideMs: Long? = null): Outcome {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val clock = SimClock(1_700_000_000_000L)
            val n = (0..5).map { SimNode(it, scope, clock = clock) }
            n.forEach { it.mode = mode; it.gossipEngine.livenessWindowOverrideMs = windowOverrideMs }
            registerContact(n[A], n[B])

            val allEdges = listOf(A to m1, m1 to m2, m2 to m3, m3 to B, m1 to r, r to m3)
            val edges = if (m2Alive) allEdges
            else allEdges.filterNot { it.first == m2 || it.second == m2 }

            // Straight-line crumb trail: each hop's only candidate for B is the next.
            n[A].breadcrumbs.record(n[B].userId, n[m1].userId)
            n[m1].breadcrumbs.record(n[B].userId, n[m2].userId)
            n[m2].breadcrumbs.record(n[B].userId, n[m3].userId)
            n[m3].breadcrumbs.record(n[B].userId, n[B].userId)
            delay(50)

            // Warm-up: three HELLO-only beats so every node has a *recent* first-hand
            // contact record for its neighbours before the DM is composed. Without it
            // the very first routing decision sees no record at all for the next hop
            // (indistinguishable from "dead") and floods in every mode — which would
            // make this test measure the cold-start case, not the window length.
            repeat(3) {
                clock.nowMs += beatMs
                for ((x, y) in edges) { touch(n[x], n[y]); touch(n[y], n[x]) }
                delay(5)
            }

            val dmId = n[A].gossipEngine.composeDirect(
                recipientId = n[B].userId,
                recipientPublicKey = n[B].identityProvider.identity.value!!.publicKeyBytes,
                text = "quiet is not dead",
            )?.id ?: error("composeDirect null")

            repeat(20) {
                clock.nowMs += beatMs
                for ((x, y) in edges) { realExchange(n[x], n[y]); realExchange(n[y], n[x]) }
                delay(5)
            }
            delay(40)
            return Outcome(
                bypassSawDm = n[r].messageRepo.getById(dmId) != null,
                delivered = n[B].messageRepo.getById(dmId) != null,
            )
        } finally {
            scope.cancel()
        }
    }

    /** A round where the peers meet but neither has anything new — HELLO only. */
    private fun touch(src: SimNode, dst: SimNode) = deliver(src, dst, emptyList())

    /**
     * Unlike [SmartRoutingLivenessTest]'s helper this delivers the exchange even when
     * the offer is empty, because an empty round is exactly what this test measures:
     * a real `LanTransport.runRound` completes the HELLO (and so refreshes the peer's
     * liveness record) whether or not either side had a message to send. Returning
     * early on an empty offer would silently age every quiet link out of the window
     * and make even MOBILE flood — i.e. it would fake the bug.
     */
    private suspend fun realExchange(src: SimNode, dst: SimNode) {
        val ser = RumorMessage.serializer()
        val offer = src.gossipEngine.messagesForExchange(dst.userId)
            .map { WireJson.decodeFromString(ser, WireJson.encodeToString(ser, it)) }
        deliver(src, dst, offer)
    }

    private fun deliver(src: SimNode, dst: SimNode, offer: List<RumorMessage>) {
        dst.deliverExchange(
            PeerExchangeResult(
                peerUserId = src.userId,
                messagesReceived = offer,
                ackedByPeer = emptyList(),
                peerOnlineUsers = mapOf(src.userId to src.clock.now()),
                durationMs = 0,
            )
        )
    }

    private suspend fun registerContact(host: SimNode, other: SimNode) {
        val id = other.identityProvider.identity.value!!
        host.contactRepoForTest().upsert(
            com.rumor.mesh.core.model.Contact(
                userId = id.userId,
                publicKey = com.rumor.mesh.core.crypto.CryptoManager.run { id.publicKeyBytes.toBase64() },
                displayName = "n${other.index}", isVerified = false, autoRelay = false,
                alwaysSave = false, willingToCache = false, firstSeenMs = 0L,
                lastSeenMs = 0L, isPriorityPeer = false,
            )
        )
    }

    private fun SimNode.contactRepoForTest(): com.rumor.mesh.core.data.memory.InMemoryContactRepository =
        this::class.java.getDeclaredField("contactRepo").apply { isAccessible = true }
            .get(this) as com.rumor.mesh.core.data.memory.InMemoryContactRepository
}
