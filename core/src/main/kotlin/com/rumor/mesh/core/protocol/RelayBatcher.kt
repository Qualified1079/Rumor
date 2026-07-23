package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.Clock
import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.model.RumorMessage
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * Buffers relayed (not locally-composed) messages and releases each after its
 * own randomized delay of [minWindowMs]..[minWindowMs]+[spreadMs].
 *
 * Goals:
 *  - **Timing-correlation resistance** — a passive observer cannot reliably
 *    link an incoming message to its outbound relay by comparing timestamps.
 *  - **Micro-batching efficiency** — amortises transport wake-ups across a
 *    window of messages rather than one-per-message.
 *
 * **O130(a) — why a per-message deadline, not one shared flush window:** the
 * earlier design slept a random window then flushed *everything* pending, so a
 * message that arrived just before the flush fired got ~zero delay — exactly
 * the arrival→relay correlation the batcher claims to prevent. Now each message
 * is stamped with its own `releaseAt` on arrival and is only flushed once that
 * deadline passes, so its outbound time is decorrelated from its inbound time
 * regardless of where the tick lands. The tick-based sweep still batches
 * (multiple due messages release together), preserving the wake-up amortisation
 * that matters for the O55 low-power envelope.
 *
 * Locally-composed messages bypass this batcher entirely so the local sender
 * does not feel the delay.
 */
internal class RelayBatcher(
    scope: CoroutineScope,
    private val minWindowMs: Long = MIN_WINDOW_MS,
    private val spreadMs: Long = SPREAD_MS,
    private val clock: Clock = SystemClock,
    private val onFlush: (List<RumorMessage>) -> Unit,
) : SynchronizedObject() {

    private class Pending(val releaseAt: Long, val msg: RumorMessage)

    private val pending = mutableListOf<Pending>()

    init {
        scope.launch {
            while (true) {
                // Sweep at the minimum-window cadence — fine granularity relative
                // to the delays, so a message never waits much past its deadline.
                delay(minWindowMs.coerceAtLeast(1))
                val now = clock.now()
                val due = synchronized(this@RelayBatcher) {
                    if (pending.isEmpty()) return@synchronized emptyList<RumorMessage>()
                    val ready = ArrayList<RumorMessage>()
                    val it = pending.iterator()
                    while (it.hasNext()) {
                        val p = it.next()
                        if (p.releaseAt <= now) { ready.add(p.msg); it.remove() }
                    }
                    ready
                }
                if (due.isNotEmpty()) onFlush(due)
            }
        }
    }

    fun add(msg: RumorMessage) {
        // Non-CSPRNG fine here — this is timing jitter, not security material.
        val releaseAt = clock.now() + minWindowMs + (abs(Random.nextLong()) % spreadMs)
        synchronized(this) { pending.add(Pending(releaseAt, msg)) }
    }

    companion object {
        const val MIN_WINDOW_MS = 100L
        const val SPREAD_MS     = 400L  // per-message delay: 100–500 ms
    }
}
