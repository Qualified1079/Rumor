package com.rumor.mesh.core.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

data class MetricsSnapshot(
    /** Total messages received over the wire (including duplicates). */
    val dedupTotal: Long,
    /** Subset of [dedupTotal] that were already known (duplicate suppressed). */
    val dedupHits: Long,
    /** Messages dropped because the Ed25519 signature did not verify. */
    val sigFailures: Long,
    /** Gossip exchanges that completed successfully. */
    val exchangeSuccesses: Long,
    /** Gossip exchanges that failed (connection error, timeout, bad handshake). */
    val exchangeFailures: Long,
    /** Messages this node has relayed on behalf of others. */
    val relayedMessages: Long,
    /** Smoothed average round-trip time across recent exchanges, ms. */
    val avgRttMs: Long,
    /** Current number of messages waiting in the outbound scheduler. */
    val queueDepth: Int,
) {
    val dedupHitRate: Float
        get() = if (dedupTotal == 0L) 0f else dedupHits.toFloat() / dedupTotal
}

/**
 * Lightweight operational counters for the local node.
 *
 * All increments are thread-safe (AtomicLong). [publish] snapshots the current
 * state into [flow]; callers can rate-limit how often they call it.
 */
class CanaryMetrics {
    private val _dedupTotal     = AtomicLong()
    private val _dedupHits      = AtomicLong()
    private val _sigFailures    = AtomicLong()
    private val _exchSuccesses  = AtomicLong()
    private val _exchFailures   = AtomicLong()
    private val _relayedMsgs    = AtomicLong()
    private val _rttSum         = AtomicLong()
    private val _rttCount       = AtomicLong()

    private val _flow = MutableStateFlow(MetricsSnapshot(0,0,0,0,0,0,0,0))
    val flow: StateFlow<MetricsSnapshot> = _flow

    fun recordIncoming(isDuplicate: Boolean) {
        _dedupTotal.incrementAndGet()
        if (isDuplicate) _dedupHits.incrementAndGet()
        publish()
    }

    fun recordSigFailure() {
        _sigFailures.incrementAndGet()
        publish()
    }

    fun recordExchange(success: Boolean, rttMs: Long) {
        if (success) {
            _exchSuccesses.incrementAndGet()
            _rttSum.addAndGet(rttMs)
            _rttCount.incrementAndGet()
        } else {
            _exchFailures.incrementAndGet()
        }
        publish()
    }

    fun recordRelay() {
        _relayedMsgs.incrementAndGet()
        publish()
    }

    fun publish(queueDepth: Int = _flow.value.queueDepth) {
        val rttCount = _rttCount.get()
        _flow.value = MetricsSnapshot(
            dedupTotal       = _dedupTotal.get(),
            dedupHits        = _dedupHits.get(),
            sigFailures      = _sigFailures.get(),
            exchangeSuccesses = _exchSuccesses.get(),
            exchangeFailures  = _exchFailures.get(),
            relayedMessages  = _relayedMsgs.get(),
            avgRttMs         = if (rttCount > 0) _rttSum.get() / rttCount else 0,
            queueDepth       = queueDepth,
        )
    }
}
