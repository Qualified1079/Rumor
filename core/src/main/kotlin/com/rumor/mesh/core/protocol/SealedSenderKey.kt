package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.HkdfSha256

/**
 * O53 — per-contact symmetric key for sealed-sender tagging.
 *
 * Both sender and recipient independently derive the same 32-byte key
 * from the X25519 ECDH of their long-term static identities:
 *
 * ```
 * shared = X25519(my_x25519_static_priv, their_x25519_static_pub)
 * tagKey = HKDF(shared, info = "rumor-dm-recipient-tag-v1:" || theirUserId || "|" || myUserId)
 * ```
 *
 * The `info` carries BOTH userIds (alphabetically ordered) so the same
 * key is derived regardless of which side is the sender. Without sorting,
 * Alice→Bob and Bob→Alice would produce different keys and the relay
 * could never match a Bob→Alice DM against Alice's precomputed tags.
 *
 * Identity keys are Ed25519 in Rumor; the X25519 conversion via the
 * birational map (O91) is applied at both ends before ECDH.
 *
 * The derived key is suitable to feed into [SealedSenderTag.tagFor] for
 * every messageId — one `tagKey` per contact, many tags per messageId
 * over time. Sender computes the tag at compose time; recipient
 * precomputes-and-caches per-contact `tagKey` once and recomputes the
 * tag on every inbound DM lookup.
 *
 * **Forward secrecy posture:** this derivation uses long-term static
 * keys on both sides, so a future leak of either side's identity key
 * lets an observer reconstruct every historical tag. That is the same
 * FS posture the plaintext-recipientId field has today — sealed-sender
 * does NOT regress; it adds privacy against in-mesh observers who do
 * NOT hold either identity key. The orthogonal FS work (O38 prekeys)
 * tightens the per-message DM encryption layer; this layer is purely
 * about routing-metadata privacy.
 */
object SealedSenderKey {

    private const val INFO_PREFIX = "rumor-dm-recipient-tag-v1:"

    /**
     * Derive the symmetric tag key shared between [myUserId] and [theirUserId].
     *
     * @param myEd25519Priv The local 64-byte Ed25519 private seed (caller
     *   responsible for zeroing after use — sensitive).
     * @param theirEd25519Pub The 32-byte Ed25519 public key of the other party.
     */
    fun derive(
        myUserId: String,
        myEd25519Priv: ByteArray,
        theirUserId: String,
        theirEd25519Pub: ByteArray,
    ): ByteArray {
        val myX25519Priv = CryptoManager.ed25519ToX25519PrivateSeed(myEd25519Priv)
        try {
            val theirX25519Pub = CryptoManager.ed25519ToX25519Public(theirEd25519Pub)
            val shared = CryptoManager.x25519Agreement(myX25519Priv, theirX25519Pub)
            try {
                val (lo, hi) = if (myUserId < theirUserId) myUserId to theirUserId
                else theirUserId to myUserId
                val info = (INFO_PREFIX + lo + "|" + hi).encodeToByteArray()
                return HkdfSha256.deriveKey(salt = ByteArray(0), ikm = shared, info = info, length = 32)
            } finally {
                shared.fill(0)
            }
        } finally {
            myX25519Priv.fill(0)
        }
    }
}
