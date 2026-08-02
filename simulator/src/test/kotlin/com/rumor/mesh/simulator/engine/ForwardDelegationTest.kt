package com.rumor.mesh.simulator.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * O102 experiment (abstract): broadcast flooding wastes airtime when every node
 * that receives a message rebroadcasts it. "Neighbour-aware forward delegation"
 * suppresses redundant rebroadcasts (ideal: one copy per airtime). This measures
 * the coverage/airtime trade of the simplest realisable version — probabilistic
 * forwarding (each receiver rebroadcasts with prob p) — to find where airtime can
 * be cut without losing coverage, and where coverage collapses.
 */
class ForwardDelegationTest {

    private companion object {
        const val N = 400
        const val DEGREE = 8      // dense-ish mesh (rebroadcast redundancy exists to cut)
        const val SEEDS = 200
    }

    private fun buildGraph(rng: Random): Array<IntArray> {
        val adj = Array(N) { mutableListOf<Int>() }
        val seen = HashSet<Long>()
        for (a in 0 until N) {
            var tries = 0
            while (adj[a].size < DEGREE && tries++ < DEGREE * 8) {
                val b = rng.nextInt(N); if (b == a) continue
                val k = minOf(a, b).toLong() * N + maxOf(a, b)
                if (seen.add(k)) { adj[a].add(b); adj[b].add(a) }
            }
        }
        return Array(N) { adj[it].toIntArray() }
    }

    /** Returns coverage fraction and rebroadcast count for forward-probability [p]. */
    private fun flood(adj: Array<IntArray>, p: Double, seed: Long): Pair<Double, Int> {
        val rng = Random(seed)
        val received = BooleanArray(N)
        val src = rng.nextInt(N)
        received[src] = true
        var transmissions = 0
        val queue = ArrayDeque<Int>()
        queue.add(src)  // source always transmits once
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            transmissions++
            for (y in adj[x]) {
                if (received[y]) continue
                received[y] = true
                // Newly-covered node forwards with probability p (source-forward = always).
                if (rng.nextDouble() < p) queue.add(y)
            }
        }
        return received.count { it }.toDouble() / N to transmissions
    }

    @Test
    fun probabilisticForwardingCutsAirtime() {
        val sb = StringBuilder("\nO102 experiment — forward delegation: coverage vs airtime (rebroadcasts)\n")
        sb.append("N=$N, degree=$DEGREE, $SEEDS seeds. cell = coverage% | rebroadcasts (÷N)\n\n")
        sb.append("forward p".padEnd(12)).append("coverage".padEnd(14)).append("airtime (tx/N)\n")
        val ps = listOf(1.0, 0.7, 0.5, 0.3, 0.15)
        val res = HashMap<Double, Pair<Double, Double>>()
        for (p in ps) {
            var cov = 0.0; var tx = 0.0
            for (s in 0 until SEEDS) {
                val adj = buildGraph(Random(s * 7907L + p.hashCode()))
                val (c, t) = flood(adj, p, s * 131L + 3)
                cov += c; tx += t.toDouble() / N
            }
            res[p] = cov / SEEDS to tx / SEEDS
            sb.append("%.2f".format(p).padEnd(12))
                .append("%.0f%%".format(res[p]!!.first * 100).padEnd(14))
                .append("%.2f".format(res[p]!!.second)).append('\n')
        }
        sb.append("\nAt full flood (p=1) every covered node rebroadcasts — max airtime. In a dense\n")
        sb.append("mesh, cutting p keeps ~full coverage while shedding airtime, down to a threshold\n")
        sb.append("where coverage collapses (percolation). The knee is the practical operating point;\n")
        sb.append("a neighbour-aware scheme (suppress if you've heard it from k neighbours) beats a\n")
        sb.append("blind coin-flip by adapting p to local density — same coverage, less airtime.\n")
        println(sb.toString())
        runCatching { java.io.File("build/o102-forward-report.txt").writeText(sb.toString()) }

        // Teeth: reducing p cuts airtime; dense mesh keeps high coverage at moderate p;
        // very low p collapses coverage (proves the measurement sees the percolation threshold).
        assertTrue("p=0.5 should cut airtime vs p=1.0", res[0.5]!!.second < res[1.0]!!.second)
        assertTrue("p=0.5 should keep high coverage in a dense mesh", res[0.5]!!.first > 0.9)
        assertTrue("very low p should visibly lose coverage", res[0.15]!!.first < res[1.0]!!.first - 0.05)
    }
}
