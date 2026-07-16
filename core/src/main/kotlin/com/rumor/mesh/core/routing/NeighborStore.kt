package com.rumor.mesh.core.routing

import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.platform.ConcurrentMap

/**
 * In-memory cache of each peer's last-seen message-set overlap fraction.
 *
 * Updated after every gossip exchange: [update] records what fraction of the
 * local node's outbound offer the peer already knew. A value near 1.0 means
 * the peer is already well-informed by other routes; a value near 0.0 means
 * they have little overlap with our known set and are high-value relay targets.
 *
 * [selectDiverse] uses this to rank candidates: it returns the 80% with the
 * lowest overlap (maximising novel reach) plus 20% random exploration to avoid
 * clique ossification where the same tight cluster always exchanges with itself.
 */
class NeighborStore {

    private data class Entry(val overlapFraction: Float, val updatedAtMs: Long)

    private val data = ConcurrentMap<String, Entry>()

    /**
     * Record [overlapFraction] ∈ [0,1] for [peerId] using an exponential
     * moving average (α=0.25) so short spikes don't dominate the score.
     */
    fun update(peerId: String, overlapFraction: Float) {
        val now = SystemClock.now()
        val prev = data[peerId]?.overlapFraction ?: overlapFraction
        val smoothed = prev * 0.75f + overlapFraction * 0.25f
        data[peerId] = Entry(smoothed, now)
    }

    /**
     * Returns up to [limit] peer IDs from [candidates] selected for maximum
     * coverage diversity. Unknown peers are treated as neutral (0.5 overlap).
     *
     * 80% chosen by lowest overlap fraction; 20% chosen at random from the
     * remainder so the node occasionally reaches outside its usual cluster.
     */
    fun selectDiverse(candidates: List<String>, limit: Int): List<String> {
        if (candidates.size <= limit) return candidates
        val scored = candidates.map { id ->
            id to (data[id]?.overlapFraction ?: 0.5f)
        }.sortedBy { it.second }

        // At least one coverage pick so the lowest-overlap peer is always chosen
        // (at limit=1 a coerce to limit-1 = 0 made the single slot pure random
        // exploration, dropping the low-overlap guarantee). Floor keeps one
        // exploration slot alive at small limits (ceil would kill it at 2).
        val coverageCount = (limit * 0.8).toInt().coerceAtLeast(1)
        val explorationCount = limit - coverageCount

        val coverageSet = scored.take(coverageCount).map { it.first }
        val explorationPool = scored.drop(coverageCount).map { it.first }.shuffled()
        return coverageSet + explorationPool.take(explorationCount)
    }

    /** Evict entries older than [olderThanMs]. Call periodically to avoid unbounded growth. */
    fun pruneStale(olderThanMs: Long) {
        val cutoff = SystemClock.now() - olderThanMs
        data.removeIf { _, v -> v.updatedAtMs < cutoff }
    }

    fun overlapFor(peerId: String): Float = data[peerId]?.overlapFraction ?: 0.5f
}
