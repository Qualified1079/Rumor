package com.rumor.mesh.core.platform

import java.util.concurrent.ConcurrentHashMap

/**
 * JVM `actual` for [ConcurrentMap]. Backs onto `ConcurrentHashMap` directly;
 * the wrapper adds zero overhead.
 */
actual class ConcurrentMap<K : Any, V : Any> actual constructor() {
    private val map = ConcurrentHashMap<K, V>()

    actual operator fun get(key: K): V? = map[key]
    actual operator fun set(key: K, value: V) { map[key] = value }
    actual fun put(key: K, value: V): V? = map.put(key, value)
    actual fun putIfAbsent(key: K, value: V): V? = map.putIfAbsent(key, value)
    actual fun remove(key: K): V? = map.remove(key)
    actual fun clear() = map.clear()
    actual fun containsKey(key: K): Boolean = map.containsKey(key)
    actual val size: Int get() = map.size
    actual val keys: Set<K> get() = map.keys
    actual val values: Collection<V> get() = map.values
    actual val entries: Set<Map.Entry<K, V>> get() = map.entries
    actual fun forEach(action: (K, V) -> Unit) = map.forEach(action)
    actual fun getOrPut(key: K, defaultValue: () -> V): V = map.getOrPut(key, defaultValue)

    actual fun removeIf(predicate: (K, V) -> Boolean): Int {
        var removed = 0
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (predicate(e.key, e.value)) { it.remove(); removed++ }
        }
        return removed
    }

    actual fun compute(key: K, remap: (K, V?) -> V?): V? = map.compute(key, remap)
}

/** JVM actual for [ConcurrentSet] — uses `ConcurrentHashMap.newKeySet()`. */
actual class ConcurrentSet<E : Any> actual constructor() {
    private val set: MutableSet<E> = ConcurrentHashMap.newKeySet()
    actual fun add(element: E): Boolean = set.add(element)
    actual fun remove(element: E): Boolean = set.remove(element)
    actual operator fun contains(element: E): Boolean = element in set
    actual fun clear() = set.clear()
    actual val size: Int get() = set.size
    actual fun forEach(action: (E) -> Unit) = set.forEach(action)
}
