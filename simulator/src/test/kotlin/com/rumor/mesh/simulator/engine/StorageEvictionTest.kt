package com.rumor.mesh.simulator.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * O23 experiment (abstract model): under a bounded per-node message store, does
 * **delivery-aware eviction** (drop DMs already delivered to their recipient,
 * keep undelivered ones) beat naive **FIFO** (drop oldest)? O23/O55 make storage
 * eviction load-bearing: months of uptime without OOM means relays must shed,
 * and *what* they shed determines whether scarce-capacity meshes still deliver.
 *
 * Model: [n] nodes meet (random mixing); [dms] DMs (random sender→recipient) are
 * originated over the first half. Every node relays/stores what it meets, capped
 * at [cap] messages; on overflow it evicts per policy. A node never evicts its
 * OWN outbound DM (outbox is separate). Delivery = the recipient acquires its DM.
 */
class StorageEvictionTest {

    private companion object {
        const val N = 30
        const val HORIZON = 100
        const val DMS = 90        // high volume relative to cap → real eviction pressure
        const val SEEDS = 120
        const val DEGREE = 1.3    // sparse → slow delivery, so DMs must survive in stores
        const val DUTY = 0.6      // nodes intermittently offline → delivery takes many rounds
    }

    private enum class Policy { FIFO, DELIVERY_AWARE }

    private class Msg(val id: Int, val sender: Int, val recipient: Int)

    private fun runOnce(cap: Int, policy: Policy, seed: Long): Double {
        val rng = Random(seed)
        val msgs = (0 until DMS).map {
            val s = rng.nextInt(N); var r = rng.nextInt(N); while (r == s) r = rng.nextInt(N)
            Msg(it, s, r)
        }
        val originRound = IntArray(DMS) { rng.nextInt(HORIZON / 2) }
        val delivered = BooleanArray(DMS)
        // Each node's store: msgId -> acquiredRound.
        val store = Array(N) { HashMap<Int, Int>() }

        fun deliverCheck(node: Int, m: Int) { if (msgs[m].recipient == node && !delivered[m]) delivered[m] = true }

        fun add(node: Int, m: Int, t: Int) {
            if (store[node].containsKey(m)) return
            store[node][m] = t
            deliverCheck(node, m)
            if (store[node].size <= cap) return
            // Evict one (never the node's own outbound DM).
            val evictable = store[node].keys.filter { msgs[it].sender != node }
            if (evictable.isEmpty()) return
            val victim = when (policy) {
                Policy.FIFO -> evictable.minByOrNull { store[node][it]!! }!!
                Policy.DELIVERY_AWARE -> {
                    val doneFirst = evictable.filter { delivered[it] }
                    (doneFirst.ifEmpty { evictable }).minByOrNull { store[node][it]!! }!!
                }
            }
            store[node].remove(victim)
        }

        val mix = MeetingModelHelper.randomMix(N, DEGREE)
        for (t in 0 until HORIZON) {
            // Originate DMs due this round into their sender's store.
            for (m in 0 until DMS) if (originRound[m] == t) add(msgs[m].sender, m, t)
            // Duty cycle: only online nodes exchange this round.
            val online = BooleanArray(N) { rng.nextDouble() < DUTY }
            for ((a, b) in mix.meetings(t, rng)) {
                if (!online[a] || !online[b]) continue
                val aHas = store[a].keys.toList(); val bHas = store[b].keys.toList()
                for (m in aHas) add(b, m, t)
                for (m in bHas) add(a, m, t)
            }
        }
        return delivered.count { it }.toDouble() / DMS
    }

    private fun measure(cap: Int, policy: Policy): Double {
        var sum = 0.0
        for (s in 0 until SEEDS) sum += runOnce(cap, policy, s * 6151L + 7)
        return sum / SEEDS
    }

    @Test
    fun deliveryAwareEvictionBeatsFifoUnderPressure() {
        val sb = StringBuilder("\nO23 experiment — bounded store: delivery-aware eviction vs FIFO\n")
        sb.append("N=$N, $DMS DMs, horizon=$HORIZON, $SEEDS seeds. cell = delivery%\n\n")
        sb.append("cap".padEnd(10)).append("FIFO".padEnd(12)).append("delivery-aware\n")
        val caps = listOf(3, 5, 8, 15, 1000)
        val res = HashMap<Pair<Int, Policy>, Double>()
        for (c in caps) {
            for (p in Policy.values()) res[c to p] = measure(c, p)
            sb.append((if (c >= 1000) "∞" else "$c").padEnd(10))
                .append("%.0f%%".format(res[c to Policy.FIFO]!! * 100).padEnd(12))
                .append("%.0f%%".format(res[c to Policy.DELIVERY_AWARE]!! * 100)).append('\n')
        }
        sb.append("\nUnder tight caps, dropping already-delivered DMs first keeps undelivered ones\n")
        sb.append("alive longer → higher delivery for the same storage budget. Converges at large cap.\n")
        println(sb.toString())
        runCatching { java.io.File("build/o23-eviction-report.txt").writeText(sb.toString()) }

        // Teeth: at a tight cap, delivery-aware must beat FIFO; at ∞ they match.
        val tightCap = 5
        assertTrue("delivery-aware should beat FIFO at cap=$tightCap (${res[tightCap to Policy.DELIVERY_AWARE]} vs ${res[tightCap to Policy.FIFO]})",
            res[tightCap to Policy.DELIVERY_AWARE]!! > res[tightCap to Policy.FIFO]!!)
        assertTrue("policies should converge at unbounded cap",
            kotlin.math.abs(res[1000 to Policy.FIFO]!! - res[1000 to Policy.DELIVERY_AWARE]!!) < 0.02)
        // Not vacuous: a tight cap must hurt delivery vs unbounded (FIFO).
        assertTrue("tight cap should reduce FIFO delivery vs unbounded",
            res[tightCap to Policy.FIFO]!! < res[1000 to Policy.FIFO]!!)
    }
}

/** Shared tiny random-mixing meeting model (kept separate from the O193 file's private one). */
private object MeetingModelHelper {
    fun randomMix(n: Int, degree: Double) = Model(n, (degree / (n - 1)).coerceIn(0.0, 1.0))
    class Model(val n: Int, val p: Double) {
        fun meetings(@Suppress("UNUSED_PARAMETER") round: Int, rng: Random): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            for (a in 0 until n) for (b in a + 1 until n) if (rng.nextDouble() < p) out.add(a to b)
            return out
        }
    }
}
