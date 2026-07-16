package com.rumor.mesh.core.filter

import com.rumor.mesh.core.data.FilterSubscriptionRepository
import com.rumor.mesh.core.data.KeywordFilterListRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.FilterSubscription
import com.rumor.mesh.core.model.FilterSubscriptionMode
import com.rumor.mesh.core.model.KeywordFilterList
import com.rumor.mesh.core.platform.Base64Codec

/**
 * O67 — Manages subscriptions to keyword filter lists published by other
 * users. Mirrors `BlocklistSubscriber`'s shape.
 *
 * Two ingest paths:
 *
 *  - [subscribe] registers interest in a publisher (stores their pubkey
 *    and the local subscription preferences). Does NOT itself fetch a
 *    list — that happens when a `KEYWORD_FILTER_PUBLISH` arrives over
 *    gossip and is handed to [applyList].
 *
 *  - [applyList] verifies an inbound list against the registered
 *    publisher's pubkey, enforces monotonic version
 *    (`list.version > sub.lastAppliedVersion`), and stores the list.
 *    Returns true on accept, false on any reject (no subscription,
 *    ONE_TIME already applied, stale version, signature failure).
 *
 * Local preference overrides (action overrides, allowlist additions,
 * enable/disable, gossip-share toggle) are mutated independently via
 * [updateSubscription] — they are subscriber-side state, not part of
 * the publisher's signed bytes.
 *
 * Helper [subscribedListsForMatcher] returns the snapshot the
 * `KeywordFilterMatcher.match` API expects (paired lists + subscription
 * overrides), filtered to enabled subscriptions only.
 */
class KeywordFilterSubscriber(
    private val listRepo: KeywordFilterListRepository,
    private val subRepo: FilterSubscriptionRepository,
) {
    private val TAG = "KeywordFilterSubscriber"

    suspend fun subscribe(
        publisherId: String,
        publisherPublicKey: ByteArray,
        listName: String,
        mode: FilterSubscriptionMode = FilterSubscriptionMode.CONTINUOUS,
    ) {
        subRepo.upsert(
            FilterSubscription(
                listPublisherId = publisherId,
                listName = listName,
                mode = mode,
                enabled = true,
                publisherPublicKey = Base64Codec.encode(publisherPublicKey),
                lastAppliedVersion = 0,
            )
        )
        RumorLog.i(TAG, "Subscribed to ${publisherId.take(16)}… ($mode)")
    }

    suspend fun unsubscribe(publisherId: String) {
        subRepo.delete(publisherId)
        listRepo.delete(publisherId)
    }

    /** Replace the stored override layer for a publisher (action overrides, allowlist, enable/disable). */
    suspend fun updateSubscription(sub: FilterSubscription) {
        subRepo.upsert(sub)
    }

    /**
     * Verify and apply an inbound signed list. Returns true on success;
     * false on no-subscription / stale-version / ONE_TIME-already-applied /
     * signature failure.
     */
    suspend fun applyList(list: KeywordFilterList): Boolean {
        val sub = subRepo.get(list.publisherId) ?: return false
        if (sub.mode == FilterSubscriptionMode.ONE_TIME && sub.lastAppliedVersion > 0) return false
        if (list.version <= sub.lastAppliedVersion) return false
        if (sub.publisherPublicKey.isEmpty()) return false
        val publisherKey = runCatching { Base64Codec.decode(sub.publisherPublicKey) }.getOrNull() ?: return false
        if (!KeywordFilterVerifier.verify(list, publisherKey)) {
            RumorLog.w(TAG, "List from ${list.publisherId.take(16)}… failed verification")
            return false
        }
        listRepo.upsert(list)
        subRepo.upsert(sub.copy(lastAppliedVersion = list.version, listName = list.name))
        return true
    }

    /**
     * Snapshot of `(list, subscription)` pairs the matcher consumes.
     * Filters to lists that are both stored locally AND have an
     * enabled subscription — disabled subs are skipped so the matcher
     * doesn't even consider them.
     */
    suspend fun subscribedListsForMatcher(): List<Pair<KeywordFilterList, FilterSubscription>> {
        val subs = subRepo.getAll().associateBy { it.listPublisherId }
        return listRepo.getAll().mapNotNull { list ->
            val sub = subs[list.publisherId] ?: return@mapNotNull null
            if (!sub.enabled) return@mapNotNull null
            list to sub
        }
    }
}
