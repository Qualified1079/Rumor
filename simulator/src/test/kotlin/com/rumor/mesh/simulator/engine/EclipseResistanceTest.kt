package com.rumor.mesh.simulator.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * O194 experiment (abstract): eclipse = an adversary fills ALL of a target R's
 * connections with sybils that don't relay, cutting R off. The recorded O194
 * decision is that the defense is a **trust-weighted view** (prefer known
 * contacts), NOT radio fingerprinting. This quantifies it: delivery to R vs
 * sybil fraction, for random peering vs trust-weighted peering (fill R's
 * neighbour slots from its known honest contacts first, then random).
 *
 * Honest nodes relay and form the reachable mesh; a DM reaches R iff R has ≥1
 * honest neighbour that is online. Contacts have a duty cycle, so trust-weighting
 * isn't a trivial win — when all of R's contacts are offline it falls back to
 * random peers and inherits the eclipse risk.
 */
class EclipseResistanceTest {

    private companion object {
        const val N = 500
        const val R_DEGREE = 6      // connection slots R maintains
        const val CONTACTS = 8      // R's known (honest) contacts
        const val CONTACT_DUTY = 0.5
        const val SEEDS = 4000
    }

    private enum class Peering { RANDOM, TRUST_WEIGHTED }

    private fun deliveredToR(sybilFraction: Double, peering: Peering, seed: Long): Boolean {
        val rng = Random(seed)
        // Nodes 0..N-1; R = 0, R's contacts = 1..CONTACTS (honest by definition).
        val sybil = BooleanArray(N)
        run {
            // Sybils drawn from the non-contact, non-R population.
            val pool = (1 + CONTACTS until N).toMutableList()
            val count = (sybilFraction * N).toInt().coerceAtMost(pool.size)
            pool.shuffle(rng)
            for (i in 0 until count) sybil[pool[i]] = true
        }
        val online = BooleanArray(N) { true }
        for (c in 1..CONTACTS) online[c] = rng.nextDouble() < CONTACT_DUTY

        val neighbours = LinkedHashSet<Int>()
        if (peering == Peering.TRUST_WEIGHTED) {
            for (c in 1..CONTACTS) { if (online[c] && neighbours.size < R_DEGREE) neighbours.add(c) }
        }
        // Fill remaining slots with random online non-R nodes (may be sybil).
        var guard = 0
        while (neighbours.size < R_DEGREE && guard++ < N * 4) {
            val x = 1 + rng.nextInt(N - 1)
            if (online[x]) neighbours.add(x)
        }
        // Delivered iff at least one neighbour is an honest (non-sybil) relay.
        return neighbours.any { !sybil[it] }
    }

    private fun rate(f: Double, peering: Peering): Double {
        var ok = 0
        for (s in 0 until SEEDS) if (deliveredToR(f, peering, s * 3319L + 1)) ok++
        return ok.toDouble() / SEEDS
    }

    @Test
    fun trustWeightedPeeringResistsEclipse() {
        val sb = StringBuilder("\nO194 experiment — eclipse resistance: random vs trust-weighted peering\n")
        sb.append("N=$N, R degree=$R_DEGREE, R contacts=$CONTACTS @ ${CONTACT_DUTY} duty, $SEEDS seeds.\n")
        sb.append("cell = % of trials R stays reachable (has ≥1 honest relay neighbour)\n\n")
        sb.append("sybil frac".padEnd(12)).append("random peering".padEnd(18)).append("trust-weighted\n")
        val fracs = listOf(0.0, 0.5, 0.8, 0.9, 0.95, 0.99)
        val res = HashMap<Pair<Double, Peering>, Double>()
        for (f in fracs) {
            for (p in Peering.values()) res[f to p] = rate(f, p)
            sb.append("%.0f%%".format(f * 100).padEnd(12))
                .append("%.0f%%".format(res[f to Peering.RANDOM]!! * 100).padEnd(18))
                .append("%.0f%%".format(res[f to Peering.TRUST_WEIGHTED]!! * 100)).append('\n')
        }
        sb.append("\nRandom peering is eclipsed as sybil fraction → 1 (all R's slots go sybil).\n")
        sb.append("Trust-weighted peering stays reachable as long as ≥1 known contact is online —\n")
        sb.append("its residual risk is contact-duty-cycle (all contacts offline → random fallback),\n")
        sb.append("NOT sybil count. Confirms O194: defend with a trust-weighted view, not radio ID.\n")
        println(sb.toString())
        runCatching { java.io.File("build/o194-eclipse-report.txt").writeText(sb.toString()) }

        // Teeth: under heavy sybil, trust-weighted must vastly beat random; at 0 sybil they match.
        assertTrue("trust-weighted should resist eclipse at 99% sybil (${res[0.99 to Peering.TRUST_WEIGHTED]} vs ${res[0.99 to Peering.RANDOM]})",
            res[0.99 to Peering.TRUST_WEIGHTED]!! > res[0.99 to Peering.RANDOM]!! + 0.3)
        assertTrue("random peering should be largely eclipsed at 99% sybil", res[0.99 to Peering.RANDOM]!! < 0.3)
        // Trust-weighted residual risk ≈ all-contacts-offline = (1-duty)^contacts (small), so it stays high.
        assertTrue("trust-weighted should stay mostly reachable even at 99% sybil", res[0.99 to Peering.TRUST_WEIGHTED]!! > 0.9)
    }
}
