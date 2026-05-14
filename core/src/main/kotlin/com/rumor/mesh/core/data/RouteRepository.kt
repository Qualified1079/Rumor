package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.Breadcrumb
import com.rumor.mesh.core.model.Route
import kotlinx.coroutines.flow.Flow

interface RouteRepository {
    suspend fun upsert(route: Route)
    suspend fun getFastest(limit: Int = 20): List<Route>
    fun observeAll(): Flow<List<Route>>
    suspend fun getForPeer(peerId: String): Route?
    suspend fun pruneStale(olderThanMs: Long)
    suspend fun delete(peerId: String)
}

interface BreadcrumbRepository {
    suspend fun upsert(crumb: Breadcrumb)
    suspend fun getLatest(targetUserId: String): Breadcrumb?
    suspend fun pruneForTarget(targetUserId: String)
    suspend fun pruneOld(olderThanMs: Long)
}
