package com.rumor.mesh.core.platform

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
expect class ConcurrentMap<K : Any, V : Any>() {
    operator fun get(key: K): V?
    operator fun set(key: K, value: V)
    fun put(key: K, value: V): V?
    fun putIfAbsent(key: K, value: V): V?
    fun remove(key: K): V?
    fun clear()
    fun containsKey(key: K): Boolean
    val size: Int
    val keys: Set<K>
    val values: Collection<V>
    val entries: Set<Map.Entry<K, V>>
    fun forEach(action: (K, V) -> Unit)
    fun getOrPut(key: K, defaultValue: () -> V): V

    /** Remove every entry where [predicate] holds. Returns the count removed. */
    fun removeIf(predicate: (K, V) -> Boolean): Int

    /**
     * Atomic compute. `remap` receives the current value (or null if absent)
     * and returns the new value (or null to remove). Mirrors
     * `java.util.concurrent.ConcurrentHashMap.compute`.
     */
    fun compute(key: K, remap: (K, V?) -> V?): V?
}

/**
 * Sibling of [ConcurrentMap] for cases that need a concurrent set. Backed by
 * a ConcurrentMap<E, Unit> on JVM (matches `ConcurrentHashMap.newKeySet()`).
 */
expect class ConcurrentSet<E : Any>() {
    fun add(element: E): Boolean
    fun remove(element: E): Boolean
    operator fun contains(element: E): Boolean
    fun clear()
    val size: Int
    fun forEach(action: (E) -> Unit)
}
