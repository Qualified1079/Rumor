package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.Breadcrumb
import com.rumor.mesh.core.model.Route
import kotlinx.coroutines.flow.Flow

interface RouteRepository {
    suspend fun upsert(route: Route)
    /** Peers to prefer for gossip — ranked by encounter recency, then session count. */
    suspend fun getPreferred(limit: Int = 20): List<Route>
    fun observeAll(): Flow<List<Route>>
    suspend fun getForPeer(peerId: String): Route?
    suspend fun pruneStale(olderThanMs: Long)
    suspend fun delete(peerId: String)
}

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
