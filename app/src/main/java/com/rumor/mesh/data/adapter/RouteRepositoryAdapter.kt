package com.rumor.mesh.data.adapter

import com.rumor.mesh.core.data.BreadcrumbRepository
import com.rumor.mesh.core.data.RouteRepository
import com.rumor.mesh.core.model.Breadcrumb
import com.rumor.mesh.core.model.Route
import com.rumor.mesh.data.BreadcrumbDao
import com.rumor.mesh.data.BreadcrumbEntity
import com.rumor.mesh.data.RouteDao
import com.rumor.mesh.data.RouteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RouteRepositoryAdapter(private val dao: RouteDao) : RouteRepository {
    override suspend fun upsert(route: Route) = dao.upsert(route.toEntity())
    override suspend fun getPreferred(limit: Int): List<Route> = dao.getPreferred(limit).map(RouteEntity::toModel)
    override fun observeAll(): Flow<List<Route>> = dao.observeAll().map { it.map(RouteEntity::toModel) }
    override suspend fun getForPeer(peerId: String): Route? = dao.getForPeer(peerId)?.toModel()
    override suspend fun pruneStale(olderThanMs: Long) = dao.pruneStale(olderThanMs)
    override suspend fun delete(peerId: String) = dao.delete(peerId)
}

class BreadcrumbRepositoryAdapter(private val dao: BreadcrumbDao) : BreadcrumbRepository {
    override suspend fun upsert(crumb: Breadcrumb) = dao.upsert(crumb.toEntity())
    override suspend fun getLatest(targetUserId: String): Breadcrumb? = dao.getLatest(targetUserId)?.toModel()
    override suspend fun pruneForTarget(targetUserId: String) = dao.pruneForTarget(targetUserId)
    override suspend fun pruneOld(olderThanMs: Long) = dao.pruneOld(olderThanMs)
}

private fun Route.toEntity() = RouteEntity(peerId, latencyMs, hopCount, lastUpdatedMs, sessionCount)
private fun RouteEntity.toModel() = Route(peerId, latencyMs, hopCount, lastUpdatedMs, sessionCount)

private fun Breadcrumb.toEntity() = BreadcrumbEntity(
    targetUserId = targetUserId,
    arrivedFromPeerId = arrivedFromPeerId,
    hopCount = hopCount,
    recordedAtMs = recordedAtMs,
)
private fun BreadcrumbEntity.toModel() = Breadcrumb(targetUserId, arrivedFromPeerId, hopCount, recordedAtMs)
