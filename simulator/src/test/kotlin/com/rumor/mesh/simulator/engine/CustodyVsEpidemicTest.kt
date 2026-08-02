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
    }

    private data class Res(val delivered: Boolean, val storage: Long)

    private fun runOnce(custody: Boolean, duty: Double, seed: Long): Res {
        val rng = Random(seed)
        val contacts = (2 until 2 + CONTACTS).toSet()
        // Who is allowed to retain a copy for R.
        val retainer = BooleanArray(N) { custody.not() || it == S || it in contacts }
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
                    if (a == b || !online[b] || holds[b] || !retainer[b]) continue
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
        }
        return Res(delivered, storage)
    }

    private fun measure(custody: Boolean, duty: Double): Pair<Double, Double> {
        var del = 0; var store = 0L
        for (s in 0 until SEEDS) { val r = runOnce(custody, duty, s * 7919L + 3); if (r.delivered) del++; store += r.storage }
        return del.toDouble() / SEEDS to store.toDouble() / SEEDS
    }

    @Test
    fun custodyHoldsDeliveryAtFractionOfStorage() {
        val sb = StringBuilder("\nO202 experiment — custody vs epidemic storage under duty cycle\n")
        sb.append("N=$N, R's known contacts=$CONTACTS, $SEEDS seeds. cell = delivery% | storage(carrier·rounds)\n\n")
        sb.append("duty".padEnd(8)).append("epidemic".padEnd(24)).append("custody(S+contacts)\n")
        val duties = listOf(1.0, 0.5, 0.3, 0.15)
        val results = HashMap<Pair<Boolean, Double>, Pair<Double, Double>>()
        for (d in duties) {
            val e = measure(false, d); val c = measure(true, d)
            results[false to d] = e; results[true to d] = c
            fun f(m: Pair<Double, Double>) = "%.0f%% | %.0f".format(m.first * 100, m.second)
            sb.append("%3.0f%%".format(d * 100).padEnd(8)).append(f(e).padEnd(24)).append(f(c)).append('\n')
        }
        sb.append("\nThesis: custody keeps delivery close to epidemic at a fraction of the storage —\n")
        sb.append("because R's known contacts ARE who meets R. The stranger copies epidemic keeps\n")
        sb.append("are mostly wasted (they rarely meet R). Cost: if S can't reach a contact, custody\n")
        sb.append("loses more than epidemic — the delivery gap widens as duty cycle drops.\n")
        println(sb.toString())
        runCatching { java.io.File("build/o202-custody-report.txt").writeText(sb.toString()) }

        // Teeth: custody must cost far less storage, and at full duty keep delivery close.
        val (_, eStoreFull) = results[false to 1.0]!!
        val (_, cStoreFull) = results[true to 1.0]!!
        assertTrue("custody should use much less storage than epidemic ($cStoreFull vs $eStoreFull)", cStoreFull < eStoreFull * 0.5)
        val (eDelFull, _) = results[false to 1.0]!!
        val (cDelFull, _) = results[true to 1.0]!!
        assertTrue("at full duty custody delivery should be close to epidemic ($cDelFull vs $eDelFull)", cDelFull >= eDelFull - 0.05)
        // Not vacuous: low duty must visibly reduce delivery for both.
        assertTrue("low duty should reduce epidemic delivery", results[false to 0.15]!!.first < eDelFull)
    }
}
