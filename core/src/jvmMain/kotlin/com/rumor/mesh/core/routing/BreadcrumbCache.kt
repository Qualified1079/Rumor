package com.rumor.mesh.core.routing

import com.rumor.mesh.core.data.BreadcrumbRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Breadcrumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Tier-1 routing substrate (O29). Records "I received a message from
 * [targetUserId] via this peer recently, so for future messages addressed
 * TO [targetUserId], this peer is a plausible next-hop." Purely local —
 * never gossiped, no marginal privacy cost beyond the gossip baseline.
 *
 * Capped at 5 entries per target (the freshest crumbs); pruned at 24h
 * of inactivity. Backed by persistent storage so breadcrumbs survive an
 * app restart — important for the long-term-collapse threat model where
 * an offline-for-days return path is still useful.
 */
class BreadcrumbCache(
    private val breadcrumbRepo: BreadcrumbRepository,
) {
    private val TAG = "BreadcrumbCache"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * In-memory mirror of the persistent breadcrumb store, keyed by target
     * userId → ordered list of recent peer userIds (freshest first, capped).
     * Lets non-suspend callers (`messagesForExchange`, scheduler bias logic)
     * consult breadcrumbs synchronously. Updated on every [record]; survives
     * the lifetime of this process. Cold-start after app launch: empty until
     * something arrives.
     */
    private val snapshot = ConcurrentHashMap<String, List<String>>()
    private val SNAPSHOT_LIMIT = 5

    fun record(targetUserId: String, fromPeerId: String, hopCount: Int = 1) {
        // Update the synchronous snapshot first so callers see the change
        // immediately, even before the persistent upsert finishes.
        snapshot.compute(targetUserId) { _, existing ->
            val deduped = (listOf(fromPeerId) + (existing ?: emptyList()).filter { it != fromPeerId })
            deduped.take(SNAPSHOT_LIMIT)
        }
        scope.launch {
            val crumb = Breadcrumb(
                targetUserId = targetUserId,
                arrivedFromPeerId = fromPeerId,
                hopCount = hopCount,
                recordedAtMs = System.currentTimeMillis(),
            )
            breadcrumbRepo.upsert(crumb)
            breadcrumbRepo.pruneForTarget(targetUserId)
            RumorLog.d(TAG, "Crumb: ${targetUserId.take(8)}… ← ${fromPeerId.take(8)}…")
        }
    }

    /**
     * Synchronous non-suspend candidates lookup against the in-memory
     * [snapshot]. For use from hot non-suspend code paths like
     * [com.rumor.mesh.core.protocol.GossipEngine.messagesForExchange]. Cold
     * cache returns empty list. The persistent [candidatePeers] is the
     * ground truth across restarts.
     */
    fun candidatePeersSync(targetUserId: String, limit: Int = 3): List<String> =
        snapshot[targetUserId]?.take(limit) ?: emptyList()

    suspend fun nextHop(targetUserId: String): String? =
        breadcrumbRepo.getLatest(targetUserId)?.arrivedFromPeerId

    /**
     * Up to [limit] candidate peers to prefer when handing off a DM
     * addressed to [targetUserId], freshest first. Returns empty when we
     * have no breadcrumbs for the target — signals the caller to fall back
     * to flood. Top-K (default 3) is more robust than top-1 against any
     * single peer being temporarily unreachable.
     */
    suspend fun candidatePeers(targetUserId: String, limit: Int = 3): List<String> =
        breadcrumbRepo.getCandidates(targetUserId, limit).map { it.arrivedFromPeerId }

    fun pruneOld() {
        scope.launch {
            breadcrumbRepo.pruneOld(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
        }
    }
}
