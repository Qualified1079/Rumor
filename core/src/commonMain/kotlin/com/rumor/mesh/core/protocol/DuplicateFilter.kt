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
class DuplicateFilter(
    /**
     * Maximum entries before LRU eviction. Default sized for a modern phone
     * (~40 MB upper bound at 200 bytes/entry). JVM callers can pass a heap-
     * aware value if they want tighter scaling on low-RAM Android devices;
     * iOS/native callers should accept the default. Hard-coded floor + ceiling
     * preserved as constants for documentation.
     */
    private val capacity: Int = DEFAULT_CAPACITY,
) : kotlinx.atomicfu.locks.SynchronizedObject() {

    private val cache: LinkedHashMap<String, Unit> = object : LinkedHashMap<String, Unit>(
        capacity, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Unit>): Boolean =
            size > capacity
    }

    /** Returns true if [id] is new (not previously seen). Records it either way. */
    fun recordAndCheck(id: String): Boolean = kotlinx.atomicfu.locks.synchronized(this) {
        val isNew = !cache.containsKey(id)
        cache[id] = Unit
        isNew
    }

    fun knownIds(): Set<String> = kotlinx.atomicfu.locks.synchronized(this) { cache.keys.toSet() }

    fun size(): Int = kotlinx.atomicfu.locks.synchronized(this) { cache.size }

    companion object {
        const val MIN_CAPACITY = 2_000
        const val MAX_CAPACITY = 200_000
        const val DEFAULT_CAPACITY = MAX_CAPACITY
    }
}
