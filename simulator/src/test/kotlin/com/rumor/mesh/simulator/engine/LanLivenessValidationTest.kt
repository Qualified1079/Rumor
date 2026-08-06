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
 */
class LanLivenessValidationTest {

    private val EDGES = listOf(0 to 2, 2 to 3, 3 to 4, 4 to 1, 2 to 5, 5 to 4)

    /** Build the diamond, kill m2 before any exchange, lay the single stale crumb chain, send A→B. */
    private suspend fun startDiamond(liveness: Boolean, seed: Long): Pair<LanMeshHarness, String> {
        val h = LanMeshHarness(n = 6, seed = seed)
        h.nodes.forEach { it.gossipEngine.livenessRouting = liveness }
        h.start(edges = EDGES)
        h.setOnline(3, up = false)  // m2 dead from the start → never ONLINE in m1's tracker
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
                h.awaitDelivered(id, to = 1, timeoutMs = 120_000),
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
            delay(25_000) // ample gossip rounds; the strand is structural, not slow
            assertFalse(
                "baseline (liveness OFF) must strand — B must never receive it",
                h.nodes[1].messageRepo.getById(id) != null,
            )
        } finally {
            h.stop()
        }
    }
}
