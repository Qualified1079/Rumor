package com.rumor.mesh.core.platform

import java.util.concurrent.ConcurrentHashMap


/**
 * Platform-agnostic concurrent map. Read-heavy / write-occasional callers
 * in Rumor (BreadcrumbCache, NeighborStore, MessageStore rate-limit buckets)
 * use this so they can stay in `commonMain`.
 *
 * JVM `actual` typealiases to `java.util.concurrent.ConcurrentHashMap` — no
 * overhead. Non-JVM `actual`s use a Mutex-protected MutableMap; concurrent
 * reads serialize, which is slower than CHM but still correct. Profile-and-
 * specialize per-platform if any caller becomes hot.
 *
 * Phase 1c shim per docs/IOS_PORT_PHASE_1_HANDOFF.md.
 */
/**
 * JVM `actual` for [ConcurrentMap]. Backs onto `ConcurrentHashMap` directly;
 * the wrapper adds zero overhead.
 */
class ConcurrentMap<K : Any, V : Any> constructor() {
    private val map = ConcurrentHashMap<K, V>()

    operator fun get(key: K): V? = map[key]
    operator fun set(key: K, value: V) { map[key] = value }
    fun put(key: K, value: V): V? = map.put(key, value)
    fun putIfAbsent(key: K, value: V): V? = map.putIfAbsent(key, value)
    fun remove(key: K): V? = map.remove(key)
    fun clear() = map.clear()
    fun containsKey(key: K): Boolean = map.containsKey(key)
    val size: Int get() = map.size
    val keys: Set<K> get() = map.keys
    val values: Collection<V> get() = map.values
    val entries: Set<Map.Entry<K, V>> get() = map.entries
    fun forEach(action: (K, V) -> Unit) = map.forEach(action)
    fun getOrPut(key: K, defaultValue: () -> V): V = map.getOrPut(key, defaultValue)

    fun removeIf(predicate: (K, V) -> Boolean): Int {
        var removed = 0
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (predicate(e.key, e.value)) { it.remove(); removed++ }
        }
        return removed
    }

    fun compute(key: K, remap: (K, V?) -> V?): V? = map.compute(key, remap)
}

/** JVM for [ConcurrentSet] — uses `ConcurrentHashMap.newKeySet()`. */
class ConcurrentSet<E : Any> constructor() {
    private val set: MutableSet<E> = ConcurrentHashMap.newKeySet()
    fun add(element: E): Boolean = set.add(element)
    fun remove(element: E): Boolean = set.remove(element)
    operator fun contains(element: E): Boolean = element in set
    fun clear() = set.clear()
    val size: Int get() = set.size
    fun forEach(action: (E) -> Unit) = set.forEach(action)
}
