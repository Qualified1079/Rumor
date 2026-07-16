package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.crypto.HkdfSha256
import com.rumor.mesh.core.model.KeyWrap
import com.rumor.mesh.core.model.MultiRecipientEnvelope
import com.rumor.mesh.core.model.multiRecipientEnvelopeSignableBytes
import com.rumor.mesh.core.platform.PlatformRandom

/**
 * O79 — Construction + decryption codec for [MultiRecipientEnvelope].
 *
 * Composes the existing primitives (X25519 from `PlatformCrypto`, AES-256-GCM
 * from `CryptoManager`, HKDF-SHA-256 from `HkdfSha256`, Ed25519 sign/verify
 * from `CryptoManager`) into the encrypt and decrypt sides of the multi-
 * recipient envelope.
 *
 * Pure functions — no GossipEngine, no MessageStore, no repository
 * access. The compose/relay/route integration is a separate concern
 * (lives in GossipEngine once `MessageType.ROOM_MESSAGE` is wired).
 *
 * **Forward secrecy properties:**
 *  - The per-envelope content key is generated fresh and never stored.
 *  - The per-envelope X25519 ephemeral private is generated fresh,
 *    used N times for N recipient wraps, then zeroed.
 *  - Each recipient's wrap key is derived per-wrap via HKDF with
 *    `info = "rumor-room-wrap-v1:" || recipientId` — domain-separated
 *    so a future protocol that uses the same shared secret cannot
 *    accidentally produce the same wrap key.
 *  - All intermediate key material is zeroed on the sender side
 *    after use (O39 pattern).
 *
 * **Wire AAD note:** the AES-GCM operations use empty AAD because
 * the envelope-level integrity is guaranteed by the outer Ed25519
 * signature (which covers the ciphertext and every wrap). Binding
 * additional context into AAD would be redundant.
 */
object MultiRecipientEnvelopeCodec {

    /** Per-recipient input: their userId + their long-term X25519 static public key. */
    data class Recipient(val userId: String, val x25519StaticPublic: ByteArray) {
        override fun equals(other: Any?) = other is Recipient &&
            other.userId == userId &&
            other.x25519StaticPublic.contentEquals(x25519StaticPublic)
        override fun hashCode(): Int = userId.hashCode() * 31 + x25519StaticPublic.contentHashCode()
    }

    private const val HKDF_INFO_PREFIX = "rumor-room-wrap-v1:"

    /**
     * Encrypt [plaintext] to all [recipients], producing a signed envelope.
     *
     * @param senderEd25519Private The sender's long-term Ed25519 private key.
     *   Used to sign the envelope. NOT zeroed by this function — caller
     *   manages identity-key lifecycle.
     * @param senderId The sender's userId (carried on the wire so receivers
     *   can match against the senderPublicKey hash).
     * @param senderEd25519Public The sender's long-term Ed25519 public key
     *   (Base64-encoded on the wire).
     * @param recipients One entry per addressee.
     * @param roomRoutingTag Opaque tag identifying the target room.
     */
    fun encrypt(
        plaintext: ByteArray,
        senderEd25519Private: ByteArray,
        senderId: String,
        senderEd25519Public: ByteArray,
        recipients: List<Recipient>,
        roomRoutingTag: String,
    ): MultiRecipientEnvelope {
        // Step 1: fresh content key for this envelope.
        val contentKey = PlatformRandom.nextBytes(32)
        // Step 2: fresh X25519 ephemeral keypair, shared across all recipient wraps.
        val ephemeral = CryptoManager.generateX25519KeyPair()
        try {
            // Step 3: AES-GCM the plaintext under the content key.
            val contentCt = CryptoManager.aesGcmEncrypt(plaintext, contentKey)
            // Step 4: per-recipient key wrap.
            val wraps = recipients.map { r ->
                val shared = CryptoManager.x25519Agreement(ephemeral.privateKeyBytes, r.x25519StaticPublic)
                try {
                    val info = (HKDF_INFO_PREFIX + r.userId).encodeToByteArray()
                    val wrapKey = HkdfSha256.deriveKey(salt = ByteArray(0), ikm = shared, info = info, length = 32)
                    try {
                        val wrappedKeyCt = CryptoManager.aesGcmEncrypt(contentKey, wrapKey)
                        KeyWrap(
                            recipientId = r.userId,
                            wrappedKey = wrappedKeyCt.ciphertext.toBase64(),
                            wrapIv = wrappedKeyCt.iv.toBase64(),
                        )
                    } finally {
                        wrapKey.fill(0)
                    }
                } finally {
                    shared.fill(0)
                }
            }
            // Step 5: sign.
            val routingTag = roomRoutingTag
            val ephPubB64 = ephemeral.publicKeyBytes.toBase64()
            val contentCtB64 = contentCt.ciphertext.toBase64()
            val contentIvB64 = contentCt.iv.toBase64()
            val senderPubB64 = senderEd25519Public.toBase64()
            val signableBytes = multiRecipientEnvelopeSignableBytes(
                routingTag, senderId, senderPubB64, ephPubB64, contentCtB64, contentIvB64, wraps,
            )
            val sig = CryptoManager.sign(signableBytes, senderEd25519Private).toBase64()
            return MultiRecipientEnvelope(
                roomRoutingTag = routingTag,
                senderId = senderId,
                senderPublicKey = senderPubB64,
                senderEphemeralPublic = ephPubB64,
                contentCiphertext = contentCtB64,
                contentIv = contentIvB64,
                keyWraps = wraps,
                signature = sig,
            )
        } finally {
            // O39 sender-side FS: zero the content key + ephemeral private.
            contentKey.fill(0)
            ephemeral.privateKeyBytes.fill(0)
        }
    }

