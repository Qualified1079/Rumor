package com.rumor.mesh.simulator.engine

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Breadcrumb-routing vs flooding — bandwidth/reliability sweep across topologies,
 * chain lengths, a mid-path relay dropout, COMPOSABLE scheme permutations, and two
 * crumb-table regimes (ideal vs dynamically-learned-and-stale). Companion to the
 * real-engine [SmartRoutingDropoutTest]; this is a fast **abstract model** so it
 * can compare routing variations that DON'T exist in `GossipEngine` yet.
 *
 * NOT trusted blind: [`diamond reproduces the real engine`] asserts the model
 * reproduces [SmartRoutingDropoutTest]'s real-`GossipEngine` numbers exactly
 * (flood delivers/touches 9, a single stale crumb strands/touches 2, crumb
 * diversity delivers/touches 5). It encodes the SAME decision `messagesForExchange`
 * makes — this is DYNAMIC next-hop routing (O59): the message carries NO path, and
 * each relay consults ITS OWN breadcrumb table (`breadcrumbs.candidatePeers` keyed
 * by the holder). The origin always floods to its neighbours (targeting is a
 * relay-only property); a relay offers a routed DM only to its own crumb
 * candidates, flooding when it holds none.
 *
 * A [Scheme] is the composable relay-side offer policy, so permutations combine:
 *  - kBest    : how many cached next-hops a relay will offer to (1 = straight line).
 *  - liveness : treat a crumb whose next-hop is not a currently-connected peer as
 *               "none" → flood. Fixes "my next hop is dead" from purely local info;
 *               does NOT fix "next hop alive but the path behind it is severed".
 *  - ack      : if no end-to-end ACK returns within a deadline, escalate THIS dm to
 *               flood. The ACK routes back over the same (fragile) crumbs, so
 *               escalation is timeout-driven, not arrival-driven.
 *  - flood    : the baseline (every relay floods; crumbs ignored).
 *
 * Crumb regimes:
 *  - ideal   : every node holds the pre-dropout shortest-path next-hops (a perfect
 *              table). Stresses the lookup-and-forward half only.
 *  - learned : crumbs are POPULATED by flooding traffic from the target and recording
 *              the reverse path (parent), then go STALE when a node dies. `floods`
 *              controls learned diversity; `relearnOnLive` models fresh post-dropout
 *              traffic re-teaching a route around the hole. This tests the dynamic
 *              end-to-end (population → staleness → recovery), not a hand-seeded ideal.
 */
class RoutingBandwidthSweepTest {

    private data class Scheme(
        val name: String,
        val flood: Boolean = false,
        val kBest: Int = 1,
        val liveness: Boolean = false,
        val ack: Boolean = false,
    )

    // The permutation matrix: baseline + the three ideas and their combinations.
    private val schemes = listOf(
        Scheme("FLOOD", flood = true),
        Scheme("NAIVE", kBest = 1),
        Scheme("DIVERSE", kBest = 3),
        Scheme("LIVE", kBest = 1, liveness = true),
        Scheme("DIV+LIVE", kBest = 3, liveness = true),
        Scheme("NAIVE+ACK", kBest = 1, ack = true),
        Scheme("LIVE+ACK", kBest = 1, liveness = true, ack = true),
        Scheme("ALL", kBest = 3, liveness = true, ack = true),
    )

    private data class Res(val delivered: Boolean, val touched: Int, val deliverRound: Int)
    private class Crumbs(val fwd: Array<List<Int>>, val back: Array<List<Int>>)

    private class Topo(
        val n: Int,
        val preAdj: Array<MutableSet<Int>>,
        val dead: Set<Int>,
        val origin: Int,
        val dest: Int,
    ) {
        val adj: Array<Set<Int>> = Array(n) { h ->
            if (h in dead) emptySet() else preAdj[h].filter { it !in dead }.toSet()
        }
    }

    private val ACK_DEADLINE = 6

    // ---- crumb tables -----------------------------------------------------

    private fun bfs(from: Int, adj: Array<out Set<Int>>): IntArray {
        val d = IntArray(adj.size) { INF }; d[from] = 0
        val q = ArrayDeque<Int>(); q.add(from)
        while (q.isNotEmpty()) { val x = q.removeFirst(); for (y in adj[x]) if (d[y] == INF) { d[y] = d[x] + 1; q.add(y) } }
        return d
    }

