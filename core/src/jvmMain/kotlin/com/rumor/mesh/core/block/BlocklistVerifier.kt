package com.rumor.mesh.core.block

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.model.Blocklist
import com.rumor.mesh.core.model.BlocklistDiff
import com.rumor.mesh.core.model.blocklistDiffSignableBytes
import com.rumor.mesh.core.model.blocklistSignableBytes

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
