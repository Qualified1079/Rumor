package com.rumor.mesh.simulator.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-wire validation of the O198 liveness routing prototype
 * (`GossipEngine.livenessRouting`) ahead of a possible default-on flip — over REAL
 * [com.rumor.mesh.core.transport.lan.LanTransport] (real TCP + GossipSession +
 * onlineStatusTracker populated by real exchanges), not the in-process engine of
 * [SmartRoutingLivenessTest]. This is the "validate on the desktop `:node`/LAN
 * harness, which forms real sessions (unlike phone Wi-Fi Direct), THEN flip"
 * gate — the phone O98/O99 group-formation blocker does not apply here.
 *
 * Diamond with a mid-path dropout, node indices: A=0, B=1, m1=2, m2=3, m3=4, r=5.
 *   straight crumb chain A→m1→m2→m3→B, live bypass m1─r─m3.
 * m2 is taken offline **from the start**, so it is never recorded ONLINE in m1's
 * tracker — which is exactly the condition liveness keys on. m1's single crumb
 * points at the (now-dead) m2:
 *   - liveness OFF (baseline): m1 offers only to the dead m2, never falls back → STRANDS.
 *   - liveness ON: m1's only crumb next-hop isn't ONLINE → floods → the DM reaches
 *     B via the live bypass r→m3.
 *
 * Slow (LanTransport gossips every 10 s; a 4-hop carry is tens of seconds) — a
 * real-wire spot-check, kept to two arms.
 *
 * Timeouts are deliberately generous (240 s for a 4-hop carry whose floor is ~40 s).
 * A wait that succeeds costs only what the delivery actually takes, so patience is
 * nearly free, and the 120 s ceiling was tight enough that this test failed under
 * full-suite CPU contention on a 4-core box — a flake, not a finding. Patience is
 * not a weakened assertion: what is asserted is unchanged.
 */
class LanLivenessValidationTest {

    private val EDGES = listOf(0 to 2, 2 to 3, 3 to 4, 4 to 1, 2 to 5, 5 to 4)

    /** Build the diamond, kill m2 before any exchange, lay the single stale crumb chain, send A→B. */
    private suspend fun startDiamond(liveness: Boolean, seed: Long): Pair<LanMeshHarness, String> {
        val h = LanMeshHarness(n = 6, seed = seed)
        h.nodes.forEach { it.gossipEngine.livenessRouting = liveness }
        h.start(edges = EDGES)
        h.setOnline(3, up = false)  // m2 dead from the start → never ONLINE in m1's tracker

        // READINESS GATE (2026-10-11). Six real LanTransports on a loaded box do not
        // always finish forming every session, and when the bypass m1─r─m3 fails to
        // come up the DM sits at m1 forever — which is indistinguishable, from the
        // assertions below, from the liveness fallback not working. That is exactly
        // how this test read as a routing regression once: instrumented, the stuck
        // run had NOTHING on the bypass at all, while runs where the bypass formed
        // delivered in ~30 s (also verified with the window forced fully stale).
        //
        // So prove the mesh exists before measuring routing on it: an unrestricted
        // broadcast from A must reach B over the bypass. It is sent BEFORE the crumbs
        // are laid, and it is a broadcast from A, so the crumbs it creates are for A
        // and cannot affect the DM's routing toward B.
        val probe = h.broadcast(from = 0, text = "readiness-probe") ?: error("broadcast null")
        check(h.awaitDelivered(probe, to = 1, timeoutMs = 240_000)) {
            "HARNESS NOT READY: a plain broadcast never crossed A→…→B, so the real-wire " +
                "mesh (specifically the m1─r─m3 bypass) never formed. Nothing about " +
                "routing can be concluded from this run — re-run; if it persists, the " +
                "LanTransport harness is the problem, not the routing under test."
        }

        // Single straight-line crumb trail — m1's only crumb is the (dead) m2.
        h.nodes[0].breadcrumbs.record(h.nodes[1].userId, h.nodes[2].userId) // A : B via m1
        h.nodes[2].breadcrumbs.record(h.nodes[1].userId, h.nodes[3].userId) // m1: B via (dead) m2
        h.nodes[4].breadcrumbs.record(h.nodes[1].userId, h.nodes[1].userId) // m3: B via B
        delay(300) // let the async breadcrumb upserts settle before composing
        val id = h.sendDm(from = 0, to = 1, text = "survive?")!!
        return h to id
    }

