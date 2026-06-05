package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.FilterSubscription
import com.rumor.mesh.core.model.KeywordFilterList

/**
 * O67 — Repositories for keyword filter persistence.
 *
 * Two interfaces, both keyed by publisherId:
 *
 *  - [KeywordFilterListRepository] stores the verified signed list
 *    itself (the bytes the publisher signed). Whole-list keyed by
 *    publisherId because lists are small (kilobytes) and the matcher
 *    consumes them as a unit.
 *
 *  - [FilterSubscriptionRepository] stores the subscriber-local
 *    override layer ([FilterSubscription]) — enabled/disabled state,
 *    per-entry action overrides, localAllowlistAdditions, etc.
 *    Independent of the list so unsubscribing and re-subscribing
 *    preserves local prefs across the gap.
 *
 * Mirrors the two-interface split in `BlockRepository.kt` but at the
 * "whole signed object" level instead of the per-entry level (blocklists
 * have thousands of entries; keyword filter lists have dozens).
 */
interface KeywordFilterListRepository {
    /** Upsert the verified list, keyed by [KeywordFilterList.publisherId]. */
    suspend fun upsert(list: KeywordFilterList)
    suspend fun get(publisherId: String): KeywordFilterList?
    suspend fun getAll(): List<KeywordFilterList>
    suspend fun delete(publisherId: String)
}

interface FilterSubscriptionRepository {
    suspend fun upsert(sub: FilterSubscription)
    suspend fun get(publisherId: String): FilterSubscription?
    suspend fun getAll(): List<FilterSubscription>
    suspend fun delete(publisherId: String)
}
