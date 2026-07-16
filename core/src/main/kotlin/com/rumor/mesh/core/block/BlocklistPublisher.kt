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

/**
 * Builds and signs published blocklists from the local user's
 * current block decisions. Two surfaces:
 *
 *  - [publish] — full snapshot: every active block (expired ones
 *    excluded) wrapped, sorted, Ed25519-signed. Used for first-time
 *    subscribers and as the periodic "ground truth" anchor against
 *    which diffs are applied. Wire type is [MessageType.BLOCKLIST_PUBLISH],
 *    traffic class [TrafficClass.TRANSFER_SETUP] (full snapshot can
 *    exceed 16 KB — see CLAUDE.md design decision).
 *
 *  - [publishDiff] — incremental: just the (added, removed) delta
 *    since [fromVersion]. Wire type [MessageType.BLOCKLIST_DIFF],
 *    traffic class [TrafficClass.INFRASTRUCTURE] (always small).
 *    Returns null if nothing changed — caller decides whether to
 *    publish anything.
 *
 * Both paths return null silently if identity is locked (the only
 * legitimate failure mode at compose time); caller logs and retries
 * later. Signatures cover the canonical signable-bytes form pinned
 * by `rumor-blocklist-v1:` / `rumor-blocklist-diff-v1:` domain tags
 * — see `core/model/Blocklist.kt`.
 */
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
