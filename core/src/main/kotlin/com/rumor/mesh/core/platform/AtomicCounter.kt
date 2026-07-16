package com.rumor.mesh.core.platform

import kotlinx.atomicfu.atomic

/**
 * Platform-agnostic 64-bit atomic counter, used across the protocol for
 * sequence numbers, sig-failure counters, ingest counts, and similar
 * lock-free-or-bust state.
 *
 * Backed by `kotlinx.atomicfu` so it works on every KMP target without
 * expect/actual indirection. atomicfu's JVM compile-time transform lowers
 * this to a `java.util.concurrent.atomic.AtomicLong` so there's no overhead
 * vs the original Java type.
 *
 * Phase 1c shim per docs/IOS_PORT_PHASE_1_HANDOFF.md.
 */
class AtomicCounter(initial: Long = 0L) {
    private val a = atomic(initial)

    var value: Long
        get() = a.value
        set(v) { a.value = v }

    fun get(): Long = a.value
    fun set(v: Long) { a.value = v }
    fun incrementAndGet(): Long = a.incrementAndGet()
    fun getAndIncrement(): Long = a.getAndIncrement()
    fun addAndGet(delta: Long): Long = a.addAndGet(delta)
    fun compareAndSet(expected: Long, new: Long): Boolean = a.compareAndSet(expected, new)
}
