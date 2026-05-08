package com.rumor.mesh.core.block

import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Blocklist
import com.rumor.mesh.core.model.BlocklistDiff
import com.rumor.mesh.core.model.BlocklistMode
import com.rumor.mesh.data.BlocklistEntryDao
import com.rumor.mesh.data.BlocklistEntryEntity
import com.rumor.mesh.data.SubscribedBlocklistDao
import com.rumor.mesh.data.SubscribedBlocklistEntity

/**
 * Applies incoming signed blocklist artifacts to local storage after verification.
 *
 * Snapshots replace the publisher's full set of entries.
 * Diffs add/remove entries — but only if [BlocklistDiff.fromVersion] matches the
 * subscriber's currently-applied version. Out-of-order diffs are dropped (the
 * subscriber will pick up the next snapshot to resync).
 *
 * One-time subscriptions ignore further updates after the initial snapshot is
 * applied; continuous subscriptions accept all subsequent updates.
 */
class BlocklistSubscriber(
    private val subscribedBlocklistDao: SubscribedBlocklistDao,
    private val blocklistEntryDao: BlocklistEntryDao,
) {
    private val TAG = "BlocklistSubscriber"

    /**
     * Subscribe to a publisher with the given mode. Caller has obtained the
     * publisher's public key out-of-band (e.g. from a contact card or QR).
     * Initial snapshot/diff arrives later through gossip.
     */
    suspend fun subscribe(publisherId: String, publisherPublicKey: ByteArray, mode: BlocklistMode) {
        subscribedBlocklistDao.upsert(
            SubscribedBlocklistEntity(
                publisherId = publisherId,
                publisherPublicKey = publisherPublicKey.let { java.util.Base64.getEncoder().encodeToString(it) },
                mode = mode,
                currentVersion = 0,
                subscribedAtMs = System.currentTimeMillis(),
                lastAppliedAtMs = 0,
            )
        )
        RumorLog.i(TAG, "Subscribed to ${publisherId.take(16)}… ($mode)")
    }

    suspend fun unsubscribe(publisherId: String) {
        subscribedBlocklistDao.delete(publisherId)
        blocklistEntryDao.deleteAllForPublisher(publisherId)
    }

    /** Apply an incoming snapshot. Returns true if it was accepted. */
    suspend fun applySnapshot(snapshot: Blocklist): Boolean {
        val sub = subscribedBlocklistDao.get(snapshot.publisherId) ?: return false
        if (sub.mode == BlocklistMode.ONE_TIME && sub.currentVersion > 0) return false
        if (snapshot.version <= sub.currentVersion) return false
        val publisherKey = sub.publisherPublicKey.fromBase64()
        if (!BlocklistVerifier.verifySnapshot(snapshot, publisherKey)) {
            RumorLog.w(TAG, "Snapshot from ${snapshot.publisherId.take(16)}… failed verification")
            return false
        }
        // Replace publisher's entries
        blocklistEntryDao.deleteAllForPublisher(snapshot.publisherId)
        blocklistEntryDao.insertAll(snapshot.entries.map {
            BlocklistEntryEntity(snapshot.publisherId, it)
        })
        subscribedBlocklistDao.upsert(
            sub.copy(currentVersion = snapshot.version, lastAppliedAtMs = System.currentTimeMillis())
        )
        return true
    }

    /** Apply an incoming diff. Returns true if it was accepted. */
    suspend fun applyDiff(diff: BlocklistDiff): Boolean {
        val sub = subscribedBlocklistDao.get(diff.publisherId) ?: return false
        if (sub.mode != BlocklistMode.CONTINUOUS) return false
        if (diff.fromVersion != sub.currentVersion) return false
        val publisherKey = sub.publisherPublicKey.fromBase64()
        if (!BlocklistVerifier.verifyDiff(diff, publisherKey)) {
            RumorLog.w(TAG, "Diff from ${diff.publisherId.take(16)}… failed verification")
            return false
        }
        if (diff.added.isNotEmpty()) {
            blocklistEntryDao.insertAll(diff.added.map {
                BlocklistEntryEntity(diff.publisherId, it)
            })
        }
        if (diff.removed.isNotEmpty()) {
            blocklistEntryDao.deleteEntries(diff.publisherId, diff.removed)
        }
        subscribedBlocklistDao.upsert(
            sub.copy(currentVersion = diff.toVersion, lastAppliedAtMs = System.currentTimeMillis())
        )
        return true
    }
}
