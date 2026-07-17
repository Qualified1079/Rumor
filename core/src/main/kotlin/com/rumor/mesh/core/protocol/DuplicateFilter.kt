package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.transport.wifidirect.BloomFilterData

/**
 * Two-tier in-memory dedup cache (O85). Volatile and lossy — restarts start clean.
 *
 * **Tier 0 (exact):** LinkedHashMap LRU bounded to [capacity] entries. Holds the
 * recent N message ids with no false positives. Default 200k entries
 * (~40 MB at 200 bytes/entry).
 *
 * **Tier 1 (probabilistic):** Bloom filter over the long-tail of evicted ids.
 * When Tier 0 evicts an entry, its id is pushed into the bloom. On lookup,
 * if Tier 0 misses we consult the bloom; a hit means "probably seen, drop";
 * a miss means "definitely new, accept and relay". The small false-positive
 * rate (~0.1%) is caught by the gossip / RBSR exchange path — a falsely-
 * dropped fresh message is re-offered by the next peer that has it, and
 * exchange compares ID sets so the missing one gets re-delivered. Worst-case
 * latency for the rare FP: one exchange period (tens of seconds).
 *
 * Net effect: effective dedup memory grows from "last 200k seen" to
 * "essentially every id ever seen for the lifetime of the process" at the
 * cost of ~1.8 MB additional RAM for the bloom + a small FP rate.
 *
 * Both tiers are RAM-only; restart clears them. Bandwidth bound under
 * TTL-extender attack is unchanged (dedup still gates BEFORE relay enqueue
 * in `GossipEngine.processIncoming`); the Tier 1 bloom just closes the
 * window where an attacker could re-flood an id after Tier 0 eviction.
 *
 * Disk persistence intentionally NOT added: flash wear, forensic surface,
 * and incompatibility with MCU-class relays (O75) outweigh the crash-recovery
 * benefit.
 */
class DuplicateFilter(
    /**
     * Tier 0 capacity — max exact entries before LRU eviction. Default sized for
     * a modern phone (~40 MB upper bound at 200 bytes/entry). JVM callers can
     * pass a heap-aware value if they want tighter scaling on low-RAM devices;
     * iOS/native callers should accept the default.
     */
    private val capacity: Int = DEFAULT_CAPACITY,
    /**
     * Tier 1 bloom capacity — how many evicted ids the bloom is sized to hold
     * at the chosen false-positive rate. Default 1M entries gives ~1.8 MB
     * bloom at 0.1% FP. Set to 0 to disable Tier 1 (back to single-tier
     * exact-only behavior).
     */
    private val longTailCapacity: Int = DEFAULT_LONG_TAIL_CAPACITY,
    /**
     * Tier 1 bloom false-positive rate. 0.001 = 0.1% — one falsely-dropped
     * fresh message in 1000. Each FP is recoverable via the gossip-exchange
     * path with one-exchange-period of latency, so tightening below 0.001
     * isn't worth the RAM.
     */
    private val longTailFalsePositiveRate: Double = 0.001,
) : kotlinx.atomicfu.locks.SynchronizedObject() {

    private val cache: LinkedHashMap<String, Unit> = object : LinkedHashMap<String, Unit>(
        capacity, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Unit>): Boolean {
            if (size > capacity) {
                // Push the evicted id into Tier 1 before it's gone. If Tier 1
                // is disabled (longTail == null) this is a no-op.
                longTail?.add(eldest.key)
                return true
            }
            return false
        }
    }

    /**
     * Tier 1 bloom. Null when [longTailCapacity] is 0. Use
     * [BloomFilterData.deserializeOrNull]-style graceful handling NOT needed
     * here — we control the construction parameters, no adversarial input.
     */
    private val longTail: BloomFilterData? =
        if (longTailCapacity > 0)
            BloomFilterData(longTailCapacity, longTailFalsePositiveRate)
        else null

    /**
     * Bulk-seed previously-seen ids (O92). Called once on mesh start to restore
     * an accurate "what we hold" summary from the durable store after the volatile
     * cache reset on restart. Ids beyond [capacity] are evicted LRU as usual (into
     * Tier 1), so the freshest-first order the caller passes decides what survives
     * in the exact tier.
     */
    fun seed(ids: Collection<String>) = kotlinx.atomicfu.locks.synchronized(this) {
        for (id in ids) cache[id] = Unit
    }

    /**
     * Check-only: true if [id] has probably been seen, WITHOUT recording it.
     * Used by the ingest path to test for a duplicate before signature
     * verification — a check that commits no "seen" state, so a later forgery
     * carrying a genuine message's id can't blackhole it (the §2 fix). Does not
     * bump LRU order (a bare lookup isn't an access).
     */
    fun mightBeSeen(id: String): Boolean = kotlinx.atomicfu.locks.synchronized(this) {
        cache.containsKey(id) || longTail?.mightContain(id) == true
    }

    /** Returns true if [id] is new (not previously seen). Records it either way. */
    fun recordAndCheck(id: String): Boolean = kotlinx.atomicfu.locks.synchronized(this) {
        // Tier 0: exact LRU check.
        if (cache.containsKey(id)) {
            // Re-put to bump LRU order (containsKey alone doesn't count as
            // access; only put / get do per LinkedHashMap docs).
            cache[id] = Unit
            return@synchronized false
        }
        // Tier 1: bloom check for long-tail seen ids.
        if (longTail?.mightContain(id) == true) {
            // Probably seen; accept the small FP rate. Don't insert into Tier 0
            // — would compete with genuinely recent entries.
            return@synchronized false
        }
        // Genuinely new. Insert into Tier 0; eviction (if any) pushes the
        // displaced id into Tier 1 via removeEldestEntry above.
        cache[id] = Unit
        return@synchronized true
    }

    fun knownIds(): Set<String> = kotlinx.atomicfu.locks.synchronized(this) { cache.keys.toSet() }

    fun size(): Int = kotlinx.atomicfu.locks.synchronized(this) { cache.size }

    companion object {
        const val MIN_CAPACITY = 2_000
        const val MAX_CAPACITY = 200_000
        const val DEFAULT_CAPACITY = MAX_CAPACITY

        /** Default Tier 1 capacity — 1M evicted-id slots at 0.1% FP ≈ 1.8 MB. */
        const val DEFAULT_LONG_TAIL_CAPACITY = 1_000_000
    }
}
