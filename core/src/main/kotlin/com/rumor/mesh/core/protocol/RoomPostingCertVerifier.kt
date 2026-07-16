package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.model.RoomPostingCert
import com.rumor.mesh.core.model.roomPostingCertSignableBytes

/**
 * O89 — Stateless verifier for a [RoomPostingCert].
 *
 * Three checks:
 *
 *   1. **moderatorId binds to moderatorPublicKey.** Re-derive the userId
 *      from the supplied pubkey via the standard hash and compare.
 *      Prevents a relay from substituting a different pubkey for the
 *      same moderator userId.
 *
 *   2. **Signature verifies.** Ed25519 over [roomPostingCertSignableBytes].
 *      The signed bytes bind all cert fields — a relay cannot extend
 *      the validity window, swap the grantee, change the channel, or
 *      switch the moderator identity without breaking the sig.
 *
 *   3. **Validity window sanity.** `validToMs > validFromMs`.
 *
 * Wall-clock validity check (`now in [validFromMs, validToMs)`) and
 * **moderator-authority check** (is [RoomPostingCert.moderatorId]
 * actually a moderator of [RoomPostingCert.roomId] right now?) are the
 * caller's job. This verifier is stateless and time-free.
 *
 * Moderator-authority lookup will need a [com.rumor.mesh.core.data.RoomRepository]
 * read once that interface is shipped — until then, integrators pass
 * their own resolver. The cert-verification primitive itself doesn't
 * depend on it.
 */
object RoomPostingCertVerifier {

    sealed class Result {
        object Accepted : Result()
        data class Rejected(val reason: String) : Result()
    }

    fun verify(cert: RoomPostingCert): Result {
        val pubKey = runCatching { cert.moderatorPublicKey.fromBase64() }.getOrNull()
            ?: return Result.Rejected("moderatorPublicKey not valid base64")

        val expectedModId = CryptoManager.publicKeyToUserId(pubKey)
        if (expectedModId != cert.moderatorId) {
            return Result.Rejected("moderatorId does not hash from moderatorPublicKey")
        }

        val sig = runCatching { cert.signature.fromBase64() }.getOrNull()
            ?: return Result.Rejected("signature not valid base64")

        val signed = roomPostingCertSignableBytes(
            cert.roomId,
            cert.channel,
            cert.userId,
            cert.validFromMs,
            cert.validToMs,
            cert.moderatorId,
            cert.moderatorPublicKey,
        )
        if (!CryptoManager.verify(signed, sig, pubKey)) {
            return Result.Rejected("signature verify failed")
        }

        if (cert.validToMs <= cert.validFromMs) {
            return Result.Rejected("validToMs must be strictly > validFromMs")
        }

        return Result.Accepted
    }
}
