package com.rumor.mesh.data.adapter

import com.rumor.mesh.core.data.RoomSubscription
import com.rumor.mesh.core.data.RoomSubscriptionMode
import com.rumor.mesh.core.data.RoomSubscriptionRepository
import com.rumor.mesh.data.RoomSubscriptionDao
import com.rumor.mesh.data.RoomSubscriptionEntity

/**
 * O79 Room-backed adapter for [RoomSubscriptionRepository].
 *
 * Flat-field mapping (no JSON-blob round-trip). Mode is persisted
 * as its enum `name`; an unknown / corrupted name yields a fallback
 * to OPEN with empty routingKey — safer than crashing on a corrupt
 * row.
 */
class RoomSubscriptionRepositoryAdapter(
    private val dao: RoomSubscriptionDao,
) : RoomSubscriptionRepository {

    override suspend fun upsert(subscription: RoomSubscription) {
        dao.upsert(subscription.toEntity())
    }

    override suspend fun delete(roomId: String) = dao.delete(roomId)

    override suspend fun get(roomId: String): RoomSubscription? =
        dao.get(roomId)?.toModel()

    override suspend fun getAll(): List<RoomSubscription> =
        dao.getAll().mapNotNull { it.toModelOrNull() }
}

private fun RoomSubscription.toEntity() = RoomSubscriptionEntity(
    roomId = roomId,
    mode = mode.name,
    routingKey = routingKey,
    joinedAtMs = joinedAtMs,
)

private fun RoomSubscriptionEntity.toModel(): RoomSubscription = toModelOrNull()
    ?: error("Unknown subscription mode '$mode' for room $roomId — corrupted row")

/**
 * Safe converter that returns null if the persisted row is malformed.
 * Used by `getAll` to filter out corrupt rows silently rather than
 * propagating an exception across the boundary; single-row reads
 * via `get` use [toModel] which throws so the caller sees the
 * failure directly.
 */
private fun RoomSubscriptionEntity.toModelOrNull(): RoomSubscription? {
    val parsedMode = runCatching { RoomSubscriptionMode.valueOf(mode) }.getOrNull() ?: return null
    return runCatching {
        RoomSubscription(
            roomId = roomId,
            mode = parsedMode,
            routingKey = routingKey,
            joinedAtMs = joinedAtMs,
        )
    }.getOrNull()  // init { require(...) } may also throw on malformed routingKey
}
