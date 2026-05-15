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
private val STALE_THRESHOLD_MS = 7 * 24 * 60 * 60 * 1000L

class TopologyTracker(
    private val routeRepo: RouteRepository,
    private val neighborStore: NeighborStore = NeighborStore(),
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Record a completed exchange.
     *
     * [latencyMs] is stored as a diagnostic only — not used for ranking because
     * on BLE/Wi-Fi Direct it mostly measures discovery timing, not route quality.
     *
     * [bytesTransferred] accumulates into [Route.bytesRelayed], which drives the
     * primary ranking in [preferredPeers]: high-throughput peers are preferred over
     * peers that rarely exchange substantial data.
     *
     * [overlapFraction] (0–1) records what fraction of the local node's outbound
     * message offer the peer already knew. Stored in [NeighborStore] for
     * diversity-aware relay target selection via [selectDiversePeers].
     */
    fun recordSession(
        peerId: String,
        latencyMs: Long,
        hopCount: Int,
        bytesTransferred: Long = 0,
        overlapFraction: Float = 0.5f,
    ) {
        scope.launch {
            val existing = routeRepo.getForPeer(peerId)
            val newRoute = if (existing == null) {
                Route(peerId, latencyMs, hopCount, System.currentTimeMillis(), 1, bytesTransferred)
            } else {
                val smoothed = (existing.latencyMs * 7 + latencyMs) / 8
                Route(
                    peerId,
                    smoothed,
                    hopCount,
                    System.currentTimeMillis(),
                    existing.sessionCount + 1,
                    existing.bytesRelayed + bytesTransferred,
                )
            }
            routeRepo.upsert(newRoute)
            neighborStore.update(peerId, overlapFraction)
            RumorLog.d(TAG, "Session with ${peerId.take(8)}… " +
                "bytes=${bytesTransferred} overlap=%.2f".format(overlapFraction))
        }
    }

    /**
     * Peers to prefer for gossip — ranked by cumulative bytes transferred then
     * session count; latency is not used (see [recordSession]).
     */
    suspend fun preferredPeers(limit: Int = 20): List<String> =
        routeRepo.getPreferred(limit).map { it.peerId }

    /** EMA overlap fraction for [peerId], or 0.5 (neutral) if unknown. */
    fun overlapFor(peerId: String): Float = neighborStore.overlapFor(peerId)

    /**
     * Select up to [limit] peers from [candidates] to maximise novel message
     * reach. Uses [NeighborStore] diversity scoring: 80% lowest-overlap peers
     * (they know least of what we know → most valuable relay targets) plus 20%
     * random exploration to prevent clique ossification.
     *
     * Falls back to [preferredPeers] when [candidates] is empty.
     */
    suspend fun selectDiversePeers(
        candidates: List<String>,
        limit: Int = 5,
    ): List<String> {
        val pool = candidates.ifEmpty { preferredPeers(limit * 4) }
        return neighborStore.selectDiverse(pool, limit)
    }

    fun observeRoutes(): Flow<List<Route>> = routeRepo.observeAll()

    fun pruneStale() {
        scope.launch {
            routeRepo.pruneStale(System.currentTimeMillis() - STALE_THRESHOLD_MS)
            neighborStore.pruneStale(STALE_THRESHOLD_MS)
        }
    }
}
