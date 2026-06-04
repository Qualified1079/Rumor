package com.rumor.mesh.core.platform

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * iOS `actual` for [ConcurrentMap]. Backed by a plain `MutableMap` guarded by
 * atomicfu's cross-platform synchronized block. Reads and writes both take
 * the lock — slower than `ConcurrentHashMap` (which allows lock-free reads)
 * but correct.
 *
 * If profiling shows this is hot, swap for a per-bucket-locked impl or a
 * lock-free hash trie. None of the current callers (BreadcrumbCache,
 * NeighborStore, MessageStore rate-limit buckets) churn enough to matter.
 */
actual class ConcurrentMap<K : Any, V : Any> : SynchronizedObject() {

    private val map = mutableMapOf<K, V>()

    actual constructor() : super()

    actual operator fun get(key: K): V? = synchronized(this) { map[key] }
    actual operator fun set(key: K, value: V) { synchronized(this) { map[key] = value } }
    actual fun put(key: K, value: V): V? = synchronized(this) { map.put(key, value) }
    actual fun putIfAbsent(key: K, value: V): V? = synchronized(this) {
        val prev = map[key]
        if (prev == null) map[key] = value
        prev
    }
    actual fun remove(key: K): V? = synchronized(this) { map.remove(key) }
    actual fun clear() = synchronized(this) { map.clear() }
    actual fun containsKey(key: K): Boolean = synchronized(this) { map.containsKey(key) }
    actual val size: Int get() = synchronized(this) { map.size }
    actual val keys: Set<K> get() = synchronized(this) { map.keys.toSet() }
    actual val values: Collection<V> get() = synchronized(this) { map.values.toList() }
    actual val entries: Set<Map.Entry<K, V>> get() = synchronized(this) { map.entries.toSet() }
    actual fun forEach(action: (K, V) -> Unit) = synchronized(this) { map.forEach(action) }
    actual fun getOrPut(key: K, defaultValue: () -> V): V = synchronized(this) {
        map.getOrPut(key, defaultValue)
    }

    actual fun removeIf(predicate: (K, V) -> Boolean): Int = synchronized(this) {
        var removed = 0
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (predicate(e.key, e.value)) { it.remove(); removed++ }
        }
        removed
    }

    actual fun compute(key: K, remap: (K, V?) -> V?): V? = synchronized(this) {
        val newVal = remap(key, map[key])
        if (newVal != null) map[key] = newVal else map.remove(key)
        newVal
    }
}

/**
 * iOS `actual` for [ConcurrentSet]. Backed by a `MutableSet` guarded by the
 * same atomicfu synchronized primitive.
 */
actual class ConcurrentSet<E : Any> : SynchronizedObject() {

    private val set = mutableSetOf<E>()

    actual constructor() : super()

    actual fun add(element: E): Boolean = synchronized(this) { set.add(element) }
    actual fun remove(element: E): Boolean = synchronized(this) { set.remove(element) }
    actual operator fun contains(element: E): Boolean = synchronized(this) { element in set }
    actual fun clear() = synchronized(this) { set.clear() }
    actual val size: Int get() = synchronized(this) { set.size }
    actual fun forEach(action: (E) -> Unit) = synchronized(this) { set.forEach(action) }
}
