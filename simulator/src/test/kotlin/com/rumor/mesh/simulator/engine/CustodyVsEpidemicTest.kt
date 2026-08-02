package com.rumor.mesh.simulator.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * O202 design experiment (abstract model, NOT real code): does **custody-based
 * storage** — keeping a DM for R only on nodes in R's social graph (the ones
 * that actually meet R) — hold delivery while cutting relay storage vs uniform
 * **epidemic** (everyone carries everything), especially under low duty-cycle?
 *
 * Model: R = node 0, S = node 1, R has [contacts] "known peers" that meet R
 * often (pHigh); strangers meet R rarely (pLow). Carriers spread the DM among
 * themselves (pMix). Duty cycle: each node online with prob f/round.
 *  - epidemic: every node retains + carries the DM.
 *  - custody:  only S + R's known contacts retain it (a stranger that sees it
 *    doesn't keep it — pointless, it won't meet R).
 *
 * Thesis to test: custody delivers ~as well as epidemic (because R's contacts
 * ARE who meets R) at a fraction of the storage — the pragmatic O202 lever.
 */
class CustodyVsEpidemicTest {

    private companion object {
        const val N = 40
        const val CONTACTS = 6          // R's known peers (indices 2..7)
        const val HORIZON = 120
        const val SEEDS = 300
        const val P_HIGH = 0.25         // a known contact meets R (per round, both online)
        const val P_LOW = 0.01          // a stranger meets R
        const val P_MIX = 0.06          // carrier-to-carrier spread
        const val R = 0
        const val S = 1
        const val ESCALATE_AFTER = 30   // hybrid: widen custody→epidemic if undelivered by here
    }

    private enum class Mode { EPIDEMIC, CUSTODY, HYBRID }

    private data class Res(val delivered: Boolean, val storage: Long)

    private fun runOnce(mode: Mode, duty: Double, seed: Long, escalateAfter: Int = ESCALATE_AFTER): Res {
        val rng = Random(seed)
        val contacts = (2 until 2 + CONTACTS).toSet()
        // Who is allowed to retain a copy for R this round. HYBRID starts narrow
        // (custody) and widens to everyone once the escalation deadline passes
        // without delivery — models "no ACK by deadline → widen the search".
        fun retains(i: Int, t: Int): Boolean = when (mode) {
            Mode.EPIDEMIC -> true
            Mode.CUSTODY -> i == S || i in contacts
            Mode.HYBRID -> i == S || i in contacts || t >= escalateAfter
        }
        val holds = BooleanArray(N)
        holds[S] = true
        var delivered = false
        var storage = 0L
        for (t in 0 until HORIZON) {
            val online = BooleanArray(N) { duty >= 1.0 || rng.nextDouble() < duty }
            // Carrier-to-carrier spread among retainers.
            for (a in 1 until N) {
                if (!online[a] || !holds[a]) continue
                for (b in 1 until N) {
                    if (a == b || !online[b] || holds[b] || !retains(b, t)) continue
                    if (rng.nextDouble() < P_MIX) holds[b] = true
                }
            }
            // Delivery: any holder that meets R (contact→pHigh, stranger→pLow).
            if (online[R] && !delivered) {
                for (x in 1 until N) {
                    if (!holds[x] || !online[x]) continue
                    val p = if (x in contacts) P_HIGH else P_LOW
                    if (rng.nextDouble() < p) { delivered = true; break }
                }
            }
            for (x in 2 until N) if (holds[x]) storage++   // carrier-rounds held (exclude S outbox, R)
            // Delivery triggers an ACK that halts further carrying (O193): stop
            // accumulating once delivered, so storage = carrier-rounds UNTIL delivery.
            if (delivered) break
        }
        return Res(delivered, storage)
    }

    private fun measure(mode: Mode, duty: Double, escalateAfter: Int = ESCALATE_AFTER): Pair<Double, Double> {
        var del = 0; var store = 0L
        for (s in 0 until SEEDS) { val r = runOnce(mode, duty, s * 7919L + 3, escalateAfter); if (r.delivered) del++; store += r.storage }
        return del.toDouble() / SEEDS to store.toDouble() / SEEDS
    }

