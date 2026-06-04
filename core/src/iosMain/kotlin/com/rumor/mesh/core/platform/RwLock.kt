package com.rumor.mesh.core.platform

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * iOS `actual` for [RwLock]. Backed by a single atomicfu synchronized
 * monitor — both `read` and `write` take exclusive access. Functionally
 * correct but slower than the JVM ReentrantReadWriteLock on read-heavy
 * workloads.
 *
 * Current callers are RumorLog and a few diagnostic readers; none are
 * profile-hot enough to need a real RW distinction. Replace with a Darwin
 * `os_unfair_lock` or `pthread_rwlock_t` cinterop wrapper if profiling
 * later shows contention.
 */
actual class RwLock : SynchronizedObject() {
    actual constructor() : super()

    actual fun <T> read(block: () -> T): T = synchronized(this) { block() }
    actual fun <T> write(block: () -> T): T = synchronized(this) { block() }
}
