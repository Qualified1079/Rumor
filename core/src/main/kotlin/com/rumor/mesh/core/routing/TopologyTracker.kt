package com.rumor.mesh.core.routing

import com.rumor.mesh.core.data.RouteRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val TAG = "TopologyTracker"
private val STALE_THRESHOLD_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

class TopologyTracker(
    private val routeRepo: RouteRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun recordSession(peerId: String, latencyMs: Long, hopCount: Int) {
        scope.launch {
            val existing = routeRepo.getForPeer(peerId)
            val newRoute = if (existing == null) {
                Route(peerId, latencyMs, hopCount, System.currentTimeMillis(), 1)
            } else {
                val smoothed = (existing.latencyMs * 7 + latencyMs) / 8
                Route(peerId, smoothed, hopCount, System.currentTimeMillis(), existing.sessionCount + 1)
            }
            routeRepo.upsert(newRoute)
            RumorLog.d(TAG, "Recorded session with ${peerId.take(8)}… latency=${latencyMs}ms")
        }
    }

    suspend fun preferredPeers(limit: Int = 20): List<String> =
        routeRepo.getFastest(limit).map { it.peerId }

    fun observeRoutes(): Flow<List<Route>> = routeRepo.observeAll()

    fun pruneStale() {
        scope.launch {
            routeRepo.pruneStale(System.currentTimeMillis() - STALE_THRESHOLD_MS)
        }
    }
}
