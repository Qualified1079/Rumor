package com.rumor.mesh.core.protocol

/**
 * In-memory LRU dedup cache. Volatile and lossy — restarts start clean.
 * Sized to 0.5% of max heap, clamped to [2_000, 200_000].
 *
 * Separate from MessageStore so they can evolve independently: the dedup cache
 * is hot, write-mostly, and acceptable to lose; the store is persistent and compressed.
 */
class DuplicateFilter {
    private val MIN_CAPACITY = 2_000
    private val MAX_CAPACITY = 200_000
    private val BYTES_PER_ENTRY = 200
    private val HEAP_FRACTION_DENOM = 200

    private val capacity: Int = run {
        val budgetBytes = Runtime.getRuntime().maxMemory() / HEAP_FRACTION_DENOM
        (budgetBytes / BYTES_PER_ENTRY).toInt().coerceIn(MIN_CAPACITY, MAX_CAPACITY)
    }

    private val cache: LinkedHashMap<String, Unit> = object : LinkedHashMap<String, Unit>(
        capacity, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Unit>): Boolean =
            size > capacity
    }

    /** Returns true if [id] is new (not previously seen). Records it either way. */
    @Synchronized
    fun recordAndCheck(id: String): Boolean {
        val isNew = !cache.containsKey(id)
        cache[id] = Unit
        return isNew
    }

    @Synchronized
    fun knownIds(): Set<String> = cache.keys.toSet()

    @Synchronized
    fun size(): Int = cache.size
}
