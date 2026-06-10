package com.rumor.mesh.core.data

import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.model.BlockEntry
import com.rumor.mesh.core.model.BlocklistEntry
import com.rumor.mesh.core.model.BlocklistMode
import com.rumor.mesh.core.model.SubscribedBlocklist

/**
 * Per-user block decisions made by THIS local user. Each
 * [BlockEntry] is a "do not surface their messages in my UI" record;
 * optionally time-bound via the entry's expiry field. Display-layer
 * only — `GossipEngine.relay()` never consults this repo (per the
 * architectural "relay path never sees blocklist" rule); only the
 * inbox emit path does.
 *
 * Three impls in tree (Room / in-memory / tests), same contract.
 */
interface BlockEntryRepository {
    suspend fun upsert(entry: BlockEntry)
    suspend fun delete(userId: String)
    suspend fun getActive(now: Long = SystemClock.now()): List<BlockEntry>
    suspend fun getActiveIds(now: Long = SystemClock.now()): List<String>
    suspend fun pruneExpired(now: Long = SystemClock.now()): Int
}

/**
 * Per-publisher subscription state — which external publisher block
 * lists this user follows, in what mode (ONE_TIME snapshot vs
 * CONTINUOUS auto-apply), and what version of each list is currently
 * applied. Distinct from [BlockEntryRepository] because it tracks
 * delegation ("I trust publisher X's list") rather than direct
 * blocks ("I personally chose to block userY").
 */
interface SubscribedBlocklistRepository {
    suspend fun upsert(sub: SubscribedBlocklist)
    suspend fun delete(publisherId: String)
    suspend fun get(publisherId: String): SubscribedBlocklist?
    suspend fun getByMode(mode: BlocklistMode): List<SubscribedBlocklist>
    suspend fun getAll(): List<SubscribedBlocklist>
}

/**
 * The actual list contents — the (publisherId, blockedUserId) join
 * table behind [SubscribedBlocklistRepository]. Reads against this
 * table feed the inbox filter; writes happen when a subscribed
 * publisher's [com.rumor.mesh.core.model.Blocklist] (full) or
 * [com.rumor.mesh.core.model.BlocklistDiff] (incremental) gets
 * applied. Pruning per-publisher is the only way a subscription
 * gets cleaned up — there's no per-entry expiry here (use
 * [BlockEntryRepository] for that).
 */
interface BlocklistEntryRepository {
    suspend fun insertAll(entries: List<BlocklistEntry>)
    suspend fun deleteAllForPublisher(publisherId: String)
    suspend fun deleteEntries(publisherId: String, userIds: List<String>)
    suspend fun getAllBlockedIds(): List<String>
    suspend fun getBlockedIdsForPublisher(publisherId: String): List<String>
}
