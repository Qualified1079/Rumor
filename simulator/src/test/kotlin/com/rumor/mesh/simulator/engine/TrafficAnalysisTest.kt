package com.rumor.mesh.simulator.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * O195 experiment (abstract): traffic-analysis resistance at a relay. An observer
 * watches inbound and outbound timings and tries to LINK each outbound to the
 * inbound it came from (which reveals who-forwarded-what → sender/recipient
 * correlation). We measure the observer's linking accuracy vs the latency cost
 * for three mitigations:
 *   - immediate forward (baseline): timing is a perfect fingerprint.
 *   - random relay delay: each message waits U(0,D); timing decorrelates.
 *   - mix batching: hold k messages, release together shuffled; the observer
 *     can't distinguish within a batch → ~1/k linking.
 *
 * The point is the trade curve: how much unlinkability per unit of added latency,
 * so O195's "worth it?" decision (O27/O55 budget) is grounded, not hand-waved.
 */
class TrafficAnalysisTest {

    private companion object {
        const val M = 400        // messages through the relay
        const val SEEDS = 200
    }

    private sealed interface Policy {
        object Immediate : Policy
        data class Delay(val d: Double) : Policy
        data class Mix(val k: Int) : Policy
    }

    private data class Res(val linkAccuracy: Double, val meanLatency: Double)

    private fun runOnce(policy: Policy, seed: Long): Res {
        val rng = Random(seed)
        // Inbound arrival times: exponential inter-arrivals, mean 1.0.
        val tIn = DoubleArray(M)
        var clock = 0.0
        for (i in 0 until M) { clock += -kotlin.math.ln(1 - rng.nextDouble()); tIn[i] = clock }
        val tOut = DoubleArray(M)
        val expDelay: Double
        when (policy) {
            is Policy.Immediate -> { for (i in 0 until M) tOut[i] = tIn[i] + 0.01; expDelay = 0.01 }
            is Policy.Delay -> { for (i in 0 until M) tOut[i] = tIn[i] + rng.nextDouble() * policy.d; expDelay = policy.d / 2 }
            is Policy.Mix -> {
                // Release each full batch of k at the arrival time of its last member,
                // with a tiny shuffle jitter so members share (approximately) one instant.
                var i = 0
                while (i < M) {
                    val end = minOf(i + policy.k, M)
                    val release = tIn[end - 1]
                    for (j in i until end) tOut[j] = release + rng.nextDouble() * 1e-6
                    i = end
                }
                expDelay = policy.k / 2.0
            }
        }
        // Observer: greedily match each outbound (in time order) to the unused
        // inbound minimizing |tOut - tIn - expDelay|. Count correct links.
        val order = (0 until M).sortedBy { tOut[it] }
        val used = BooleanArray(M)
        var correct = 0
        for (o in order) {
            var best = -1; var bestErr = Double.MAX_VALUE
            for (c in 0 until M) {
                if (used[c]) continue
                val err = abs(tOut[o] - tIn[c] - expDelay)
                if (err < bestErr) { bestErr = err; best = c }
            }
            used[best] = true
            if (best == o) correct++
        }
        var lat = 0.0; for (i in 0 until M) lat += tOut[i] - tIn[i]
        return Res(correct.toDouble() / M, lat / M)
    }

    private fun measure(policy: Policy): Res {
        var acc = 0.0; var lat = 0.0
        for (s in 0 until SEEDS) { val r = runOnce(policy, s * 2749L + 5); acc += r.linkAccuracy; lat += r.meanLatency }
        return Res(acc / SEEDS, lat / SEEDS)
    }

    @Test
    fun mixingTradesLatencyForUnlinkability() {
        val sb = StringBuilder("\nO195 experiment — traffic-analysis resistance at a relay\n")
        sb.append("$M msgs, $SEEDS seeds, inter-arrival mean=1.0. link% = observer's correct inbound↔outbound matches\n\n")
        sb.append("policy".padEnd(16)).append("link accuracy".padEnd(16)).append("mean added latency (arrival-intervals)\n")
        val policies = listOf<Pair<String, Policy>>(
            "immediate" to Policy.Immediate,
            "delay D=2" to Policy.Delay(2.0),
            "delay D=8" to Policy.Delay(8.0),
            "mix k=4" to Policy.Mix(4),
            "mix k=16" to Policy.Mix(16),
        )
        val res = LinkedHashMap<String, Res>()
        for ((name, p) in policies) {
            val r = measure(p); res[name] = r
            sb.append(name.padEnd(16)).append("%.0f%%".format(r.linkAccuracy * 100).padEnd(16)).append("%.1f".format(r.meanLatency)).append('\n')
        }
        sb.append("\nImmediate forward is a perfect timing fingerprint. Random delay decorrelates\n")
        sb.append("gradually (needs large D to help, at real latency cost). Mix batching gives the\n")
        sb.append("strongest unlinkability per message (~1/k) but adds latency ∝ k / arrival-rate —\n")
        sb.append("cheap on a busy relay, brutal on a quiet one. This is the O195 dial: batch size\n")
        sb.append("should scale with traffic, and none of it is free (O27/O55 budget).\n")
        println(sb.toString())
        runCatching { java.io.File("build/o195-traffic-report.txt").writeText(sb.toString()) }

        // Teeth: mixing must cut linkability well below immediate, and cost latency.
        assertTrue("immediate should be near-perfectly linkable", res["immediate"]!!.linkAccuracy > 0.9)
        assertTrue("mix k=16 should cut linkability far below immediate", res["mix k=16"]!!.linkAccuracy < 0.2)
        assertTrue("mix k=16 should cost more latency than immediate", res["mix k=16"]!!.meanLatency > res["immediate"]!!.meanLatency)
        assertTrue("bigger batch = less linkable", res["mix k=16"]!!.linkAccuracy < res["mix k=4"]!!.linkAccuracy)
    }
}
