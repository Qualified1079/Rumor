package com.rumor.mesh.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * O67 Room entities for keyword filter persistence.
 *
 * **Storage shape:** each entity has a publisherId primary key plus a
 * single `json` TEXT column carrying the full kotlinx-serialized payload.
 * Adapter layer (`KeywordFilterRepositoryAdapter`) round-trips through
 * `WireJson` to map back to the typed `KeywordFilterList` /
 * `FilterSubscription` core models.
 *
 * **Why JSON blob rather than expanded typed columns?**
 *  - `KeywordFilterList.entries: List<FilterEntry>` and
 *    `FilterSubscription.actionOverrides: Map<String, FilterAction>` /
 *    `localAllowlistAdditions: Set<String>` are not Room-native
 *    types. Either a child-table refactor or a per-field JSON
 *    converter would be required; the per-row JSON blob is the
 *    smallest amount of code that achieves the same persistence
 *    semantics.
 *  - We never query inside these blobs. The repository surface
 *    (get by publisherId, getAll) only ever materializes whole
 *    objects; SQL-side filtering on entries would never beat the
 *    matcher's in-memory scan over a few-KB blob.
 *  - The wire-format-to-storage-format mapping is 1:1, so any
 *    serialization regression caught by a deserialization failure
 *    surfaces immediately at read time.
 *
 * **Trade-off accepted:** a corrupted JSON blob can take down a single
 * subscription rather than a single field. Adapter logs and returns null
 * on deserialize failure (same shape as `BlocklistVerifier` returning
 * null on signature failure), so the failure is observable.
 */

@Entity(tableName = "keyword_filter_lists")
data class KeywordFilterListEntity(
    @PrimaryKey val publisherId: String,
    val json: String,
)

@Entity(tableName = "filter_subscriptions")
data class FilterSubscriptionEntity(
    @PrimaryKey val publisherId: String,
    val json: String,
)
