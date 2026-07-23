package com.rumor.mesh.core.trust

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * O135(4) caveat, made empirical (`sybil_research.md` §1, Alvisi et al. 2013):
 * SybilLimit-style bounds assume the honest social graph is **fast-mixing**.
 * Rumor's realistic graph is a **sparse, geographically-clustered mesh** (a
 * neighbourhood / county / building post-collapse) — closer to a grid than to a
 * small-world graph — and there the hops frontier reaches only a *local*
 * fraction of honest nodes. The cost lands on legitimately-far honest nodes
 * (the "isolated newcomer at the geographic edge"): they are false-excluded, not
 * a security failure but a reachability one.
 *
 * This test quantifies the gap: same node count, same hop budget, grid vs
 * small-world (grid + a few random long-range edges = the classic
 * Watts–Strogatz shortcut). It is a characterization, not an invariant — the
 * assertions only pin the *direction and rough magnitude* so the caveat can't
 * silently regress into an over-claim.
 */
class TrustGraphTopologyTest {

    /** N×N grid: each node vouches for its 4-neighbours (undirected → both ways). */
    private fun grid(n: Int): Map<String, Set<String>> {
        fun id(r: Int, c: Int) = "n${r}_${c}"
        val v = HashMap<String, MutableSet<String>>()
        fun link(a: String, b: String) { v.getOrPut(a) { mutableSetOf() }.add(b); v.getOrPut(b) { mutableSetOf() }.add(a) }
        for (r in 0 until n) for (c in 0 until n) {
            if (c + 1 < n) link(id(r, c), id(r, c + 1))
            if (r + 1 < n) link(id(r, c), id(r + 1, c))
        }
        return v
    }

    /** The grid plus [shortcuts] random long-range edges — the small-world lift. */
    private fun smallWorld(n: Int, shortcuts: Int, rng: Random): Map<String, Set<String>> {
        val v = grid(n).mapValues { it.value.toMutableSet() }.toMutableMap()
        val nodes = v.keys.toList()
        repeat(shortcuts) {
            val a = nodes[rng.nextInt(nodes.size)]
            val b = nodes[rng.nextInt(nodes.size)]
            if (a != b) { v.getOrPut(a) { mutableSetOf() }.add(b); v.getOrPut(b) { mutableSetOf() }.add(a) }
        }
        return v
    }

    private fun coverage(vouches: Map<String, Set<String>>, seed: String, hops: Int, total: Int): Double =
        TrustGraph.frontier(seed, vouches, hops = hops).size.toDouble() / (total - 1)

    @Test
    fun `sparse grid reaches only a local fraction, small-world shortcuts fix it`() {
        val n = 15                // 225 nodes
        val total = n * n
        val seed = "n7_7"         // centre
        val rng = Random(42)

        val gridCov = coverage(grid(n), seed, hops = 3, total = total)
        val swCov = coverage(smallWorld(n, shortcuts = total, rng), seed, hops = 3, total = total)

        // On the grid at 3 hops the frontier is a diamond of radius 3 — a small,
        // strictly local neighbourhood (~a few % of a 225-node mesh). This IS
        // the caveat: far honest nodes are unreachable within the hop budget.
        assertTrue("grid coverage should be strictly local, was $gridCov", gridCov < 0.15)

        // A handful of long-range shortcuts (fast-mixing lift) dramatically
        // widens reach at the SAME hop budget — the difference between Rumor's
        // realistic sparse mesh and the small-world graphs the bounds assume.
        assertTrue("small-world coverage should dwarf the grid, was $swCov vs $gridCov", swCov > gridCov * 2)
    }

    @Test
    fun `raising hops widens grid reach but the security cost is admitting deeper strangers`() {
        val n = 15
        val total = n * n
        val seed = "n7_7"
        val cov2 = coverage(grid(n), seed, hops = 2, total = total)
        val cov5 = coverage(grid(n), seed, hops = 5, total = total)
        // More hops = more reach on a sparse graph...
        assertTrue("more hops must widen reach ($cov2 -> $cov5)", cov5 > cov2)
        // ...but this is the tradeoff to document, not a free win: every extra
        // hop also admits a wider ring of friends-of-friends-of-… whose vouch is
        // further from the seed's direct judgement (more attack surface per
        // O135's per-attack-edge framing). The knob is the user's (the hops
        // slider); the test just pins that the tradeoff is monotonic.
        assertTrue("grid at 5 hops still under-covers a large mesh", cov5 < 0.9)
    }
}
