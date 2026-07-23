package com.rumor.mesh.core.platform

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec


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
/**
 * JVM `actual` for [PlatformCrypto]. Byte-identical to the pre-Phase-1c
 * `CryptoManager` implementation — same BouncyCastle Ed25519 / X25519
 * generators + signers, same `javax.crypto` AES-GCM + PBKDF2.
 *
 * SecureRandom instance is shared (documented thread-safe).
 */
object PlatformCrypto {

    private val rng = SecureRandom()

    fun ed25519GenerateKeyPair(): Pair<ByteArray, ByteArray> {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(rng))
        val pair = gen.generateKeyPair()
        val priv = pair.private as Ed25519PrivateKeyParameters
        val pub  = pair.public  as Ed25519PublicKeyParameters
        return priv.encoded to pub.encoded
    }

    fun ed25519Sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean = try {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey))
        verifier.update(message, 0, message.size)
        verifier.verifySignature(signature)
    } catch (_: Exception) { false }

    fun x25519GenerateKeyPair(): Pair<ByteArray, ByteArray> {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(rng))
        val pair = gen.generateKeyPair()
        val priv = pair.private as X25519PrivateKeyParameters
        val pub  = pair.public  as X25519PublicKeyParameters
        return priv.encoded to pub.encoded
    }

    fun x25519Agreement(ourPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(ourPrivateKey))
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(theirPublicKey), shared, 0)
        return shared
    }

    fun ed25519ToX25519PrivateSeed(ed25519Seed: ByteArray): ByteArray =
        com.rumor.mesh.core.crypto.Ed25519ToX25519.ed25519PrivToX25519Priv(ed25519Seed)

    fun ed25519ToX25519Public(ed25519Pub: ByteArray): ByteArray =
        com.rumor.mesh.core.crypto.Ed25519ToX25519.ed25519PubToX25519Pub(ed25519Pub)

    fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    fun pbkdf2HmacSha256(passphrase: String, salt: ByteArray, iterations: Int, outputBits: Int): ByteArray {
        // O115: clear both passphrase copies we control. The String itself is
        // uncollectable-by-us (JVM interning) — threading CharArray from the
        // entry UI is the remaining full fix, tracked on the O115 row.
        val chars = passphrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, outputBits)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }
}