    /** Perfect pre-dropout shortest-path next-hops (can point at the soon-dead node). */
    private fun idealCrumbs(t: Topo): Crumbs {
        fun rank(target: Int): Array<List<Int>> {
            val dist = bfs(target, t.preAdj)
            return Array(t.n) { h -> t.preAdj[h].filter { dist[it] < INF }.sortedWith(compareBy({ dist[it] }, { it })) }
        }
        return Crumbs(fwd = rank(t.dest), back = rank(t.origin))
    }

    /** One randomized flood from [source]; each node records the neighbour it was
     *  first reached from (its reverse-path parent), appended if new, capped at [capK]. */
    private fun floodLearn(adj: Array<out Set<Int>>, source: Int, rnd: Random, capK: Int, into: Array<MutableList<Int>>) {
        val reached = BooleanArray(adj.size); reached[source] = true
        var frontier = listOf(source)
        while (frontier.isNotEmpty()) {
            val next = ArrayList<Int>()
            for (x in frontier) for (y in adj[x].shuffled(rnd)) if (!reached[y]) {
                reached[y] = true
                if (x !in into[y] && into[y].size < capK) into[y].add(x)
                next.add(y)
            }
            frontier = next
        }
    }

    /** Crumbs learned from traffic: [floods] pre-dropout floods from each endpoint
     *  (stale w.r.t. the dropout); optionally one post-dropout flood on the live
     *  graph, recorded FIRST so a freshly-relearned route is the preferred next-hop. */
    private fun learnedCrumbs(t: Topo, floods: Int, relearnOnLive: Boolean, rnd: Random, capK: Int = 4): Crumbs {
        fun learn(target: Int): Array<List<Int>> {
            val into = Array(t.n) { mutableListOf<Int>() }
            if (relearnOnLive) floodLearn(t.adj, target, rnd, capK, into)   // fresh, preferred
            repeat(floods) { floodLearn(t.preAdj, target, rnd, capK, into) } // stale background
            return Array(t.n) { into[it] }
        }
        return Crumbs(fwd = learn(t.dest), back = learn(t.origin))
    }

    // ---- the offer decision + simulator ----------------------------------

    private fun offersDM(t: Topo, c: Crumbs, s: Scheme, h: Int, p: Int, escalated: Boolean): Boolean {
        if (h == t.origin) return true
        if (s.flood) return true
        if (s.ack && escalated) return true
        var cand = c.fwd[h].take(s.kBest)
        if (s.liveness) cand = cand.filter { it in t.adj[h] }
        return cand.isEmpty() || p in cand
    }

    private fun offersAck(t: Topo, c: Crumbs, s: Scheme, h: Int, p: Int): Boolean {
        if (h == t.dest) return true
        var cand = c.back[h].take(s.kBest)
        if (s.liveness) cand = cand.filter { it in t.adj[h] }
        return cand.isEmpty() || p in cand
    }

    private fun simulate(t: Topo, c: Crumbs, s: Scheme): Res {
        val hasDM = BooleanArray(t.n); hasDM[t.origin] = true
        val hasAck = BooleanArray(t.n)
        var escalated = false
        var deliverRound = -1
        val maxRounds = 2 * t.n + ACK_DEADLINE + 5
        for (round in 1..maxRounds) {
            val dmSnap = hasDM.copyOf()
            for (p in 0 until t.n) {
                if (dmSnap[p] || p in t.dead) continue
                for (h in t.adj[p]) {
                    if (!dmSnap[h] || h == t.dest) continue
                    if (offersDM(t, c, s, h, p, escalated)) { hasDM[p] = true; break }
                }
            }
            if (hasDM[t.dest] && deliverRound < 0) { deliverRound = round; hasAck[t.dest] = true }
            if (s.ack) {
                if (deliverRound in 0 until round && !hasAck[t.origin]) {
                    val ackSnap = hasAck.copyOf()
                    for (p in 0 until t.n) {
                        if (ackSnap[p] || p in t.dead) continue
                        for (h in t.adj[p]) {
                            if (!ackSnap[h] || h == t.origin) continue
                            if (offersAck(t, c, s, h, p)) { hasAck[p] = true; break }
                        }
                    }
                }
                if (!escalated && !hasAck[t.origin] && round >= ACK_DEADLINE) escalated = true
            }
        }
        return Res(hasDM[t.dest], hasDM.count { it }, deliverRound)
    }

    // ---- topology builders ------------------------------------------------

    private fun edge(a: Array<MutableSet<Int>>, x: Int, y: Int) { a[x].add(y); a[y].add(x) }

