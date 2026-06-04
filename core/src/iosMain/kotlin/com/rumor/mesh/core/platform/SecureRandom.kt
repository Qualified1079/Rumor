@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.rumor.mesh.core.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * iOS `actual` for [PlatformRandom]. Backed by the Security framework's
 * `SecRandomCopyBytes` which is the system CSPRNG.
 */
actual object PlatformRandom {
    actual fun nextBytes(buf: ByteArray) {
        if (buf.isEmpty()) return
        buf.usePinned { pinned ->
            val rc = SecRandomCopyBytes(kSecRandomDefault, buf.size.toULong(), pinned.addressOf(0))
            if (rc != 0) throw RuntimeException("SecRandomCopyBytes failed: $rc")
        }
    }

    actual fun nextBytes(size: Int): ByteArray {
        val out = ByteArray(size)
        nextBytes(out)
        return out
    }
}
