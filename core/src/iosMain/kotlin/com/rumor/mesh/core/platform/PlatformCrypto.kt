@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.rumor.mesh.core.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256

/**
 * iOS `actual` for [PlatformCrypto].
 *
 * **Partial implementation.** PBKDF2-HMAC-SHA256 is wired through CommonCrypto
 * (system-blessed, FIPS-validated). Ed25519, X25519, and AES-256-GCM are
 * **NOT** implemented here because Apple exposes them only through CryptoKit's
 * Swift API; there is no public C / Objective-C surface that Kotlin/Native
 * can call directly via cinterop.
 *
 * To finish the iOS port, write a thin Swift bridge inside the Xcode iOS app
 * project that exposes the three missing primitives via `@objc` classes,
 * then call into those bridge classes from this file via Kotlin/Native's
 * Objective-C interop. The Swift bridge should look roughly like:
 *
 * ```swift
 * @objc public class RumorCryptoBridge: NSObject {
 *     @objc public static func ed25519GenerateKeyPair() -> NSArray { ... }
 *     @objc public static func ed25519Sign(_ message: Data, privateKey: Data) -> Data { ... }
 *     @objc public static func ed25519Verify(_ message: Data, signature: Data, publicKey: Data) -> Bool { ... }
 *     @objc public static func x25519GenerateKeyPair() -> NSArray { ... }
 *     @objc public static func x25519Agreement(_ ourPriv: Data, theirPub: Data) -> Data { ... }
 *     @objc public static func aesGcmEncrypt(_ plaintext: Data, key: Data, iv: Data) -> Data { ... }
 *     @objc public static func aesGcmDecrypt(_ ciphertext: Data, key: Data, iv: Data) -> Data { ... }
 * }
 * ```
 *
 * Each delegates to the matching CryptoKit type (`Curve25519.Signing.*`,
 * `Curve25519.KeyAgreement.*`, `AES.GCM.SealedBox`). Golden vectors in
 * `commonTest` will validate byte-identical output against the JVM
 * BouncyCastle implementation.
 *
 * Until the bridge lands, calling Ed25519 / X25519 / AES-GCM from iOS code
 * throws `NotImplementedError` at runtime — the build configures and the
 * non-crypto features (logging, scheduling, JSON wire parsing, dedup, etc)
 * all work.
 */
actual object PlatformCrypto {

    private const val BRIDGE_MISSING =
        "iOS PlatformCrypto: Swift bridge not yet wired. See file comment for the @objc bridge spec."

    actual fun ed25519GenerateKeyPair(): Pair<ByteArray, ByteArray> = throw NotImplementedError(BRIDGE_MISSING)

    actual fun ed25519Sign(message: ByteArray, privateKey: ByteArray): ByteArray = throw NotImplementedError(BRIDGE_MISSING)

    actual fun ed25519Verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean = throw NotImplementedError(BRIDGE_MISSING)

    actual fun x25519GenerateKeyPair(): Pair<ByteArray, ByteArray> = throw NotImplementedError(BRIDGE_MISSING)

    actual fun x25519Agreement(ourPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray = throw NotImplementedError(BRIDGE_MISSING)

    actual fun ed25519ToX25519PrivateSeed(ed25519Seed: ByteArray): ByteArray = throw NotImplementedError(BRIDGE_MISSING)

    actual fun ed25519ToX25519Public(ed25519Pub: ByteArray): ByteArray = throw NotImplementedError(BRIDGE_MISSING)

    actual fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray = throw NotImplementedError(BRIDGE_MISSING)

    actual fun aesGcmDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray = throw NotImplementedError(BRIDGE_MISSING)

    actual fun pbkdf2HmacSha256(passphrase: String, salt: ByteArray, iterations: Int, outputBits: Int): ByteArray {
        val outLen = outputBits / 8
        val out = ByteArray(outLen)
        val passBytes = passphrase.encodeToByteArray()
        passBytes.usePinned { pp ->
            salt.usePinned { ss ->
                out.usePinned { oo ->
                    val rc = CCKeyDerivationPBKDF(
                        algorithm = kCCPBKDF2,
                        password = pp.addressOf(0).reinterpret(),
                        passwordLen = passBytes.size.toULong(),
                        salt = ss.addressOf(0).reinterpret(),
                        saltLen = salt.size.toULong(),
                        prf = kCCPRFHmacAlgSHA256,
                        rounds = iterations.toUInt(),
                        derivedKey = oo.addressOf(0).reinterpret(),
                        derivedKeyLen = outLen.toULong(),
                    )
                    if (rc != 0) throw RuntimeException("CCKeyDerivationPBKDF failed: $rc")
                }
            }
        }
        return out
    }
}
