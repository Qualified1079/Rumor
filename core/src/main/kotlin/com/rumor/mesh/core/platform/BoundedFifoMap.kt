package com.rumor.mesh.core.platform

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Thread-safe FIFO-bounded map. New entries evict the oldest insert when
 * [capacity] would be exceeded. Lookup never blocks. Put / remove serialize
 * via an atomicfu cross-platform synchronized block.
 *
 * Used for `GossipEngine.sentDmPlaintext`: a bounded cache of plaintexts the
 * local node composed, keyed by message id, that the UI consults to render
 * "[sent] X" without storing plaintext on the wire or in long-term storage.
 *
 * Semantics:
 *  - put(k, v): replaces existing entry (no order change), or inserts new
 *    entry at tail (evicting head if over capacity).
 *  - get(k): no lock — reads from the underlying ConcurrentMap directly.
 *
 * Insertion order via a side ArrayDeque<K> protected by the same monitor.
 */
class BoundedFifoMap<K : Any, V : Any>(private val capacity: Int) : SynchronizedObject() {

    private val map = ConcurrentMap<K, V>()
    private val order = ArrayDeque<K>()

    operator fun get(key: K): V? = map[key]

    fun put(key: K, value: V): V? = synchronized(this) {
        val prev = map.put(key, value)
        if (prev == null) {
            order.addLast(key)
            while (order.size > capacity) {
                val evicted = order.removeFirst()
                map.remove(evicted)
            }
        }
        prev
    }

    fun remove(key: K): V? = synchronized(this) {
        val prev = map.remove(key)
        if (prev != null) order.remove(key)
        prev
    }

    val size: Int get() = map.size
}
