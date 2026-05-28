package com.rumor.mesh.core.routing

import com.rumor.mesh.core.data.BreadcrumbRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Breadcrumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    fun record(targetUserId: String, fromPeerId: String, hopCount: Int = 1) {
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
