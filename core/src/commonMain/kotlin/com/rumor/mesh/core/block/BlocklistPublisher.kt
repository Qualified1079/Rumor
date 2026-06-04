package com.rumor.mesh.core.block

import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.data.BlockEntryRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Blocklist
import com.rumor.mesh.core.model.BlocklistDiff
import com.rumor.mesh.core.model.blocklistDiffSignableBytes
import com.rumor.mesh.core.model.blocklistSignableBytes

class BlocklistPublisher(
    private val blockEntryRepo: BlockEntryRepository,
    private val identityProvider: IdentityProvider,
) {
    private val TAG = "BlocklistPublisher"

    suspend fun publish(): Blocklist? {
        val identity = identityProvider.identity.value ?: run {
            RumorLog.w(TAG, "Cannot publish — identity locked")
            return null
        }
        val entries = blockEntryRepo.getActiveIds().sorted()
        val version = SystemClock.now()
        val signed = blocklistSignableBytes(identity.userId, version, entries)
        val sig = CryptoManager.sign(signed, identity.privateKeyBytes)
        return Blocklist(
            publisherId = identity.userId,
            version = version,
            entries = entries,
            signature = sig.toBase64(),
        )
    }

    suspend fun publishDiff(
        fromVersion: Long,
        previousEntries: List<String>,
        toVersion: Long = SystemClock.now(),
    ): BlocklistDiff? {
        val identity = identityProvider.identity.value ?: return null
        val current = blockEntryRepo.getActiveIds().toSet()
        val previous = previousEntries.toSet()
        val added = (current - previous).sorted()
        val removed = (previous - current).sorted()
        if (added.isEmpty() && removed.isEmpty()) return null
        val signed = blocklistDiffSignableBytes(
            identity.userId, fromVersion, toVersion, added, removed,
        )
        val sig = CryptoManager.sign(signed, identity.privateKeyBytes)
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
