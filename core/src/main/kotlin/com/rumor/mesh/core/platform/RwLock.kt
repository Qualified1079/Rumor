package com.rumor.mesh.core.platform

import java.util.concurrent.locks.ReentrantReadWriteLock


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
/**
 * JVM `actual` for [RwLock]. Wraps `ReentrantReadWriteLock` directly.
 */
class RwLock constructor() {
    private val lock = ReentrantReadWriteLock()

    fun <T> read(block: () -> T): T {
        lock.readLock().lock()
        try { return block() } finally { lock.readLock().unlock() }
    }

    fun <T> write(block: () -> T): T {
        lock.writeLock().lock()
        try { return block() } finally { lock.writeLock().unlock() }
    }
}
