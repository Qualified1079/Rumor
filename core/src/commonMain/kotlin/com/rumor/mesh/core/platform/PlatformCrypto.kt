package com.rumor.mesh.core.platform

/**
 * Platform-agnostic facade for the asymmetric / symmetric primitives Rumor
 * uses. The implementation choice is per-platform: JVM uses BouncyCastle +
 * `javax.crypto`; iOS will use `CryptoKit.Curve25519` + `CryptoKit.AES.GCM`
 * + a CommonCrypto PBKDF2 binding; Linux native will use whichever audited
 * Rust/C binding is wired in.
 *
 * Re-implementers MUST produce bit-identical outputs for the same inputs —
 * this is wire-format-critical. Golden vectors in `commonTest` pin every
 * primitive against known answers.
 *
 * The high-level wrapper [com.rumor.mesh.core.crypto.CryptoManager] lives in
 * commonMain and composes these primitives with the cross-platform Sha256 /
 * Base64Codec / PlatformRandom shims.
 *
 * Phase 1c shim per docs/IOS_PORT_PHASE_1_HANDOFF.md.
 */
expect object PlatformCrypto {

    // ── Ed25519 ──────────────────────────────────────────────────────────────

    /** Returns (privateKey, publicKey) — each 32 bytes. */
    fun ed25519GenerateKeyPair(): Pair<ByteArray, ByteArray>

    /** 64-byte detached signature. */
    fun ed25519Sign(message: ByteArray, privateKey: ByteArray): ByteArray

    /** Returns true iff [signature] verifies under [publicKey]. */
    fun ed25519Verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean

    // ── X25519 ──────────────────────────────────────────────────────────────

    /** Returns (privateKey, publicKey) — each 32 bytes. */
    fun x25519GenerateKeyPair(): Pair<ByteArray, ByteArray>

    /** 32-byte raw shared secret. Caller applies a KDF on top. */
    fun x25519Agreement(ourPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray

    // ── AES-256-GCM ──────────────────────────────────────────────────────────

    /**
     * AES-256-GCM encrypt. [iv] is 12 bytes, [key] is 32 bytes. Tag is
     * appended to the ciphertext (16 bytes, 128-bit tag, the JCE default).
     *
     * [aad] is optional associated data — bytes covered by the tag but not
     * encrypted. Empty AAD produces output identical to a no-AAD call
     * (AES-GCM definition); pass `EMPTY_AAD` (or `ByteArray(0)`) for
     * the legacy path. Used by O76 to bind `originalLength` to the tag.
     */
    fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray

    /** Inverse of [aesGcmEncrypt]; throws on auth failure. [aad] must match what encrypt used. */
    fun aesGcmDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray

    // ── PBKDF2-HMAC-SHA256 ───────────────────────────────────────────────────

    /**
     * Returns [outputBits] / 8 bytes of derived key material. The
     * passphrase is taken as UTF-8 bytes; pre-existing JVM behavior is the
     * "Char-array PBEKeySpec" form which is functionally equivalent for
     * UTF-8 callers.
     */
    fun pbkdf2HmacSha256(passphrase: String, salt: ByteArray, iterations: Int, outputBits: Int): ByteArray
}
