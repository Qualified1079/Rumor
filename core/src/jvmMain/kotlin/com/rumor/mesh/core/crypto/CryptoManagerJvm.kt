package com.rumor.mesh.core.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * JVM `actual` for the legacy `PBEKeySpec(null, secret + salt, 1, 256)`
 * form. Preserves the byte output of the pre-Phase-1c CryptoManager.
 *
 * Non-JVM `actual`s must match this byte-for-byte — wire-format-critical.
 */
internal actual fun platformDeriveAesKey(secret: ByteArray, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(null, secret + salt, 1, 256)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    return factory.generateSecret(spec).encoded
}
