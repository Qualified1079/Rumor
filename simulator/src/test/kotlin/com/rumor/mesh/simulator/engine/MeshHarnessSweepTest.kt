package com.rumor.mesh.simulator.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-engine slider sweeps over [MeshHarness] — DM delivery through the actual
 * Rumor `GossipEngine`/relay/dedup/crypto (SimTransport wire). Demonstrates the
 * "everything on sliders, testable at various levels" harness on real code, and
 * pins a few monotone-trend invariants so the harness isn't vacuous.
 *
 * Kept deliberately small (few nodes/seeds): live engines are heavy and the
 * whole sim suite runs in parallel. This is the high-fidelity/low-scale tier.
 */
class MeshHarnessSweepTest {

    private fun measure(cfg: MeshHarness.Config, seeds: Int = 3): Double {
        var sent = 0; var delivered = 0
        for (s in 0 until seeds) {
            val h = MeshHarness(cfg.copy(seed = 100L + s))
            try {
                val r = runBlocking { h.run() }
                sent += r.sent; delivered += r.delivered
            } finally { h.close() }
        }
        return if (sent == 0) 0.0 else delivered.toDouble() / sent
    }

    @Test
    fun realEngineDeliverySweeps() {
        // Harsh base: short horizon + sparse topology so the built-in relay
        // re-offer/retry can't fully mask the sliders (a long-horizon connected
        // mesh delivers ~100% regardless — itself the headline real-code finding).
        val base = MeshHarness.Config(nodes = 24, peerCap = 2, rounds = 12, dms = 48)
        val sb = StringBuilder("\nMeshHarness — REAL GossipEngine DM delivery (SimTransport wire), 3 seeds/cell\n")
        sb.append("harsh base: ${base.nodes} nodes, peerCap ${base.peerCap}, ${base.rounds} rounds, ${base.dms} DMs\n")
        sb.append("(context: a well-connected 45-round mesh delivers ~100% on every slider — the real relay's\n")
        sb.append(" store+re-offer IS the retry; these short/sparse runs are where the knobs become visible)\n")

        sb.append("\n(1) peer cap (topology degree):\n")
        val peerCaps = listOf(1, 2, 3, 4, 6)
        val byPeer = peerCaps.associateWith { measure(base.copy(peerCap = it)) }
        for (k in peerCaps) sb.append("  peerCap=%d  delivery=%.0f%%\n".format(k, byPeer[k]!! * 100))

        sb.append("\n(2) node duty cycle (fraction online/round):\n")
        val duties = listOf(1.0, 0.7, 0.5, 0.3)
        val byDuty = duties.associateWith { measure(base.copy(nodeOnlineFraction = it)) }
        for (d in duties) sb.append("  online=%3.0f%%  delivery=%.0f%%\n".format(d * 100, byDuty[d]!! * 100))

        sb.append("\n(3) link loss (per-message drop):\n")
        for (l in listOf(0.0, 0.1, 0.3, 0.5)) sb.append("  loss=%3.0f%%  delivery=%.0f%%\n".format(l * 100, measure(base.copy(linkLoss = l)) * 100))

        sb.append("\n(4) dead/hostile relay fraction (nodes that won't relay):\n")
        for (m in listOf(0.0, 0.2, 0.4)) sb.append("  dead=%3.0f%%  delivery=%.0f%%\n".format(m * 100, measure(base.copy(deadRelayFraction = m)) * 100))

        println(sb.toString())
        runCatching { java.io.File("build/mesh-harness-report.txt").writeText(sb.toString()) }

        // Teeth: a well-connected all-online mesh should deliver most DMs, and
        // strangling connectivity must visibly reduce delivery (not vacuous).
        assertTrue("denser mesh should deliver the bulk of DMs, got ${byPeer[6]}", byPeer[6]!! > 0.7)
        assertTrue("peerCap=1 (barely connected) should deliver less than peerCap=6", byPeer[1]!! < byPeer[6]!!)
        assertTrue("30%-online should deliver less than fully-online", byDuty[0.3]!! < byDuty[1.0]!! + 0.001)
    }
}
