package com.rumor.mesh.core.protocol

import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory size-bounded LRU cache of seen message IDs.
 * Size-based eviction (not time-based): self-adjusts to device storage capacity.
 * Thread-safe.
 */
@Singleton
class DuplicateFilter @Inject constructor() {

    private val DEFAULT_CAPACITY = 10_000

    @Volatile private var capacity = DEFAULT_CAPACITY

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
