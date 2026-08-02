package com.rumor.mesh.simulator.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * O76 experiment (abstract): per-message size-bucket padding. Padding each
 * message up to a fixed bucket size makes messages of the same padded size
 * indistinguishable BY SIZE — a size anonymity set. The trade: fewer/larger
 * buckets → bigger anonymity sets (better privacy) but more padding overhead;
 * more buckets → less waste but size leaks more. This measures overhead vs
 * anonymity across bucket counts to sanity-check O76's 6-bucket choice.
 */
class PaddingAnonymityTest {

    private companion object {
        const val M = 8000
        const val MIN_SIZE = 8
        const val MAX_SIZE = 5000
    }

    /** Realistic text-length mix: mostly short chat, some medium, few long. */
    private fun sampleSizes(rng: Random): IntArray = IntArray(M) {
        val r = rng.nextDouble()
        when {
            r < 0.70 -> MIN_SIZE + rng.nextInt(140)             // short
            r < 0.95 -> 140 + rng.nextInt(860)                  // medium
            else -> 1000 + rng.nextInt(MAX_SIZE - 1000)         // long
        }
    }

    /** Geometric bucket boundaries; pad each size up to the smallest boundary ≥ it. */
    private fun boundaries(b: Int): IntArray {
        if (b <= 1) return intArrayOf(MAX_SIZE)
        return IntArray(b) { i ->
            (MIN_SIZE * (MAX_SIZE.toDouble() / MIN_SIZE).pow((i + 1).toDouble() / b)).toInt().coerceAtLeast(MIN_SIZE)
        }
    }

    private fun padTo(size: Int, bounds: IntArray): Int = bounds.firstOrNull { it >= size } ?: MAX_SIZE

    private data class Res(val overheadPct: Double, val smallAnonPct: Double, val minAnon: Int)

    private fun evaluate(buckets: Int, noPad: Boolean, rng: Random): Res {
        val sizes = sampleSizes(rng)
        val bounds = boundaries(buckets)
        val padded = IntArray(M) { if (noPad) sizes[it] else padTo(sizes[it], bounds) }
        val realSum = sizes.sum().toLong()
        val padSum = padded.sum().toLong()
        // Anonymity set = # of messages sharing a padded size.
        val pop = HashMap<Int, Int>()
        for (p in padded) pop.merge(p, 1, Int::plus)
        val smallAnon = padded.count { pop[it]!! < 5 }   // in a set of <5 → size nearly identifying
        return Res(
            overheadPct = (padSum - realSum).toDouble() / realSum * 100,
            smallAnonPct = smallAnon.toDouble() / M * 100,
            minAnon = pop.values.min(),
        )
    }

    @Test
    fun paddingTradesBandwidthForSizeAnonymity() {
        val sb = StringBuilder("\nO76 experiment — size-bucket padding: bandwidth overhead vs size-anonymity\n")
        sb.append("$M msgs (70% short / 25% med / 5% long, $MIN_SIZE–$MAX_SIZE bytes).\n")
        sb.append("overhead = padded/real−1; small-anon = % of msgs whose padded-size set has <5 members\n\n")
        sb.append("buckets".padEnd(12)).append("overhead".padEnd(14)).append("small-anon%".padEnd(14)).append("min anon set\n")
        val configs = listOf(1, 2, 3, 6, 12, 24)
        val res = LinkedHashMap<Int, Res>()
        val rng = Random(42)
        for (b in configs) {
            val r = evaluate(b, false, Random(rng.nextLong())); res[b] = r
            sb.append("$b".padEnd(12))
                .append("%.0f%%".format(r.overheadPct).padEnd(14))
                .append("%.1f%%".format(r.smallAnonPct).padEnd(14))
                .append("${r.minAnon}").append('\n')
        }
        val noPad = evaluate(1, true, Random(rng.nextLong()))
        sb.append("none".padEnd(12)).append("0%".padEnd(14)).append("%.1f%%".format(noPad.smallAnonPct).padEnd(14)).append("${noPad.minAnon}\n")
        sb.append("\n1 bucket (pad-to-max) = perfect size-anonymity, huge overhead. More buckets shed\n")
        sb.append("overhead but shrink anonymity sets (long/rare sizes become identifying). O76's\n")
        sb.append("~6 geometric buckets sits near the knee: modest overhead, few messages left in a\n")
        sb.append("tiny size-set. Compression (O76) runs BEFORE padding, so it mainly shifts the\n")
        sb.append("input distribution smaller — it doesn't remove the need to pad.\n")
        println(sb.toString())
        runCatching { java.io.File("build/o76-padding-report.txt").writeText(sb.toString()) }

        // Teeth: fewer buckets → more overhead but better anonymity; no-pad leaks size badly.
        assertTrue("1 bucket should cost far more overhead than 24", res[1]!!.overheadPct > res[24]!!.overheadPct)
        assertTrue("1 bucket should have ~no small-anon messages", res[1]!!.smallAnonPct < 1.0)
        assertTrue("no padding should leave many messages in tiny size-sets", noPad.smallAnonPct > res[6]!!.smallAnonPct)
    }
}
