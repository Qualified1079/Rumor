package com.rumor.mesh.core.protocol

import java.util.LinkedHashMap

/**
 * In-memory size-bounded LRU cache of seen message IDs.
 *
 * Capacity is sized from the JVM heap at construction so devices with more memory
 * keep more history without manual tuning. Each entry costs roughly 200 bytes
 * (32-char hex key + LinkedHashMap node), so we budget ~0.5% of max heap.
 * Bounded to a sane range to avoid extremes on tiny or very large heaps.
 */
class DuplicateFilter() {

    private val MIN_CAPACITY = 2_000
    private val MAX_CAPACITY = 200_000
    private val BYTES_PER_ENTRY = 200
    private val HEAP_FRACTION_DENOM = 200  // 1/200 of max heap = 0.5%

    @Volatile private var capacity = run {
        val budgetBytes = Runtime.getRuntime().maxMemory() / HEAP_FRACTION_DENOM
        (budgetBytes / BYTES_PER_ENTRY).toInt().coerceIn(MIN_CAPACITY, MAX_CAPACITY)
    }

    private val cache: LinkedHashMap<String, Unit> = object : LinkedHashMap<String, Unit>(
        capacity, 0.75f, true  // accessOrder = true → LRU
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > capacity
    }

    @Synchronized
    fun isSeen(id: String): Boolean = cache.containsKey(id)

    /** Returns true if this is a new (unseen) message. */
    @Synchronized
    fun recordAndCheck(id: String): Boolean {
        if (cache.containsKey(id)) return false
        cache[id] = Unit
        return true
    }

    @Synchronized
    fun setCapacity(newCapacity: Int) {
        capacity = newCapacity
    }

    @Synchronized
    fun knownIds(): Set<String> = LinkedHashSet(cache.keys)

    @Synchronized
    fun size(): Int = cache.size
}
