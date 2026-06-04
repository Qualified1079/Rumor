package com.rumor.mesh.core.platform

/**
 * Read-many / write-rarely lock. Currently used by `RumorLog` so its ring
 * buffer can be safely tailed by debug-metrics polling without serializing
 * appends.
 *
 * JVM `actual` wraps `java.util.concurrent.locks.ReentrantReadWriteLock`.
 * Non-JVM `actual`s can fall back to a single `Mutex` (which collapses reads
 * and writes into the same exclusion class) — slower but correct, fine for
 * the call sites we have. Specialize later if anything becomes hot.
 *
 * Phase 1c shim per docs/IOS_PORT_PHASE_1_HANDOFF.md.
 */
expect class RwLock() {
    fun <T> read(block: () -> T): T
    fun <T> write(block: () -> T): T
}
