package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.time.HlcClock
import com.rumor.mesh.core.time.HlcTimestamp
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O95 decision input — quantifies wall-clock vs HLC ordering under realistic
 * node conditions BEFORE committing to a wire-format change (user directive
 * 2026-07-18). Deliberately a purpose-built deterministic mini-sim (seeded
 * RNG, no coroutines — the O12 flakiness class is structurally excluded):
 * ordering semantics depend on stamp/merge rules and delivery topology, not
 * on the full engine, and RbsrBandwidthScenarioTest set the precedent.
 *
 * Model per round (1 sim-second): each node composes with p=0.15 (40% of
 * those are replies to a random already-held message — the causal pairs);
 * then gossiped pairs union their stores, folding every newly-seen message's
 * HLC into the receiver's clock. Partitioned rounds only pair within the
 * partition. Metrics over each node's final store, ordered by
 * (sentAtMs, id) vs (hlc, id):
 *
 *  - causal violations: reply sorting strictly before its cause (the lie
 *    users actually notice);
 *  - pair inversions: sampled message pairs whose order disagrees with true
 *    compose time (feed-order quality; concurrent pairs have no true order
 *    a clock can recover, so this never reaches 0 for either scheme).
 */
class HlcOrderingScenarioTest {

    private data class Msg(
        val id: String,
        val trueMs: Long,
        val wallMs: Long,
        val hlc: HlcTimestamp,
        val causeId: String?,
    )

    private class Node(val id: Int, val skewMs: Long, trueNow: () -> Long) {
        val clock = HlcClock { trueNow() + skewMs }
        val store = LinkedHashMap<String, Msg>()
        fun wallNow(trueNowMs: Long) = trueNowMs + skewMs
    }

    private data class RunResult(
        val messages: Int,
        val causalPairs: Int,
        val wallCausalViolations: Int,
        val hlcCausalViolations: Int,
        val wallInversionPct: Double,
        val hlcInversionPct: Double,
        val deliveredPct: Double,
    )

    private fun runSim(
        nodes: Int,
        skews: (Random) -> List<Long>,
        partitioned: Boolean,
        rounds: Int = 600,
        seed: Long = 42,
    ): RunResult {
        val rng = Random(seed)
        var trueNow = 1_700_000_000_000L
        val skewList = skews(rng)
        val fleet = (0 until nodes).map { Node(it, skewList[it]) { trueNow } }

        repeat(rounds) { round ->
            trueNow += 1_000
            // Compose phase.
            for (n in fleet) {
                if (rng.nextDouble() >= 0.15) continue
                val cause = if (n.store.isNotEmpty() && rng.nextDouble() < 0.4)
                    n.store.values.elementAt(rng.nextInt(n.store.size)) else null
                val msg = Msg(
                    id = "m${round}n${n.id}",
                    trueMs = trueNow,
                    wallMs = n.wallNow(trueNow),
                    hlc = n.clock.tick(),
                    causeId = cause?.id,
                )
                n.store[msg.id] = msg
            }
            // Gossip phase: random disjoint pairs, partition-respecting.
            val inMiddleThird = round >= rounds / 3 && round < 2 * rounds / 3
            val split = partitioned && inMiddleThird
            val pool = fleet.shuffled(rng).toMutableList()
            while (pool.size >= 2) {
                val a = pool.removeAt(0)
                val bIdx = pool.indexOfFirst { !split || (it.id % 2 == a.id % 2) }
                if (bIdx < 0) break
                val b = pool.removeAt(bIdx)
                exchange(a, b)
            }
        }
        // Heal fully: a few unpartitioned closing rounds already ran (last third);
        // finish with a full mesh sweep so every node converges.
        for (a in fleet) for (b in fleet) if (a !== b) exchange(a, b)

        // Metrics on one converged store (all identical after the sweep).
        val store = fleet.first().store
        val all = store.values.toList()
        val wallOrder = all.sortedWith(compareBy({ it.wallMs }, { it.id }))
        val hlcOrder = all.sortedWith(compareBy({ it.hlc }, { it.id }))
        val wallRank = wallOrder.withIndex().associate { (i, m) -> m.id to i }
        val hlcRank = hlcOrder.withIndex().associate { (i, m) -> m.id to i }

        var causalPairs = 0
        var wallViol = 0
        var hlcViol = 0
        for (m in all) {
            val cause = m.causeId?.let { store[it] } ?: continue
            causalPairs++
            if (wallRank[m.id]!! < wallRank[cause.id]!!) wallViol++
            if (hlcRank[m.id]!! < hlcRank[cause.id]!!) hlcViol++
        }

        var sampled = 0
        var wallInv = 0
        var hlcInv = 0
        repeat(20_000) {
            val a = all[rng.nextInt(all.size)]
            val b = all[rng.nextInt(all.size)]
            if (a.trueMs == b.trueMs) return@repeat
            sampled++
            val (early, late) = if (a.trueMs < b.trueMs) a to b else b to a
            if (wallRank[late.id]!! < wallRank[early.id]!!) wallInv++
            if (hlcRank[late.id]!! < hlcRank[early.id]!!) hlcInv++
        }

        val delivered = fleet.sumOf { it.store.size }.toDouble() / (fleet.size * all.size)
        return RunResult(
            messages = all.size,
            causalPairs = causalPairs,
            wallCausalViolations = wallViol,
            hlcCausalViolations = hlcViol,
            wallInversionPct = 100.0 * wallInv / sampled,
            hlcInversionPct = 100.0 * hlcInv / sampled,
            deliveredPct = 100.0 * delivered,
        )
    }

