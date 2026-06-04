package com.rumor.mesh.core.crypto

import com.rumor.mesh.core.platform.PlatformCrypto

/**
 * iOS `actual` for the legacy `PBEKeySpec(null, secret + salt, 1, 256)` form
 * used in `CryptoManager.deriveAesKey` for X25519 KDF wrapping.
 *
 * On JVM, `PBEKeySpec(password=null, salt=secret+salt, iter=1, keyLen=256)`
 * treats the null password as an empty `char[]`, producing PBKDF2-HMAC-SHA256
 * of an empty HMAC key over `secret+salt` for one iteration. Reproducing
 * that here with explicit empty passphrase + the combined bytes as salt.
 *
 * **Wire-format-critical.** Golden vectors in `commonTest` must confirm the
 * byte output matches the JVM implementation before any DM traffic crosses
 * an iOS↔Android boundary.
 */
internal actual fun platformDeriveAesKey(secret: ByteArray, salt: ByteArray): ByteArray =
    PlatformCrypto.pbkdf2HmacSha256(
        passphrase = "",
        salt = secret + salt,
        iterations = 1,
        outputBits = 256,
    )
