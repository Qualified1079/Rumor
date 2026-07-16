package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.model.PrekeyPublish
import com.rumor.mesh.core.model.prekeyPublishSignableBytes

/**
 * O38 — Stateless verifier for a [PrekeyPublish].
 *
 * Two checks, in order:
 *
 *   1. **publisherId binds to publisherPublicKey.** Re-derive the
 *      userId from the supplied pubkey via the standard hash and
 *      compare. Prevents a relay from substituting a different
 *      pubkey for the same userId.
 *
 *   2. **Signature verifies.** Ed25519 over [prekeyPublishSignableBytes].
 *      The signed bytes bind publisherId, publisherPublicKey,
 *      prekeyPublic, validFromMs, and validToMs — so a relay cannot
 *      extend the validity window, swap the prekey, or substitute
 *      a different publisher's identity without breaking the sig.
 *
 * Validity-window enforcement (the `validFromMs/validToMs` clock
 * check) is the caller's job — verification is stateless and time-
 * free. Senders check `now in [validFromMs, validToMs)` before
 * using a verified prekey.
 */
object PrekeyVerifier {

    sealed class Result {
        object Accepted : Result()
        data class Rejected(val reason: String) : Result()
    }

    fun verify(publish: PrekeyPublish): Result {
        val pubKey = runCatching { publish.publisherPublicKey.fromBase64() }.getOrNull()
            ?: return Result.Rejected("publisherPublicKey not valid base64")

        val expectedUserId = CryptoManager.publicKeyToUserId(pubKey)
        if (expectedUserId != publish.publisherId) {
            return Result.Rejected("publisherId does not hash from publisherPublicKey")
        }

        val sig = runCatching { publish.signature.fromBase64() }.getOrNull()
            ?: return Result.Rejected("signature not valid base64")

        val signed = prekeyPublishSignableBytes(
            publish.publisherId,
            publish.publisherPublicKey,
            publish.prekeyPublic,
            publish.validFromMs,
            publish.validToMs,
        )
        if (!CryptoManager.verify(signed, sig, pubKey)) {
            return Result.Rejected("signature verify failed")
        }

        if (publish.validToMs <= publish.validFromMs) {
            return Result.Rejected("validToMs must be strictly > validFromMs")
        }

        return Result.Accepted
    }
}
