package com.rumor.mesh.data.adapter

import com.rumor.mesh.core.data.FilterSubscriptionRepository
import com.rumor.mesh.core.data.KeywordFilterListRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.FilterSubscription
import com.rumor.mesh.core.model.KeywordFilterList
import com.rumor.mesh.core.wire.WireJson
import com.rumor.mesh.data.FilterSubscriptionDao
import com.rumor.mesh.data.FilterSubscriptionEntity
import com.rumor.mesh.data.KeywordFilterListDao
import com.rumor.mesh.data.KeywordFilterListEntity
import kotlinx.serialization.encodeToString

/**
 * O67 Room adapters for keyword filter persistence. JSON-blob storage
 * shape rationale documented in `KeywordFilterEntities.kt`.
 *
 * Deserialization failures (corrupted blob, schema mismatch across
 * versions) are logged and yield null — consistent with the
 * "verification failure returns null" pattern used by
 * `BlocklistVerifier`. The subscriber treats a missing list the same
 * way as a never-applied one.
 */
class KeywordFilterListRepositoryAdapter(
    private val dao: KeywordFilterListDao,
) : KeywordFilterListRepository {
    private val TAG = "KeywordFilterListRepositoryAdapter"

    override suspend fun upsert(list: KeywordFilterList) {
        dao.upsert(KeywordFilterListEntity(list.publisherId, WireJson.encodeToString(list)))
    }

    override suspend fun get(publisherId: String): KeywordFilterList? =
        dao.get(publisherId)?.let { decode(it) }

    override suspend fun getAll(): List<KeywordFilterList> =
        dao.getAll().mapNotNull { decode(it) }

    override suspend fun delete(publisherId: String) = dao.delete(publisherId)

    private fun decode(entity: KeywordFilterListEntity): KeywordFilterList? =
        runCatching { WireJson.decodeFromString<KeywordFilterList>(entity.json) }
            .onFailure { RumorLog.w(TAG, "Failed to decode list for ${entity.publisherId.take(16)}…", it) }
            .getOrNull()
}

class FilterSubscriptionRepositoryAdapter(
    private val dao: FilterSubscriptionDao,
) : FilterSubscriptionRepository {
    private val TAG = "FilterSubscriptionRepositoryAdapter"

    override suspend fun upsert(sub: FilterSubscription) {
        dao.upsert(FilterSubscriptionEntity(sub.listPublisherId, WireJson.encodeToString(sub)))
    }

    override suspend fun get(publisherId: String): FilterSubscription? =
        dao.get(publisherId)?.let { decode(it) }

    override suspend fun getAll(): List<FilterSubscription> =
        dao.getAll().mapNotNull { decode(it) }

    override suspend fun delete(publisherId: String) = dao.delete(publisherId)

    private fun decode(entity: FilterSubscriptionEntity): FilterSubscription? =
        runCatching { WireJson.decodeFromString<FilterSubscription>(entity.json) }
            .onFailure { RumorLog.w(TAG, "Failed to decode subscription for ${entity.publisherId.take(16)}…", it) }
            .getOrNull()
}
