package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.model.RumorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * Buffers relayed (not locally-composed) messages and flushes them in random
 * windows of [MIN_WINDOW_MS]..[MIN_WINDOW_MS]+[SPREAD_MS].
 *
 * Goals:
 *  - **Timing correlation resistance** — a passive observer cannot reliably
 *    link an incoming message to its outbound relay by comparing timestamps.
 *  - **Micro-batching efficiency** — amortises transport wake-ups across a
 *    window of messages rather than one-per-message.
 *
 * Locally-composed messages bypass this batcher entirely so the local sender
 * does not feel the delay.
 */
internal class RelayBatcher(
    scope: CoroutineScope,
    private val minWindowMs: Long = MIN_WINDOW_MS,
    private val spreadMs: Long = SPREAD_MS,
    private val onFlush: (List<RumorMessage>) -> Unit,
) {
    private val pending = Channel<RumorMessage>(Channel.UNLIMITED)

    init {
        scope.launch {
            while (true) {
                // Non-CSPRNG fine here — this is timing jitter, not security material.
                delay(minWindowMs + (abs(Random.nextLong()) % spreadMs))
                val batch = buildList {
                    while (true) add(pending.tryReceive().getOrNull() ?: break)
                }
                if (batch.isNotEmpty()) onFlush(batch)
            }
        }
    }

    fun add(msg: RumorMessage) { pending.trySend(msg) }

    companion object {
        const val MIN_WINDOW_MS = 100L
        const val SPREAD_MS     = 400L  // total window: 100–500 ms
    }
}