    @Test
    fun livenessOnDeliversAroundTheDeadCrumbTargetOverRealWire() = runBlocking {
        val (h, id) = startDiamond(liveness = true, seed = 11)
        try {
            assertTrue(
                "liveness ON must deliver to B via the live bypass over real LanTransport",
                h.awaitDelivered(id, to = 1, timeoutMs = 240_000),
            )
            // Discrimination: it did NOT go through the dead relay (m2 is offline).
            assertFalse(
                "the dead crumb-target m2 must not hold the DM (it's offline)",
                h.nodes[3].messageRepo.getById(id) != null,
            )
        } finally {
            h.stop()
        }
    }

    @Test
    fun livenessOnDoesNotRegressNormalMultiHopDelivery() = runBlocking {
        // The default-flip safety check: with liveness ON and NO crumbs, a routed DM
        // finds empty candidates → floods (same path as OFF), so an ordinary 2-hop
        // relay must still deliver over the real wire. Line 0—1—2, DM 0→2 via node 1.
        val h = LanMeshHarness(n = 3, seed = 13)
        h.nodes.forEach { it.gossipEngine.livenessRouting = true }
        try {
            h.start(edges = listOf(0 to 1, 1 to 2))
            val id = h.sendDm(from = 0, to = 2, text = "normal relay with liveness on")!!
            assertTrue(
                "liveness ON must NOT regress an ordinary 2-hop relay (no crumbs → flood)",
                h.awaitDelivered(id, to = 2, timeoutMs = 60_000),
            )
        } finally {
            h.stop()
        }
    }

    @Test
    fun baselineStrandsOnTheStaleCrumbOverRealWire() = runBlocking {
        val (h, id) = startDiamond(liveness = false, seed = 12)
        try {
            // The DM enters the mesh and reaches the relay m1 (proves it's not just lost)...
            assertTrue(
                "baseline: DM should reach relay m1 over the real wire",
                h.awaitDelivered(id, to = 2, timeoutMs = 45_000),
            )
            // ...but m1's only crumb points at the dead m2 and it never floods → stranded.
            //
            // PROGRESS CONTROL (2026-10-11). This assertion is an absence, and the
            // fixed `delay(25_000)` that used to precede it made the absence hold for
            // free whenever the box was too loaded to complete 4 gossip rounds — the
            // failure mode is a silent false PASS, which is worse than the flake in
            // the sibling test. So instead of waiting a fixed time, wait for proof
            // that this mesh CAN carry a message A→B in this window: an unrestricted
            // BROADCAST from A, over the very same live bypass (A→m1→r→m3→B) the
            // stranded DM declined to use. If the broadcast lands and the DM does not,
            // the strand is structural. If the broadcast never lands, the test fails
            // loudly instead of congratulating itself.
            val canary = h.broadcast(from = 0, text = "progress-control") ?: error("broadcast null")
            assertTrue(
                "progress control: an unrestricted broadcast must reach B over the live " +
                    "bypass — without this, 'the DM never arrived' cannot be distinguished " +
                    "from 'nothing had time to arrive'",
                h.awaitDelivered(canary, to = 1, timeoutMs = 240_000),
            )
            assertFalse(
                "baseline (liveness OFF) must strand — B must never receive it",
                h.nodes[1].messageRepo.getById(id) != null,
            )
        } finally {
            h.stop()
        }
    }
}
