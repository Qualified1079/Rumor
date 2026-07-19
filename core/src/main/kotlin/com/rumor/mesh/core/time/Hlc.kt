package com.rumor.mesh.core.time

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * O95 — Hybrid Logical Clock (Kulkarni et al. 2014). Causal ordering that
 * survives broken wall clocks: a message carries `(wallHint, counter)`; every
 * receive folds the remote timestamp in via max+1, so a reply can never sort
 * before its cause even when the replier's clock is months off (the field
 * fleet's Samsung was 64 days slow — the motivating case).
 *
 * ON THE WIRE since O95 wire adoption: rides `_ext.hlc` (see
 * `core/wire/HlcExt.kt`), reserved in `docs/RENAMED_FIELDS_NEVER_REUSE.md`.
 * Unsigned like all `_ext` — a relay can tamper it, but the impact is
 * display-order only (same class as `_ext.replyTo`), and the drift clamp
 * bounds the worst case.
 *
 * Total order: `(wallMs, counter)` lexicographic; callers needing a
 * deterministic tiebreak for identical timestamps from different senders
 * append the message id (concurrent events have no true order — the
 * tiebreak just has to be the same on every node).
 */
data class HlcTimestamp(val wallMs: Long, val counter: Long) : Comparable<HlcTimestamp> {
    override fun compareTo(other: HlcTimestamp): Int {
        val w = wallMs.compareTo(other.wallMs)
        return if (w != 0) w else counter.compareTo(other.counter)
    }
}

/** Thread-safe per-node clock state. */
class HlcClock(
    /**
     * State-poisoning bound (the O64-class defense): a remote wall value more
     * than this far ahead of OUR clock is clamped before adoption, so one
     * adversarial timestamp can't pin every node's HLC to year-3000 forever.
     * DELIBERATELY enormous (10 years): from a badly-behind node's viewpoint
     * every *correct* peer looks far-future (the 64-day-slow field phone saw
     * sane clocks as +64d), and a tight clamp here would reintroduce exactly
     * the causal violations HLC exists to kill. Human-scale clock damage must
     * always pass; only absurd values are bounded. The *display-pinning*
     * defense is NOT here — it lives in the UI comparator, clamped against
     * `receivedAtMs` (local ground truth), where tight bounds are safe.
     */
    private val maxDriftMs: Long = DEFAULT_MAX_DRIFT_MS,
    private val nowMs: () -> Long,
) : SynchronizedObject() {
    private var last = HlcTimestamp(0, 0)

    /**
     * Host-wired persistence hook: called with the new value on every advance.
     * HLC state must survive restart — a node whose clock runs behind (the
     * 64-day phone) would otherwise compose below its own pre-restart stamps.
     */
    var onAdvance: ((HlcTimestamp) -> Unit)? = null

    /** Restore persisted state (max-merge — never moves the clock backward). */
    fun restore(persisted: HlcTimestamp) = synchronized(this) {
        if (persisted > last) last = persisted
    }

    /** Stamp a local event (message compose). */
    fun tick(): HlcTimestamp = synchronized(this) {
        val now = nowMs()
        last = if (now > last.wallMs) HlcTimestamp(now, 0)
        else HlcTimestamp(last.wallMs, last.counter + 1)
        onAdvance?.invoke(last)
        last
    }

    /** Fold in a received timestamp; returns the advanced local clock. */
    fun update(remote: HlcTimestamp): HlcTimestamp = synchronized(this) {
        val now = nowMs()
        // Nonsense values (negative, hostile counter) are ignored outright.
        if (remote.wallMs < 0 || remote.counter < 0 || remote.counter > MAX_COUNTER) {
            return@synchronized last
        }
        val remoteWall = minOf(remote.wallMs, now + maxDriftMs)
        val maxWall = maxOf(now, last.wallMs, remoteWall)
        val counter = when {
            maxWall == last.wallMs && maxWall == remoteWall ->
                maxOf(last.counter, remote.counter) + 1
            maxWall == last.wallMs -> last.counter + 1
            maxWall == remoteWall -> remote.counter + 1
            else -> 0
        }
        last = HlcTimestamp(maxWall, counter)
        onAdvance?.invoke(last)
        last
    }

    fun current(): HlcTimestamp = synchronized(this) { last }

    companion object {
        /**
         * Why 10 years (SHTF drift model, 2026-07-18): quartz RTC physics buys
         * only ±1–4 h over a ~5-year device half-life (±20–50 ppm crystal,
         * cold-bias ~-13 ppm, aging ±1–3 ppm/yr). The dominant real errors are
         * discrete and BACKWARD — dead RTC power resets to epoch (~56 y) or
         * ROM build date (the 64-day field phone) — which the max-fold handles
         * unbounded by design. Forward errors are only manual-set mistakes
         * (≤ ~1 y) since hardware never fails forward; 10 y sits an order of
         * magnitude past the worst organic forward error while still killing
         * the Long.MAX-pin attack this bound exists for.
         */
        const val DEFAULT_MAX_DRIFT_MS = 10 * 365 * 24 * 3600_000L
        /**
         * Counter sanity ceiling — bounds overflow games from a hostile stamp.
         * Long + astronomical ceiling on purpose: under a wall pin (the
         * accepted G42 attack) EVERY node's counter increments on every event
         * for years — an Int-scale ceiling gets crossed by a busy relay in
         * weeks, after which peers reject its legit stamps and causality
         * breaks. 1e15 is ~31,000 years of 1kHz events: unreachable honestly,
         * still 4 orders of magnitude under Long overflow.
         */
        const val MAX_COUNTER = 1_000_000_000_000_000L
    }
}
