package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.Breadcrumb
import com.rumor.mesh.core.model.Route
import kotlinx.coroutines.flow.Flow

/**
 * Durable per-peer routing statistics. Each [Route] row is the
 * persistent summary of one direct-peer link: how much we've moved
 * through it, how many sessions succeeded vs failed, when we last
 * saw the peer. Survives restarts (in contrast to
 * [com.rumor.mesh.core.routing.NeighborStore], which is the
 * in-memory view of currently-reachable peers).
 *
 * **Ranking is by `bytesRelayed / (1 + failureCount)`** (G19, O3) —
 * latency intentionally NOT a factor on BLE/Wi-Fi Direct (mostly
 * discovery time, not link quality). [getPreferred] returns the
 * top-K for the gossip scheduler.
 *
 * Three impls in tree (Room / in-memory / tests), same contract.
 */
interface RouteRepository {
    suspend fun upsert(route: Route)
    /** Peers to prefer for gossip — ranked by encounter recency, then session count. */
    suspend fun getPreferred(limit: Int = 20): List<Route>
    fun observeAll(): Flow<List<Route>>
    suspend fun getForPeer(peerId: String): Route?
    suspend fun pruneStale(olderThanMs: Long)
    suspend fun delete(peerId: String)
}

/**
 * O29 Tier-1 routing substrate, persisted. A breadcrumb records
 * "I received a signed message from [Breadcrumb.targetUserId] via
 * peer [Breadcrumb.arrivedFromPeerId] at [Breadcrumb.recordedAtMs]"
 * — local information, zero broadcast leak (per O58 Tier 1). When
 * the local node later wants to DM that target, it consults
 * [getCandidates] for a top-K of recent inbound peers and hands the
 * DM to those rather than flooding.
 *
 * Mirror is the in-memory [com.rumor.mesh.core.routing.BreadcrumbCache];
 * this repo is the persistent surface so the routing knowledge
 * survives restart. Pruning is the caller's responsibility — see
 * [pruneForTarget] (when a target rotates out) and [pruneOld]
 * (windowed decay).
 */
interface BreadcrumbRepository {
    suspend fun upsert(crumb: Breadcrumb)
    suspend fun getLatest(targetUserId: String): Breadcrumb?
    /**
     * Up to [limit] ranked candidates for routing toward [targetUserId], most
     * recent first. Used by O29 next-hop selection to hand a DM to top-K peers
     * for robustness rather than top-1. Empty when no breadcrumbs exist.
     */
    suspend fun getCandidates(targetUserId: String, limit: Int = 3): List<Breadcrumb>
    suspend fun pruneForTarget(targetUserId: String)
    suspend fun pruneOld(olderThanMs: Long)
}
