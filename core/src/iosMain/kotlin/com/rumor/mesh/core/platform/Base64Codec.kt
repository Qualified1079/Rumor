@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.rumor.mesh.core.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.posix.memcpy

/**
 * iOS `actual` for [Base64Codec]. Standard alphabet (RFC 4648 §4) with
 * padding. Delegates to Foundation's `NSData` codec.
 */
actual object Base64Codec {
    actual fun encode(bytes: ByteArray): String {
        val data = bytes.usePinned {
            NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong())
        }
        return data.base64EncodedStringWithOptions(0u)
    }

    actual fun decode(s: String): ByteArray {
        val data = NSData.create(base64EncodedString = s, options = 0u)
            ?: throw IllegalArgumentException("Malformed Base64: $s")
        val len = data.length.toInt()
        val out = ByteArray(len)
        if (len > 0) {
            out.usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, len.toULong())
            }
        }
        return out
    }
}
