@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.rumor.mesh.core.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/**
 * iOS `actual` for [Sha256]. Delegates to Apple's CommonCrypto `CC_SHA256`,
 * which is the system-blessed implementation (FIPS-validated on iOS / macOS).
 */
actual object Sha256 {
    actual fun digest(bytes: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        bytes.usePinned { input ->
            out.usePinned { output ->
                CC_SHA256(input.addressOf(0), bytes.size.toUInt(), output.addressOf(0).reinterpret())
            }
        }
        return out
    }
}
