package com.rumor.mesh.core

/**
 * Injectable time source. Production code uses [SystemClock] (wall-clock).
 * Sim and tests can supply a deterministic clock so that timestamps that
 * influence behavior — message `receivedAtMs` feeding eviction order, rate-
 * limit windows, breadcrumb prune cutoffs, scheduled-message firing — produce
 * the same outputs across replays of the same scenario.
 *
 * Why this exists: scenario 04 bridge-kill-isolation flaked at ~6% msgDelta
 * across deterministic replays. Root cause was wall-clock time varying
 * between runs, leaking into `RumorMessage.receivedAtMs`, leaking into
 * `MessageRepository.evictOldest`'s sort key, leaking into which messages
 * survived eviction one tick later, leaking into divergent offer batches.
 * O12 escalation.
 *
 * Both [Clock] and its production singleton [SystemClock] live in commonMain
 * via `expect`/`actual`; the JVM `actual` delegates to
 * `System.currentTimeMillis()`. Non-JVM `actual`s call the platform's
 * monotonic wall-clock API (`CFAbsoluteTimeGetCurrent`, `clock_gettime`, etc).
 */
fun interface Clock {
    fun now(): Long
}

/**
 * Production singleton bound to the platform wall clock. Common code can
 * reference `SystemClock.now()` directly; sim and tests inject their own
 * [Clock] implementation where determinism matters.
 */
expect object SystemClock : Clock
