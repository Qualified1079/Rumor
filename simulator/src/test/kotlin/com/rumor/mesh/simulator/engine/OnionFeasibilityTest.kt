package com.rumor.mesh.simulator.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * O197 feasibility gate (abstract, pure graph): limited onion routing source-
 * routes a high-sensitivity DM through KNOWN-CONTACT relays only. That only
 * works if S and R are connected in the *contact graph* within a small hop
 * budget (onion layers are per-hop, so the chain must be short). This measures
 * what fraction of random sender→recipient pairs are reachable through contacts
 * only, as a function of average contact degree — if it's low, O197 can't serve
 * most traffic and dies cheap; if a giant short-path component forms, it's viable
 * for the fraction it covers (the rest fall back to normal gossip).
 */
class OnionFeasibilityTest {

    private companion object {
        const val N = 200
        const val PAIRS = 3000   // random (S,R) probes across the sweep
    }

    /** Build an undirected contact graph where each node seeks ~[degree] contacts. */
    private fun buildContacts(degree: Int, rng: Random): Array<MutableList<Int>> {
        val adj = Array(N) { mutableListOf<Int>() }
        val seen = HashSet<Long>()
        for (a in 0 until N) {
            var tries = 0
            while (adj[a].size < degree && tries++ < degree * 8) {
                val b = rng.nextInt(N)
                if (b == a) continue
                val key = a.toLong() * N + b
                val rkey = b.toLong() * N + a
                if (key in seen || rkey in seen) continue
                seen.add(key); adj[a].add(b); adj[b].add(a)
            }
        }
        return adj
    }

    /** Shortest contact-path hop count from s to r, or -1 if unreachable. */
    private fun hops(adj: Array<MutableList<Int>>, s: Int, r: Int): Int {
        if (s == r) return 0
        val dist = IntArray(N) { -1 }; dist[s] = 0
        val q = ArrayDeque<Int>(); q.add(s)
        while (q.isNotEmpty()) {
            val x = q.removeFirst()
            for (y in adj[x]) if (dist[y] < 0) { dist[y] = dist[x] + 1; if (y == r) return dist[y]; q.add(y) }
        }
        return -1
    }

    @Test
    fun onionReachabilityVsContactDegree() {
        val sb = StringBuilder("\nO197 feasibility — contact-only reachability vs average contact degree\n")
        sb.append("N=$N, $PAIRS probes/degree. cell = % of sender→recipient pairs reachable through contacts only\n\n")
        sb.append("degree".padEnd(9)).append("≤3 hops".padEnd(12)).append("≤5 hops".padEnd(12)).append("any path".padEnd(12)).append("mean hops\n")
        val degrees = listOf(2, 3, 4, 6, 10)
        val within3 = HashMap<Int, Double>()
        for (d in degrees) {
            val rng = Random(d * 104729L + 1)
            val adj = buildContacts(d, rng)
            var r3 = 0; var r5 = 0; var any = 0; var hopSum = 0L; var connCount = 0
            repeat(PAIRS) {
                var s = rng.nextInt(N); var r = rng.nextInt(N); while (r == s) r = rng.nextInt(N)
                val h = hops(adj, s, r)
                if (h in 1..3) r3++
                if (h in 1..5) r5++
                if (h >= 1) { any++; hopSum += h; connCount++ }
            }
            within3[d] = r3.toDouble() / PAIRS
            sb.append("$d".padEnd(9))
                .append("%.0f%%".format(r3 * 100.0 / PAIRS).padEnd(12))
                .append("%.0f%%".format(r5 * 100.0 / PAIRS).padEnd(12))
                .append("%.0f%%".format(any * 100.0 / PAIRS).padEnd(12))
                .append(if (connCount > 0) "%.1f".format(hopSum.toDouble() / connCount) else "-").append('\n')
        }
        sb.append("\nReading: onion routing through contacts is only viable for pairs in the giant\n")
        sb.append("short-path component. Sparse contact graphs (degree 2–3) leave many pairs\n")
        sb.append("unreachable within a sane onion hop budget → O197 serves a minority; the rest\n")
        sb.append("fall back to normal gossip. It becomes broadly viable around degree 6+.\n")
        println(sb.toString())
        runCatching { java.io.File("build/o197-onion-report.txt").writeText(sb.toString()) }

        // Teeth: reachability within a hop budget must rise with contact degree,
        // and be genuinely limited when the graph is sparse.
        assertTrue("≤3-hop reachability should increase with degree", within3[10]!! > within3[2]!!)
        assertTrue("sparse graph (degree 2) should leave a real fraction unreachable in ≤3 hops", within3[2]!! < 0.9)
    }
}
