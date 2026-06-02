package com.rumor.mesh.core.crypto

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * All cryptographic primitives used by Rumor.
 *
 * Ed25519 — message signing and verification (via BouncyCastle; Android native
 * Ed25519 only available on API 33+).
 * X25519  — Diffie-Hellman key agreement for DM session keys.
 * AES-256-GCM — symmetric encryption for DMs and private key storage.
 * PBKDF2-SHA256 — passphrase → key derivation for private key wrapping.
 */
object CryptoManager {

    private val rng = SecureRandom()

    // ── Ed25519 ───────────────────────────────────────────────────────────────

    data class Ed25519KeyPair(
        val privateKeyBytes: ByteArray,  // 32 bytes
        val publicKeyBytes: ByteArray,   // 32 bytes
    )

    fun generateEd25519KeyPair(): Ed25519KeyPair {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(rng))
        val pair = gen.generateKeyPair()
        val priv = pair.private as Ed25519PrivateKeyParameters
        val pub  = pair.public  as Ed25519PublicKeyParameters
        return Ed25519KeyPair(priv.encoded, pub.encoded)
    }

    fun sign(message: ByteArray, privateKeyBytes: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyBytes))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(message: ByteArray, signature: ByteArray, publicKeyBytes: ByteArray): Boolean {
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKeyBytes))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) { false }
    }

    /** SHA-256 fingerprint of a public key, hex-encoded — used as User ID. */
    fun publicKeyToUserId(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(publicKeyBytes).toHex()
    }

    // ── X25519 ────────────────────────────────────────────────────────────────

    data class X25519KeyPair(
        val privateKeyBytes: ByteArray,  // 32 bytes
        val publicKeyBytes: ByteArray,   // 32 bytes
    )

    fun generateX25519KeyPair(): X25519KeyPair {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(rng))
        val pair = gen.generateKeyPair()
        val priv = pair.private as X25519PrivateKeyParameters
        val pub  = pair.public  as X25519PublicKeyParameters
        return X25519KeyPair(priv.encoded, pub.encoded)
    }

    /** Returns a 32-byte shared secret. Both sides must derive the same session key from this. */
    fun x25519Agreement(ourPrivateKeyBytes: ByteArray, theirPublicKeyBytes: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(ourPrivateKeyBytes))
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(theirPublicKeyBytes), shared, 0)
        // HKDF-SHA256 would be ideal but PBKDF2 with a constant salt gives us a
        // well-understood 256-bit key without pulling in extra dependencies.
        return deriveAesKey(shared, byteArrayOf(0x52, 0x75, 0x6d, 0x6f, 0x72, 0x44, 0x48))
    }

    // ── AES-256-GCM ───────────────────────────────────────────────────────────

    data class AesGcmCiphertext(
        val iv: ByteArray,        // 12 bytes
        val ciphertext: ByteArray,
    ) {
        fun toBase64(): String {
            val combined = ByteArray(iv.size + ciphertext.size)
            iv.copyInto(combined)
            ciphertext.copyInto(combined, iv.size)
            return Base64.getEncoder().encodeToString(combined)
        }

        companion object {
            fun fromBase64(b64: String): AesGcmCiphertext {
                val combined = Base64.getDecoder().decode(b64)
                val iv = combined.copyOfRange(0, 12)
                val ct = combined.copyOfRange(12, combined.size)
                return AesGcmCiphertext(iv, ct)
            }
        }
    }

    fun aesGcmEncrypt(plaintext: ByteArray, keyBytes: ByteArray): AesGcmCiphertext {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        return AesGcmCiphertext(iv, cipher.doFinal(plaintext))
    }

    fun aesGcmDecrypt(ct: AesGcmCiphertext, keyBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ct.iv))
        return cipher.doFinal(ct.ciphertext)
    }

    // ── PBKDF2 passphrase → key ───────────────────────────────────────────────

    fun generateSalt(bytes: Int = 32): ByteArray = ByteArray(bytes).also { rng.nextBytes(it) }

    /**
     * Derives a 256-bit AES key from a passphrase.
     * 100,000 iterations — slow enough to resist brute-force on a phone.
     */
    fun deriveKeyFromPassphrase(passphrase: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 100_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun deriveAesKey(secret: ByteArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(null, secret + salt, 1, 256)
        // Single iteration — the raw X25519 output is already high-entropy.
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
    fun String.fromBase64(): ByteArray = Base64.getDecoder().decode(this)

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
}
