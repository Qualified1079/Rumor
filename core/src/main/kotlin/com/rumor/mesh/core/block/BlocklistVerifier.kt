package com.rumor.mesh.core.block

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.model.Blocklist
import com.rumor.mesh.core.model.BlocklistDiff
import com.rumor.mesh.core.model.blocklistDiffSignableBytes
import com.rumor.mesh.core.model.blocklistSignableBytes

/**
 * Pure verifier for published blocklist material. Stateless — caller
 * supplies the publisher's Ed25519 public key out-of-band (typically
 * cached on first subscribe) and the verifier checks:
 *
 *  1. The supplied pubkey actually hashes to the claimed
 *     `publisherId` (defense against impersonation via Ed25519 sig
 *     verification under the wrong pubkey).
 *  2. The signature is a valid Ed25519 sig over the canonical
 *     `blocklistSignableBytes` / `blocklistDiffSignableBytes`
 *     scope (defense against tampering in transit).
 *  3. For diffs: `toVersion > fromVersion` is a basic sanity guard
 *     against replays of stale versions.
 *
 * Returns boolean rather than throwing because the failure mode is
 * "drop the message and log" not "reject the whole gossip session."
 * Bad signatures are common enough on a public mesh (corrupted
 * frames, malicious peers) that exception-based control flow would
 * be expensive.
 *
 * Mirror pattern is `KeywordFilterVerifier`, `MessageDeleteVerifier`,
 * `PrekeyVerifier` — same shape, different signed-bytes domain.
 */
object BlocklistVerifier {

    fun verifySnapshot(snapshot: Blocklist, publisherPublicKey: ByteArray): Boolean {
        val expectedUserId = CryptoManager.publicKeyToUserId(publisherPublicKey)
        if (expectedUserId != snapshot.publisherId) return false
        val signed = blocklistSignableBytes(snapshot.publisherId, snapshot.version, snapshot.entries)
        val sig = runCatching { snapshot.signature.fromBase64() }.getOrNull() ?: return false
        return CryptoManager.verify(signed, sig, publisherPublicKey)
    }

    fun verifyDiff(diff: BlocklistDiff, publisherPublicKey: ByteArray): Boolean {
        val expectedUserId = CryptoManager.publicKeyToUserId(publisherPublicKey)
        if (expectedUserId != diff.publisherId) return false
        if (diff.toVersion <= diff.fromVersion) return false
        val signed = blocklistDiffSignableBytes(
            diff.publisherId, diff.fromVersion, diff.toVersion, diff.added, diff.removed,
        )
        val sig = runCatching { diff.signature.fromBase64() }.getOrNull() ?: return false
        return CryptoManager.verify(signed, sig, publisherPublicKey)
    }
}
