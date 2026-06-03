package com.rumor.mesh.core.protocol

/**
 * In-memory LRU dedup cache. Volatile and lossy — restarts start clean.
 * Sized to 0.5% of max heap, clamped to [2_000, 200_000].
 *
 * Separate from MessageStore so they can evolve independently: the dedup cache
 * is hot, write-mostly, and acceptable to lose; the store is persistent and compressed.
 *
 * Bandwidth-bound under a TTL-extender attack: the dedup check gates BEFORE
 * relay enqueue (see `GossipEngine.processIncoming` line ~623, `if (!isNew) return`),
 * so duplicates never re-fan-out. Attacker resending an already-seen message id
 * costs only O(attacker_immediate_neighbors) wire-receives per resend — bounded
 * by the LRU retention window and the per-sender O16 token bucket. The eventual
 * Tier-1 RAM-bloom layer (O85 in CLAUDE.md) closes the "id evicts from LRU →
 * re-floodable" window by remembering essentially every id ever seen at the
 * cost of a 0.1% false-positive rate that gossip-exchange / RBSR catches.
 *
 * Disk persistence intentionally NOT added here: flash wear, forensic surface,
 * and incompatibility with MCU-class relays (O75) outweigh the crash-recovery
 * benefit. See O85 for the agreed two-tier RAM design.
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
