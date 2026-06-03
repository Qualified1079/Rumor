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
 * The interface lives in commonMain; the platform-specific implementation
 * ([SystemClock]) stays in jvmMain (uses `System.currentTimeMillis()`) until
 * a platform shim is added in Phase 1c.
 */
fun interface Clock {
    fun now(): Long
}