    private fun diamond(): Topo {
        val a = Array(10) { mutableSetOf<Int>() }
        edge(a, 0, 2); edge(a, 2, 3); edge(a, 3, 4); edge(a, 4, 1)
        edge(a, 2, 5); edge(a, 5, 4)
        edge(a, 2, 6); edge(a, 2, 7); edge(a, 4, 8); edge(a, 4, 9)
        return Topo(10, a, setOf(3), origin = 0, dest = 1)
    }

    private fun chainWithBypass(hops: Int, leaves: Int): Topo {
        var next = hops + 1
        val interior = (1 until hops)
        val total = hops + 2 + leaves * interior.count()
        val a = Array(total) { mutableSetOf<Int>() }
        for (i in 0 until hops) edge(a, i, i + 1)
        val broken = hops / 2
        val bypass = next++
        edge(a, broken - 1, bypass); edge(a, bypass, broken + 1)
        for (r in interior) repeat(leaves) { edge(a, r, next++) }
        return Topo(total, a, setOf(broken), origin = 0, dest = hops)
    }

    private fun randomGeo(seed: Long, n: Int = 40, radius: Double = 0.30): Topo? {
        val rnd = Random(seed)
        val xs = DoubleArray(n) { rnd.nextDouble() }; val ys = DoubleArray(n) { rnd.nextDouble() }
        val a = Array(n) { mutableSetOf<Int>() }
        val r2 = radius * radius
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = xs[i] - xs[j]; val dy = ys[i] - ys[j]
            if (dx * dx + dy * dy <= r2) edge(a, i, j)
        }
        repeat(20) {
            val o = rnd.nextInt(n); val d = rnd.nextInt(n)
            if (o == d) return@repeat
            val path = shortestPath(a, o, d) ?: return@repeat
            if (path.size < 3) return@repeat
            val interior = path.subList(1, path.size - 1)
            return Topo(n, a, setOf(interior[rnd.nextInt(interior.size)]), origin = o, dest = d)
        }
        return null
    }

    private fun shortestPath(a: Array<MutableSet<Int>>, from: Int, to: Int): List<Int>? {
        val prev = IntArray(a.size) { -2 }; prev[from] = -1
        val q = ArrayDeque<Int>(); q.add(from)
        while (q.isNotEmpty()) { val x = q.removeFirst(); if (x == to) break; for (y in a[x]) if (prev[y] == -2) { prev[y] = x; q.add(y) } }
        if (prev[to] == -2) return null
        val path = ArrayList<Int>(); var cur = to
        while (cur != -1) { path.add(cur); cur = prev[cur] }
        return path.reversed()
    }

    // ---- tests ------------------------------------------------------------

    @Test
    fun `diamond reproduces the real engine`() {
        val t = diamond(); val c = idealCrumbs(t)
        val flood = simulate(t, c, Scheme("F", flood = true))
        val naive = simulate(t, c, Scheme("N", kBest = 1))
        val diverse = simulate(t, c, Scheme("D", kBest = 3))
        println("GATE diamond flood=$flood naive=$naive diverse=$diverse")
        assertTrue(flood.delivered); assertEquals(9, flood.touched)
        assertFalse(naive.delivered); assertEquals(2, naive.touched)
        assertTrue(diverse.delivered); assertEquals(5, diverse.touched)
    }

    @Test
    fun `chain sweep — bandwidth win grows with bystanders, naive stays broken`() {
        println("== chain-with-bypass (mid relay killed), ideal crumbs ==")
        println("hops lv | " + schemes.joinToString("") { it.name.padEnd(11) })
        for (hops in listOf(6, 12, 24)) for (leaves in listOf(1, 3)) {
            val t = chainWithBypass(hops, leaves); val c = idealCrumbs(t)
            val r = schemes.associateWith { simulate(t, c, it) }
            println("%4d %2d | ".format(hops, leaves) + schemes.joinToString("") {
                r[it]!!.let { x -> "${if (x.delivered) "✓" else "✗"}${x.touched}/${t.n}".padEnd(11) }
            })
            assertFalse("naive strands (hops=$hops)", r[schemes[1]]!!.delivered)
            assertTrue("diverse < flood", r[schemes[2]]!!.touched < r[schemes[0]]!!.touched)
        }
    }

    @Test
    fun `scheme-permutation sweep on random meshes (ideal crumbs)`() {
        val agg = sweep(learned = false)
        printTable("random mesh, n=40, ideal crumbs", agg)
        val d = agg.mapValues { it.value.deliveredPct }
        // Reliability: flood is the ceiling; naive the floor. Local heuristics (live,
        // diverse) sit well above naive but below flood (single-hop view can't see
        // downstream cuts). Any scheme carrying ACK matches flood (end-to-end signal).
        assertTrue("flood ceiling", d["FLOOD"]!! >= d["DIVERSE"]!! && d["FLOOD"]!! >= d["LIVE"]!!)
        assertTrue("diverse >> naive", d["DIVERSE"]!! > d["NAIVE"]!! + 10)
        assertTrue("liveness >> naive", d["LIVE"]!! > d["NAIVE"]!! + 10)
        assertTrue("local heuristics below flood", d["DIVERSE"]!! < d["FLOOD"]!! && d["LIVE"]!! < d["FLOOD"]!!)
        assertEquals("ACK matches flood delivery", d["FLOOD"]!!, d["NAIVE+ACK"]!!, 0.4)
        assertEquals("ALL matches flood delivery", d["FLOOD"]!!, d["ALL"]!!, 0.4)
        // Bandwidth: every delivering routing scheme is cheaper than flood — including
        // the ACK schemes, which only pay flood cost on the rare routing failure.
        val b = agg.mapValues { it.value.meanTouched }
        for (s in listOf("DIVERSE", "LIVE", "DIV+LIVE", "LIVE+ACK", "ALL")) {
            assertTrue("$s cheaper than flood (${b[s]} < ${b["FLOOD"]})", b[s]!! < b["FLOOD"]!!)
        }
    }

    @Test
    fun `crumb population and decay sweep (learned, staling, relearn)`() {
        println("== learned crumbs: population (floods) + staleness + relearn ==")
        for (floods in listOf(1, 3)) for (relearn in listOf(false, true)) {
            val agg = sweep(learned = true, floods = floods, relearn = relearn)
            printTable("learned: floods=$floods relearnOnLive=$relearn", agg)
        }
        // Two learned-regime claims, asserted on the extremes:
        // (a) with only 1 stale flood (single learned parent, no relearn) NAIVE is
        //     badly fragile; more floods OR relearning lifts delivery.
        val stale1 = sweep(learned = true, floods = 1, relearn = false)
        val fresh = sweep(learned = true, floods = 1, relearn = true)
        assertTrue(
            "relearning on the live graph repairs delivery vs stale crumbs " +
                "(${fresh["NAIVE"]!!.deliveredPct} > ${stale1["NAIVE"]!!.deliveredPct})",
            fresh["NAIVE"]!!.deliveredPct > stale1["NAIVE"]!!.deliveredPct,
        )
        // (b) even with stale learned crumbs, ACK escalation still reaches flood-level
        //     delivery (its safety net doesn't depend on the crumbs being fresh).
        assertTrue(
            "ACK backstop survives stale crumbs (${stale1["LIVE+ACK"]!!.deliveredPct} ≥ ${stale1["DIVERSE"]!!.deliveredPct})",
            stale1["LIVE+ACK"]!!.deliveredPct >= stale1["DIVERSE"]!!.deliveredPct,
        )
    }

    // ---- sweep harness ----------------------------------------------------

    private class Agg { var delivered = 0; var touchSum = 0L; var touchCnt = 0; var roundSum = 0L; var trials = 0
        val deliveredPct get() = 100.0 * delivered / trials
        val meanTouched get() = if (touchCnt > 0) touchSum.toDouble() / touchCnt else 0.0
        val meanRounds get() = if (touchCnt > 0) roundSum.toDouble() / touchCnt else 0.0
    }

    private fun sweep(learned: Boolean, floods: Int = 1, relearn: Boolean = false, trials: Int = 300): Map<String, Agg> {
        val agg = schemes.associate { it.name to Agg() }
        for (seed in 1..trials) {
            val t = randomGeo(seed.toLong()) ?: continue
            val c = if (learned) learnedCrumbs(t, floods, relearn, Random(seed * 7919L)) else idealCrumbs(t)
            for (s in schemes) {
                val a = agg[s.name]!!; a.trials++
                val r = simulate(t, c, s)
                if (r.delivered) { a.delivered++; a.touchSum += r.touched; a.touchCnt++; a.roundSum += r.deliverRound }
            }
        }
        return agg
    }

    private fun printTable(title: String, agg: Map<String, Agg>) {
        println("-- $title --")
        println("scheme        delivery%   mean-touched   mean-rounds")
        for (s in schemes) agg[s.name]!!.let {
            println("%-12s  %7.1f   %10.1f   %10.1f".format(s.name, it.deliveredPct, it.meanTouched, it.meanRounds))
        }
    }

    companion object { const val INF = Int.MAX_VALUE }
}
