package com.rumor.mesh.core.routing

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.data.RouteDao
import com.rumor.mesh.data.RouteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Learns network topology passively from gossip session timing.
 * Each completed session provides latency and hop data for free.
 * Over time, each node builds a picture of which routes are fastest.
 * No extra traffic required — data piggybacks on normal message exchange.
 */
@Singleton
class TopologyTracker @Inject constructor(
    private val routeDao: RouteDao,
) {
    private val TAG = "TopologyTracker"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Record metrics from a completed gossip session. */
    fun recordSession(peerId: String, latencyMs: Long, hopCount: Int) {
        scope.launch {
            val existing = routeDao.getForPeer(peerId)
            val updated = if (existing == null) {
                RouteEntity(
                    peerId = peerId,
                    latencyMs = latencyMs,
                    hopCount = hopCount,
                    lastUpdatedMs = System.currentTimeMillis(),
                    sessionCount = 1,
                )
            } else {
                // Exponential moving average: new = 0.3 * sample + 0.7 * existing
                val smoothedLatency = (0.3 * latencyMs + 0.7 * existing.latencyMs).toLong()
                existing.copy(
                    latencyMs = smoothedLatency,
                    hopCount = minOf(existing.hopCount, hopCount),
                    lastUpdatedMs = System.currentTimeMillis(),
                    sessionCount = existing.sessionCount + 1,
                )
            }
            routeDao.upsert(updated)
            RumorLog.d(TAG, "Route ${peerId.take(8)}…: ${updated.latencyMs}ms, ${updated.hopCount} hops")
        }
    }

    /** Returns peer IDs ranked by ascending latency. Prefer these for gossip exchanges. */
    suspend fun preferredPeers(limit: Int = 20): List<String> =
        routeDao.getFastest(limit).map { it.peerId }

    fun observeRoutes(): Flow<List<RouteEntity>> = routeDao.observeAll()

    /** Prune routes not seen in the last 7 days. */
    fun pruneStale() {
        scope.launch {
            routeDao.pruneStale(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)
        }
    }
}
