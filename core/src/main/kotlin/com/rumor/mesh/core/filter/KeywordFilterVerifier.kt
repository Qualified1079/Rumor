package com.rumor.mesh.core.filter

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.model.KeywordFilterList
import com.rumor.mesh.core.model.keywordFilterListSignableBytes

/**
 * O67 — Stateless verifier for a signed [KeywordFilterList].
 *
 * Mirrors [com.rumor.mesh.core.block.BlocklistVerifier]'s shape: re-derive
 * the publisher's userId from the supplied public key, re-derive the
 * signable bytes, verify the Ed25519 signature. Any mismatch (userId,
 * signature decode, signature verify) returns false.
 *
 * Unlike Blocklist there is no `verifyDiff` here — keyword filter lists
 * are full-snapshot only in v1. Diffs may follow if real-world list churn
 * justifies them; for now snapshots are the wire-format ground truth.
 */
object KeywordFilterVerifier {

    fun verify(list: KeywordFilterList, publisherPublicKey: ByteArray): Boolean {
        val expectedUserId = CryptoManager.publicKeyToUserId(publisherPublicKey)
        if (expectedUserId != list.publisherId) return false
        val signed = keywordFilterListSignableBytes(
            list.publisherId, list.version, list.name, list.entries, list.userIdAllowlist,
        )
        val sig = runCatching { list.signature.fromBase64() }.getOrNull() ?: return false
        return CryptoManager.verify(signed, sig, publisherPublicKey)
    }
}
