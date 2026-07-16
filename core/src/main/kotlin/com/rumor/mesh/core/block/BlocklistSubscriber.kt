package com.rumor.mesh.core.block

import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.data.BlocklistEntryRepository
import com.rumor.mesh.core.data.SubscribedBlocklistRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Blocklist
import com.rumor.mesh.core.model.BlocklistDiff
import com.rumor.mesh.core.model.BlocklistEntry
import com.rumor.mesh.core.model.BlocklistMode
import com.rumor.mesh.core.model.SubscribedBlocklist

/**
 * Consumer side of the blocklist publish/subscribe machinery. Holds
 * the local user's subscription decisions (which publishers, what
 * mode) and applies inbound snapshots / diffs to the persisted
 * entry table.
 *
 * Mode semantics (per [BlocklistMode]):
 *  - **ONE_TIME** — apply the first signed snapshot received from
 *    [subscribe] time forward, then ignore everything else from
 *    that publisher. UI-driven "copy this list once and freeze."
 *  - **CONTINUOUS** — apply every signed update (snapshot or diff)
 *    monotonically. The publisher's curation flows through into this
 *    user's filter forever (until [unsubscribe]).
 *
 * Both [applySnapshot] and [applyDiff] are guarded by:
 *   (a) monotonic version checks — stale versions can't roll back
 *       the local state;
 *   (b) signature verification via [BlocklistVerifier] — a relay or
 *       MITM can't slip in a forged list;
 *   (c) mode enforcement — ONE_TIME subscriptions reject post-init
 *       updates, CONTINUOUS subscriptions reject diffs from the
 *       wrong fromVersion.
 *
 * Returns boolean for accepted-vs-rejected so callers (the gossip
 * bridge) can log without surfacing rejection as an error.
 */
class BlocklistSubscriber(
    private val subscribedBlocklistRepo: SubscribedBlocklistRepository,
    private val blocklistEntryRepo: BlocklistEntryRepository,
) {
    private val TAG = "BlocklistSubscriber"

    suspend fun subscribe(publisherId: String, publisherPublicKey: ByteArray, mode: BlocklistMode) {
        subscribedBlocklistRepo.upsert(
            SubscribedBlocklist(
                publisherId = publisherId,
                publisherPublicKey = com.rumor.mesh.core.platform.Base64Codec.encode(publisherPublicKey),
                mode = mode,
                currentVersion = 0,
                subscribedAtMs = SystemClock.now(),
                lastAppliedAtMs = 0,
            )
        )
        RumorLog.i(TAG, "Subscribed to ${publisherId.take(16)}… ($mode)")
    }

    suspend fun unsubscribe(publisherId: String) {
        subscribedBlocklistRepo.delete(publisherId)
        blocklistEntryRepo.deleteAllForPublisher(publisherId)
    }

    suspend fun applySnapshot(snapshot: Blocklist): Boolean {
        val sub = subscribedBlocklistRepo.get(snapshot.publisherId) ?: return false
        if (sub.mode == BlocklistMode.ONE_TIME && sub.currentVersion > 0) return false
        if (snapshot.version <= sub.currentVersion) return false
        val publisherKey = sub.publisherPublicKey.fromBase64()
        if (!BlocklistVerifier.verifySnapshot(snapshot, publisherKey)) {
            RumorLog.w(TAG, "Snapshot from ${snapshot.publisherId.take(16)}… failed verification")
            return false
        }
        blocklistEntryRepo.deleteAllForPublisher(snapshot.publisherId)
        blocklistEntryRepo.insertAll(snapshot.entries.map {
            BlocklistEntry(snapshot.publisherId, it)
        })
        subscribedBlocklistRepo.upsert(
            sub.copy(currentVersion = snapshot.version, lastAppliedAtMs = SystemClock.now())
        )
        return true
    }

    suspend fun applyDiff(diff: BlocklistDiff): Boolean {
        val sub = subscribedBlocklistRepo.get(diff.publisherId) ?: return false
        if (sub.mode != BlocklistMode.CONTINUOUS) return false
        if (diff.fromVersion != sub.currentVersion) return false
        val publisherKey = sub.publisherPublicKey.fromBase64()
        if (!BlocklistVerifier.verifyDiff(diff, publisherKey)) {
            RumorLog.w(TAG, "Diff from ${diff.publisherId.take(16)}… failed verification")
            return false
        }
        if (diff.added.isNotEmpty()) {
            blocklistEntryRepo.insertAll(diff.added.map { BlocklistEntry(diff.publisherId, it) })
        }
        if (diff.removed.isNotEmpty()) {
            blocklistEntryRepo.deleteEntries(diff.publisherId, diff.removed)
        }
        subscribedBlocklistRepo.upsert(
            sub.copy(currentVersion = diff.toVersion, lastAppliedAtMs = SystemClock.now())
        )
        return true
    }
}