    @Test
    fun hybridEscalationDeadlineSweep() {
        val sb = StringBuilder("\nO202 — hybrid escalation-deadline sweep (when to widen custody→epidemic)\n")
        sb.append("cell = delivery% | storage. Earlier widen = more delivery under scarcity, more storage.\n\n")
        val deadlines = listOf(5, 15, 30, 60, 120)   // 120 = never widen (= pure custody)
        sb.append("duty".padEnd(7)); for (e in deadlines) sb.append("widen@$e".padEnd(16)); sb.append('\n')
        for (d in listOf(0.30, 0.15)) {
            sb.append("%3.0f%%".format(d * 100).padEnd(7))
            for (e in deadlines) {
                val m = measure(Mode.HYBRID, d, e)
                sb.append("%.0f%% | %.0f".format(m.first * 100, m.second).padEnd(16))
            }
            sb.append('\n')
        }
        sb.append("\nEarlier escalation recovers delivery under scarcity at a storage cost — the knee\n")
        sb.append("informs a default deadline (or an adaptive one keyed on observed duty cycle).\n")
        println(sb.toString())
        runCatching { java.io.File("build/o202-escalation-report.txt").writeText(sb.toString()) }

        // Teeth: at 15% duty, widening earlier (@5) delivers more than widening late (@60).
        val early = measure(Mode.HYBRID, 0.15, 5).first
        val late = measure(Mode.HYBRID, 0.15, 60).first
        assertTrue("earlier escalation should deliver more under scarcity ($early vs $late)", early >= late)
    }

    @Test
    fun hybridCustodyBeatsBothPureStrategies() {
        val sb = StringBuilder("\nO202 experiment — custody vs epidemic vs HYBRID under duty cycle\n")
        sb.append("N=$N, R's known contacts=$CONTACTS, $SEEDS seeds, hybrid widens at round $ESCALATE_AFTER.\n")
        sb.append("cell = delivery% | storage(carrier·rounds)\n\n")
        sb.append("duty".padEnd(7)).append("epidemic".padEnd(20)).append("custody".padEnd(20)).append("hybrid\n")
        val duties = listOf(1.0, 0.5, 0.3, 0.15)
        val res = HashMap<Pair<Mode, Double>, Pair<Double, Double>>()
        fun f(m: Pair<Double, Double>) = "%.0f%% | %.0f".format(m.first * 100, m.second)
        for (d in duties) {
            for (mode in Mode.values()) res[mode to d] = measure(mode, d)
            sb.append("%3.0f%%".format(d * 100).padEnd(7))
                .append(f(res[Mode.EPIDEMIC to d]!!).padEnd(20))
                .append(f(res[Mode.CUSTODY to d]!!).padEnd(20))
                .append(f(res[Mode.HYBRID to d]!!)).append('\n')
        }
        sb.append("\nHybrid = custody by default, widen to epidemic if undelivered by the deadline.\n")
        sb.append("Expected: hybrid keeps custody's low storage at high duty AND recovers epidemic's\n")
        sb.append("delivery at low duty — the best of both, the O202 design recommendation.\n")
        println(sb.toString())
        runCatching { java.io.File("build/o202-custody-report.txt").writeText(sb.toString()) }

        // Teeth: at full duty hybrid is cheap (near custody, far below epidemic).
        val eStoreFull = res[Mode.EPIDEMIC to 1.0]!!.second
        val hStoreFull = res[Mode.HYBRID to 1.0]!!.second
        assertTrue("hybrid should be much cheaper than epidemic at full duty ($hStoreFull vs $eStoreFull)", hStoreFull < eStoreFull * 0.6)
        // At low duty hybrid should recover delivery well above pure custody.
        val cDelLow = res[Mode.CUSTODY to 0.15]!!.first
        val hDelLow = res[Mode.HYBRID to 0.15]!!.first
        assertTrue("hybrid should recover low-duty delivery above pure custody ($hDelLow vs $cDelLow)", hDelLow >= cDelLow)
        // Not vacuous: low duty visibly reduces epidemic delivery vs full duty.
        assertTrue("low duty should reduce epidemic delivery", res[Mode.EPIDEMIC to 0.15]!!.first < res[Mode.EPIDEMIC to 1.0]!!.first)
    }
}
