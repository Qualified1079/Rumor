package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.transport.wifidirect.BloomFilterData
import org.junit.Test
import kotlin.random.Random

/**
 * O42 UX validation: empirical bloom false-positive rate vs configured rate, at
 * realistic small-set sizes (the sizes the adaptive plan keeps bloom on). An FP
 * here means a ready message is skipped this exchange; on a quiet mesh the skip
 * persists → a late/undelivered chat message, which is the UX we're tuning.
 *
 * Measured empirically because the filter uses a custom murmur mix + a
 * `hashCount` cap of 10, so the theoretical rate is only an upper bound. Prints
 * the rate and wire size so the knee is picked from data, not the paper formula.
 */
class BloomFalsePositiveTest {

    private fun ids(n: Int, rng: Random): List<String> =
        List(n) { "msg-${rng.nextLong()}-$it" }

    private fun measure(setSize: Int, configuredFp: Double, rng: Random): Pair<Double, Int> {
        val known = ids(setSize, rng)
        val bloom = BloomFilterData(expectedItems = setSize.coerceAtLeast(64), falsePositiveRate = configuredFp)
        for (id in known) bloom.add(id)
        val bytes = bloom.serialize().length

        val probes = 200_000
        var falsePositives = 0
        repeat(probes) {
            val absent = "probe-${rng.nextLong()}-$it"   // not in the known set
            if (bloom.mightContain(absent)) falsePositives++
        }
        return (falsePositives.toDouble() / probes) to bytes
    }

    @Test
    fun `empirical false-positive rate across configured rates and set sizes`() {
        val rng = Random(1234)
        println("O42 bloom FP — empirical rate (skip probability per message per exchange) + wire bytes:")
        for (setSize in listOf(200, 500, 2_000, 5_000)) {
            for (fp in listOf(0.01, 0.001, 0.0001, 0.00001)) {
                val (empirical, bytes) = measure(setSize, fp, rng)
                val oneIn = if (empirical > 0) (1.0 / empirical).toLong() else Long.MAX_VALUE
                println(
                    "  N=%5d cfg=%.5f  empirical=%.6f (~1 in %,d)  wire=%,dB"
                        .format(setSize, fp, empirical, oneIn, bytes)
                )
            }
        }
    }
}
