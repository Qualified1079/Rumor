package com.rumor.mesh.core.crypto

/**
 * HKDF-SHA-256 (RFC 5869) in pure Kotlin over the existing
 * [HmacSha256] primitive.
 *
 * Two-step KDF — extract then expand:
 *  - **Extract:** `PRK = HMAC(salt, IKM)` distills the input keying
 *    material into a pseudo-random key.
 *  - **Expand:** `OKM = T(1) || T(2) || ... || T(N)` where
 *    `T(i) = HMAC(PRK, T(i-1) || info || i)`, truncated to the
 *    requested output length.
 *
 * Used by O79 multi-recipient envelopes to derive per-recipient wrap
 * keys from the X25519 shared secret:
 * ```
 * shared = X25519(sender_ephemeral_priv, recipient_static_pub)
 * wrap_key = HKDF(shared, info = "rumor-room-wrap-v1:" || recipientId)
 * ```
 * Also useful for future per-contact tag key derivation (O53 sealed-
 * sender), per-channel keying (O89), and any other place a fresh
 * per-context key needs to be derived from a shared secret without
 * adding another platform actual.
 *
 * **Output length limits:** the expand step produces at most
 * `255 * 32 = 8160` bytes — way more than any callable would ever
 * need (we typically derive 32-byte AES keys). [expand] throws on
 * over-request rather than silently truncating.
 */
object HkdfSha256 {

    private const val HASH_LEN = 32

    /**
     * RFC 5869 Section 2.2 — Extract.
     *
     * @param salt Optional salt. If empty, a zero-byte salt of [HASH_LEN]
     *   is used per the RFC default.
     * @param ikm Input keying material (e.g. a DH shared secret).
     * @return 32-byte pseudo-random key.
     */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return HmacSha256.mac(effectiveSalt, ikm)
    }

    /**
     * RFC 5869 Section 2.3 — Expand.
     *
     * @param prk Pseudo-random key from [extract], or any 32-byte key.
     * @param info Optional context / application-specific info string
     *   bound into the derivation. Use to domain-separate parallel
     *   uses of the same PRK.
     * @param length Desired output length in bytes. Must be in
     *   `1..255*32` (8160). Throws [IllegalArgumentException] otherwise.
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LEN)) {
            "HKDF expand output length must be in 1..${255 * HASH_LEN}; got $length"
        }
        val n = (length + HASH_LEN - 1) / HASH_LEN
        val out = ByteArray(length)
        var prev = ByteArray(0)
        var written = 0
        for (i in 1..n) {
            val msg = prev + info + byteArrayOf(i.toByte())
            prev = HmacSha256.mac(prk, msg)
            val take = minOf(HASH_LEN, length - written)
            prev.copyInto(out, written, 0, take)
            written += take
        }
        return out
    }

    /**
     * Convenience wrapper: extract-then-expand in one call. Most
     * Rumor uses pass through this; explicit [extract] / [expand]
     * remain for cases where the PRK is cached across multiple
     * derivations.
     *
     * @param salt See [extract].
     * @param ikm Input keying material.
     * @param info Domain-separation context. Highly recommended.
     * @param length Output length in bytes.
     */
    fun deriveKey(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}
