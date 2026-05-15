package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.model.RumorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    private val rng = java.util.Random()

    init {
        scope.launch {
            while (true) {
                delay(minWindowMs + (abs(rng.nextLong()) % spreadMs))
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
