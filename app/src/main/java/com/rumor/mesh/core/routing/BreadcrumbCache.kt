package com.rumor.mesh.core.routing

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.data.BreadcrumbDao
import com.rumor.mesh.data.BreadcrumbEntity
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
    private val breadcrumbDao: BreadcrumbDao,
) {
    private val TAG = "BreadcrumbCache"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Record that a message for [targetUserId] arrived from [fromPeerId].
     * Persists the crumb and prunes old ones to maintain the 5-hop cap.
     */
    fun record(targetUserId: String, fromPeerId: String, hopCount: Int = 1) {
        scope.launch {
            val crumb = BreadcrumbEntity(
                targetUserId = targetUserId,
                arrivedFromPeerId = fromPeerId,
                hopCount = hopCount,
                recordedAtMs = System.currentTimeMillis(),
            )
            breadcrumbDao.upsert(crumb)
            breadcrumbDao.pruneForTarget(targetUserId)
            RumorLog.d(TAG, "Crumb: ${targetUserId.take(8)}… ← ${fromPeerId.take(8)}…")
        }
    }

    /** Returns the most likely next hop toward [targetUserId], or null if unknown. */
    suspend fun nextHop(targetUserId: String): String? =
        breadcrumbDao.getLatest(targetUserId)?.arrivedFromPeerId

    /** Prune breadcrumbs older than 24h. */
    fun pruneOld() {
        scope.launch {
            breadcrumbDao.pruneOld(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
        }
    }
}
