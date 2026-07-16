package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.model.MessageDeletePayload
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.messageDeleteSignableBytes

/**
 * O40 — Authorization gate for inbound `MESSAGE_DELETE` requests.
 *
 * A relay honors a delete request only if both conditions hold:
 *
 *  1. **Signature verifies.** The payload carries an Ed25519
 *     signature over the canonical bytes derived from messageId +
 *     issuerPublicKey. The signing key MUST hash to the issuer's
 *     declared userId — same `publicKeyToUserId` derivation Rumor
 *     uses for all identity binding.
 *
 *  2. **Issuer is authorized.** The issuer's userId matches either
 *     the original DM's `senderId` (the original author may delete
 *     their own message) OR its `recipientId` (the addressee may
 *     opt to free relay storage once they've ingested it).
 *
 * A third party signing a delete for someone else's message is
 * rejected — no quorum, no central authority, no "I think this should
 * be gone" by strangers. The check requires the target message be
 * present in the relay's store (so we can compare against its
 * sender/recipient); a relay that has already purged or never saw
 * the message returns `Authorized = true` with `target = null` and
 * the caller treats it as a no-op delete (still safe to keep the id
 * in the dedup set to prevent future re-ingestion).
 */
object MessageDeleteVerifier {

    sealed class Result {
        /** Authorized; if [target] is non-null, purge it. */
        data class Authorized(val target: RumorMessage?) : Result()
        /** Rejected — reason logged at the call site. */
        data class Rejected(val reason: String) : Result()
    }

    suspend fun verify(
        payload: MessageDeletePayload,
        targetLookup: suspend (id: String) -> RumorMessage?,
    ): Result {
        val pubKey = runCatching { payload.issuerPublicKey.fromBase64() }.getOrNull()
            ?: return Result.Rejected("issuerPublicKey not valid base64")
        val sig = runCatching { payload.signature.fromBase64() }.getOrNull()
            ?: return Result.Rejected("signature not valid base64")

        val signed = messageDeleteSignableBytes(payload.messageId, payload.issuerPublicKey)
        if (!CryptoManager.verify(signed, sig, pubKey)) {
            return Result.Rejected("signature verify failed")
        }

        val issuerUserId = CryptoManager.publicKeyToUserId(pubKey)
        val target = targetLookup(payload.messageId)
            ?: return Result.Authorized(target = null)  // already purged or never seen

        if (issuerUserId != target.senderId && issuerUserId != target.recipientId) {
            return Result.Rejected(
                "issuer $issuerUserId is neither sender (${target.senderId}) " +
                    "nor recipient (${target.recipientId}) of ${payload.messageId}"
            )
        }
        return Result.Authorized(target = target)
    }
}
