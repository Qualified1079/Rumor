package com.rumor.mesh.core.crypto

import com.rumor.mesh.core.platform.Base64Codec
import com.rumor.mesh.core.platform.PlatformCrypto
import com.rumor.mesh.core.platform.PlatformRandom
import com.rumor.mesh.core.platform.Sha256

/**
 * All cryptographic primitives used by Rumor.
 *
 * Ed25519 — message signing and verification.
 * X25519  — Diffie-Hellman key agreement for DM session keys.
 * AES-256-GCM — symmetric encryption for DMs and private key storage.
 * PBKDF2-SHA256 — passphrase → key derivation for private key wrapping.
 *
 * Platform-specific primitives delegate to [PlatformCrypto]. Cross-platform
 * composition (KDF wrapping, base64/hex encoding, IV generation) lives here
 * in commonMain so the wire-format-critical layering is identical across
 * targets.
 */
object CryptoManager {

    // ── Ed25519 ──────────────────────────────────────────────────────────────

    data class Ed25519KeyPair(
        val privateKeyBytes: ByteArray,  // 32 bytes
        val publicKeyBytes: ByteArray,   // 32 bytes
    )

    fun generateEd25519KeyPair(): Ed25519KeyPair {
        val (priv, pub) = PlatformCrypto.ed25519GenerateKeyPair()
        return Ed25519KeyPair(priv, pub)
    }

    fun sign(message: ByteArray, privateKeyBytes: ByteArray): ByteArray =
        PlatformCrypto.ed25519Sign(message, privateKeyBytes)

    fun verify(message: ByteArray, signature: ByteArray, publicKeyBytes: ByteArray): Boolean =
        PlatformCrypto.ed25519Verify(message, signature, publicKeyBytes)

    /** SHA-256 fingerprint of a public key, hex-encoded — used as User ID. */
    fun publicKeyToUserId(publicKeyBytes: ByteArray): String =
        Sha256.digest(publicKeyBytes).toHex()

    // ── X25519 ───────────────────────────────────────────────────────────────

    data class X25519KeyPair(
        val privateKeyBytes: ByteArray,  // 32 bytes
        val publicKeyBytes: ByteArray,   // 32 bytes
    )

    fun generateX25519KeyPair(): X25519KeyPair {
        val (priv, pub) = PlatformCrypto.x25519GenerateKeyPair()
        return X25519KeyPair(priv, pub)
    }

    /**
     * Returns a 32-byte session key. Both sides must derive the same one.
     * Wire-format-critical: the KDF here (PBKDF2 with the "RumorDH" salt,
     * single iteration) must produce identical bytes on every platform.
     */
    fun x25519Agreement(ourPrivateKeyBytes: ByteArray, theirPublicKeyBytes: ByteArray): ByteArray {
        val shared = PlatformCrypto.x25519Agreement(ourPrivateKeyBytes, theirPublicKeyBytes)
        // HKDF-SHA256 would be ideal but PBKDF2 with a constant salt gives a
        // well-understood 256-bit key without pulling in extra dependencies.
        return deriveAesKey(shared, byteArrayOf(0x52, 0x75, 0x6d, 0x6f, 0x72, 0x44, 0x48))
    }

    // ── AES-256-GCM ──────────────────────────────────────────────────────────

    data class AesGcmCiphertext(
        val iv: ByteArray,        // 12 bytes
        val ciphertext: ByteArray,
    ) {
        fun toBase64(): String {
            val combined = ByteArray(iv.size + ciphertext.size)
            iv.copyInto(combined)
            ciphertext.copyInto(combined, iv.size)
            return Base64Codec.encode(combined)
        }

        companion object {
            fun fromBase64(b64: String): AesGcmCiphertext {
                val combined = Base64Codec.decode(b64)
                val iv = combined.copyOfRange(0, 12)
                val ct = combined.copyOfRange(12, combined.size)
                return AesGcmCiphertext(iv, ct)
            }
        }
    }

    fun aesGcmEncrypt(plaintext: ByteArray, keyBytes: ByteArray): AesGcmCiphertext {
        val iv = PlatformRandom.nextBytes(12)
        return AesGcmCiphertext(iv, PlatformCrypto.aesGcmEncrypt(plaintext, keyBytes, iv))
    }

    fun aesGcmDecrypt(ct: AesGcmCiphertext, keyBytes: ByteArray): ByteArray =
        PlatformCrypto.aesGcmDecrypt(ct.ciphertext, keyBytes, ct.iv)

    // ── PBKDF2 passphrase → key ──────────────────────────────────────────────

    fun generateSalt(bytes: Int = 32): ByteArray = PlatformRandom.nextBytes(bytes)

    /**
     * Derives a 256-bit AES key from a passphrase.
     * 100,000 iterations — slow enough to resist brute-force on a phone.
     */
    fun deriveKeyFromPassphrase(passphrase: String, salt: ByteArray): ByteArray =
        PlatformCrypto.pbkdf2HmacSha256(passphrase, salt, iterations = 100_000, outputBits = 256)

    /**
     * Single-iteration PBKDF2 over the raw X25519 shared secret to produce a
     * 256-bit AES key. The raw output is already high-entropy so iterations
     * aren't doing brute-force protection — they're doing KDF mixing.
     *
     * **Wire-format-critical.** The passphrase argument is null in the legacy
     * JVM `PBEKeySpec(null, secret + salt, …)` form; we approximate by feeding
     * the salt-prefixed secret as the passphrase bytes (UTF-8 via String).
     * On JVM the legacy code path is preserved verbatim via PlatformCrypto's
     * actual; on other platforms the actual is responsible for matching that
     * behaviour byte-for-byte.
     */
    private fun deriveAesKey(secret: ByteArray, salt: ByteArray): ByteArray =
        platformDeriveAesKey(secret, salt)

    // ── Helpers ──────────────────────────────────────────────────────────────

    fun ByteArray.toHex(): String =
        joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    fun ByteArray.toBase64(): String = Base64Codec.encode(this)
    fun String.fromBase64(): ByteArray = Base64Codec.decode(this)

    fun randomBytes(n: Int): ByteArray = PlatformRandom.nextBytes(n)
}

/**
 * Bridge to the legacy `PBEKeySpec(null, secret + salt, 1, 256)` form used in
 * `deriveAesKey`. The JVM implementation calls into `SecretKeyFactory` with
 * the null-passphrase form (which BouncyCastle and the SunJCE both accept);
 * non-JVM actuals match the byte output.
 */
internal expect fun platformDeriveAesKey(secret: ByteArray, salt: ByteArray): ByteArray