    /**
     * Decrypt an envelope as [myUserId] using [myX25519StaticPrivate].
     *
     * Returns the plaintext on success, or null if:
     *  - The outer Ed25519 signature does not verify.
     *  - There is no [KeyWrap] for [myUserId] (you weren't addressed).
     *  - The wrap or content AEAD fails (tampered / wrong key material).
     *
     * [myX25519StaticPrivate] is NOT zeroed by this function — caller
     * owns the long-term static lifecycle.
     */
    fun decrypt(
        envelope: MultiRecipientEnvelope,
        myUserId: String,
        myX25519StaticPrivate: ByteArray,
    ): ByteArray? {
        // Step 0: verify the outer signature first so we never touch the
        // wrap with un-authenticated wire bytes.
        val senderPub = runCatching { envelope.senderPublicKey.fromBase64() }.getOrNull() ?: return null
        val sig = runCatching { envelope.signature.fromBase64() }.getOrNull() ?: return null
        val signableBytes = multiRecipientEnvelopeSignableBytes(
            envelope.roomRoutingTag, envelope.senderId, envelope.senderPublicKey,
            envelope.senderEphemeralPublic, envelope.contentCiphertext, envelope.contentIv,
            envelope.keyWraps,
        )
        if (!CryptoManager.verify(signableBytes, sig, senderPub)) return null

        // Step 1: find my slot.
        val myWrap = envelope.keyWraps.firstOrNull { it.recipientId == myUserId } ?: return null

        // Step 2: derive my wrap key.
        val ephPub = runCatching { envelope.senderEphemeralPublic.fromBase64() }.getOrNull() ?: return null
        val shared = CryptoManager.x25519Agreement(myX25519StaticPrivate, ephPub)
        return try {
            val info = (HKDF_INFO_PREFIX + myUserId).encodeToByteArray()
            val wrapKey = HkdfSha256.deriveKey(salt = ByteArray(0), ikm = shared, info = info, length = 32)
            try {
                // Step 3: decrypt the content key.
                val wrappedKeyBytes = runCatching { myWrap.wrappedKey.fromBase64() }.getOrNull() ?: return null
                val wrapIv = runCatching { myWrap.wrapIv.fromBase64() }.getOrNull() ?: return null
                val contentKey = runCatching {
                    CryptoManager.aesGcmDecrypt(
                        CryptoManager.AesGcmCiphertext(wrapIv, wrappedKeyBytes),
                        wrapKey,
                    )
                }.getOrNull() ?: return null
                try {
                    // Step 4: decrypt the content.
                    val contentCt = runCatching { envelope.contentCiphertext.fromBase64() }.getOrNull() ?: return null
                    val contentIv = runCatching { envelope.contentIv.fromBase64() }.getOrNull() ?: return null
                    runCatching {
                        CryptoManager.aesGcmDecrypt(
                            CryptoManager.AesGcmCiphertext(contentIv, contentCt),
                            contentKey,
                        )
                    }.getOrNull()
                } finally {
                    contentKey.fill(0)
                }
            } finally {
                wrapKey.fill(0)
            }
        } finally {
            shared.fill(0)
        }
    }
}