    private fun exchange(a: Node, b: Node) {
        for (m in a.store.values) if (b.store.putIfAbsent(m.id, m) == null) b.clock.update(m.hlc)
        for (m in b.store.values) if (a.store.putIfAbsent(m.id, m) == null) a.clock.update(m.hlc)
    }

    // Skew profiles. 64 days = the field-observed Samsung offset.
    private val none = { _: Random -> List(64) { 0L } }
    private val jitter = { r: Random -> List(64) { r.nextLong(-120_000, 120_000) } }
    private val oneBroken = { r: Random ->
        List(64) { if (it == 1) -64L * 24 * 3600 * 1000 else r.nextLong(-120_000, 120_000) }
    }
    private val mixed = { r: Random ->
        List(64) {
            when {
                it == 1 -> -64L * 24 * 3600 * 1000
                else -> r.nextLong(-6L * 3600 * 1000, 6L * 3600 * 1000)
            }
        }
    }

    @Test
    fun `sweep - hlc never causally lies, wall clock does under skew`() {
        val configs = listOf(
            Triple("accurate clocks", none, false),
            Triple("±2min jitter", jitter, false),
            Triple("one node 64d slow", oneBroken, false),
            Triple("mixed ±6h + one 64d", mixed, false),
            Triple("one 64d + partition", oneBroken, true),
            Triple("mixed + partition", mixed, true),
        )
        println("O95 HLC sweep (600 rounds; causal viol = reply sorts before its cause)")
        println("%-24s %5s | %8s | %13s | %13s | %9s | %9s".format(
            "config", "nodes", "messages", "wall-causal", "hlc-causal", "wall-inv%", "hlc-inv%"))
        for (nodeCount in listOf(6, 24)) {
            for ((label, skewFn, part) in configs) {
                val r = runSim(nodeCount, skewFn, part)
                println("%-24s %5d | %8d | %6d/%-6d | %6d/%-6d | %9.2f | %9.2f".format(
                    label, nodeCount, r.messages,
                    r.wallCausalViolations, r.causalPairs,
                    r.hlcCausalViolations, r.causalPairs,
                    r.wallInversionPct, r.hlcInversionPct))

                // The load-bearing invariant: HLC never orders a reply before
                // its cause, under any skew/partition combination.
                assertEquals("$label/$nodeCount: HLC causal violation", 0, r.hlcCausalViolations)
                assertEquals("$label/$nodeCount: sim must converge", 100.0, r.deliveredPct, 0.001)
            }
        }
        // Wall clock DOES causally lie once a broken clock is present.
        val broken = runSim(24, oneBroken, false)
        assertTrue("broken-clock run must show wall-clock causal lies", broken.wallCausalViolations > 0)
    }

    @Test
    fun `accurate clocks - hlc feed order is no worse than wall order`() {
        val r = runSim(24, none, false)
        assertEquals(0, r.wallCausalViolations)
        assertEquals(0, r.hlcCausalViolations)
        assertTrue(
            "hlc ${r.hlcInversionPct} vs wall ${r.wallInversionPct}",
            r.hlcInversionPct <= r.wallInversionPct + 0.5,
        )
    }
}
