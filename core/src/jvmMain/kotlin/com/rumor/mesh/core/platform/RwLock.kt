package com.rumor.mesh.core.platform

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * JVM `actual` for [RwLock]. Wraps `ReentrantReadWriteLock` directly.
 */
actual class RwLock actual constructor() {
    private val lock = ReentrantReadWriteLock()

    actual fun <T> read(block: () -> T): T {
        lock.readLock().lock()
        try { return block() } finally { lock.readLock().unlock() }
    }

    actual fun <T> write(block: () -> T): T {
        lock.writeLock().lock()
        try { return block() } finally { lock.writeLock().unlock() }
    }
}
