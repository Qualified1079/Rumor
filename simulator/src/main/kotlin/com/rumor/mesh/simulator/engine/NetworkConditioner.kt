package com.rumor.mesh.simulator.engine

import kotlin.random.Random

/**
 * Applied per-edge between two SimNodes. Controls latency, jitter, loss,
 * bandwidth cap, and hard partition state. Parameters are all mutable so the
 * dashboard can adjust them live without restarting the sim.
 *
 * [simulate] is called by SimTransport before each message delivery.
 * Returns the actual delay in virtual milliseconds, or null if the message
 * should be dropped (loss or partition).
 */
class NetworkConditioner {
    @Volatile var latencyMs:       Long    = 50L
    @Volatile var jitterMs:        Long    = 20L
    @Volatile var lossRate:        Double  = 0.0     // 0..1
    @Volatile var bandwidthBytesPerSec: Long = 1_000_000L
    @Volatile var partitioned:     Boolean = false

    fun simulate(payloadBytes: Int, rng: Random): Long? {
        if (partitioned) return null
        if (rng.nextDouble() < lossRate) return null
        val jitter = if (jitterMs > 0) rng.nextLong(-jitterMs, jitterMs + 1) else 0L
        val transferMs = if (bandwidthBytesPerSec > 0) (payloadBytes * 1000L) / bandwidthBytesPerSec else 0L
        return (latencyMs + jitter + transferMs).coerceAtLeast(0L)
    }

    fun snapshot() = ConditionerSnapshot(latencyMs, jitterMs, lossRate, bandwidthBytesPerSec, partitioned)
}

data class ConditionerSnapshot(
    val latencyMs: Long,
    val jitterMs: Long,
    val lossRate: Double,
    val bandwidthBytesPerSec: Long,
    val partitioned: Boolean,
)
