package com.rumor.mesh.core.routing

import com.rumor.mesh.core.data.BreadcrumbRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Breadcrumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tracks which peer a ping for a given target User ID arrived from.
 * Used to route return messages toward a target without carrying the full path.
 * Capped at 5 hops per target; backed by persistent storage.
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

    fun pruneOld() {
        scope.launch {
            breadcrumbRepo.pruneOld(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
        }
    }
}
