package com.rumor.mesh.core.time

/**
 * O95 — Hybrid Logical Clock (Kulkarni et al. 2014). Causal ordering that
 * survives broken wall clocks: a message carries `(wallHint, counter)`; every
 * receive folds the remote timestamp in via max+1, so a reply can never sort
 * before its cause even when the replier's clock is months off (the field
 * fleet's Samsung was 64 days slow — the motivating case).
 *
 * SHADOW-ONLY today: not on the wire, no `RumorMessage` field. The O95
 * simulation sweep (`HlcOrderingScenarioTest`) quantifies the win first;
 * wiring it is a wire-format change coordinated through
 * `docs/RENAMED_FIELDS_NEVER_REUSE.md` when the numbers justify it.
 *
 * Total order: `(wallMs, counter)` lexicographic; callers needing a
 * deterministic tiebreak for identical timestamps from different senders
 * append the message id (concurrent events have no true order — the
 * tiebreak just has to be the same on every node).
 */
data class HlcTimestamp(val wallMs: Long, val counter: Int) : Comparable<HlcTimestamp> {
    override fun compareTo(other: HlcTimestamp): Int {
        val w = wallMs.compareTo(other.wallMs)
        return if (w != 0) w else counter.compareTo(other.counter)
    }
}

/** Per-node clock state. Not thread-safe — callers own synchronization. */
class HlcClock(private val nowMs: () -> Long) {
    private var last = HlcTimestamp(0, 0)

    /** Stamp a local event (message compose). */
    fun tick(): HlcTimestamp {
        val now = nowMs()
        last = if (now > last.wallMs) HlcTimestamp(now, 0)
        else HlcTimestamp(last.wallMs, last.counter + 1)
        return last
    }

    /** Fold in a received timestamp; returns the advanced local clock. */
    fun update(remote: HlcTimestamp): HlcTimestamp {
        val now = nowMs()
        val maxWall = maxOf(now, last.wallMs, remote.wallMs)
        val counter = when {
            maxWall == last.wallMs && maxWall == remote.wallMs ->
                maxOf(last.counter, remote.counter) + 1
            maxWall == last.wallMs -> last.counter + 1
            maxWall == remote.wallMs -> remote.counter + 1
            else -> 0
        }
        last = HlcTimestamp(maxWall, counter)
        return last
    }

    fun current(): HlcTimestamp = last
}
