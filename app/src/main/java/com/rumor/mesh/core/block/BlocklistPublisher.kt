package com.rumor.mesh.core.block

import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Blocklist
import com.rumor.mesh.core.model.BlocklistDiff
import com.rumor.mesh.core.model.blocklistDiffSignableBytes
import com.rumor.mesh.core.model.blocklistSignableBytes
import com.rumor.mesh.data.BlockEntryDao

/**
 * Builds signed [Blocklist] snapshots and [BlocklistDiff] updates from the local
 * block list. Only nodes that opt in to publishing instantiate this — the local
 * [BlockEntryDao] state is shared but most users won't make it visible.
 *
 * The version counter is monotonically increasing per local epoch ms at publish
 * time. Subscribers diff against the version they last applied, so monotonicity
 * is the only requirement.
 */
class BlocklistPublisher(
    private val blockEntryDao: BlockEntryDao,
    private val identityManager: IdentityManager,
) {
    private val TAG = "BlocklistPublisher"

    /** Build and sign a fresh full snapshot from current active blocks. */
    suspend fun publish(): Blocklist? {
        val identity = identityManager.identity.value ?: run {
            RumorLog.w(TAG, "Cannot publish — identity locked")
            return null
        }
        val entries = blockEntryDao.getActiveIds().sorted()
        val version = System.currentTimeMillis()
        val signed = blocklistSignableBytes(identity.userId, version, entries)
        val sig = identityManager.sign(signed) ?: return null
        return Blocklist(
            publisherId = identity.userId,
            version = version,
            entries = entries,
            signature = sig.toBase64(),
        )
    }

    /**
     * Build a signed diff between two versions. [previousEntries] is the list
     * the subscriber last saw at [fromVersion]; [currentEntries] is the new state.
     * Caller is responsible for tracking previousEntries (publisher caches sent
     * snapshots, or subscribers piggyback their last-applied version).
     */
    suspend fun publishDiff(
        fromVersion: Long,
        previousEntries: List<String>,
        toVersion: Long = System.currentTimeMillis(),
    ): BlocklistDiff? {
        val identity = identityManager.identity.value ?: return null
        val current = blockEntryDao.getActiveIds().toSet()
        val previous = previousEntries.toSet()
        val added = (current - previous).sorted()
        val removed = (previous - current).sorted()
        if (added.isEmpty() && removed.isEmpty()) return null
        val signed = blocklistDiffSignableBytes(
            identity.userId, fromVersion, toVersion, added, removed,
        )
        val sig = identityManager.sign(signed) ?: return null
        return BlocklistDiff(
            publisherId = identity.userId,
            fromVersion = fromVersion,
            toVersion = toVersion,
            added = added,
            removed = removed,
            signature = sig.toBase64(),
        )
    }
}
