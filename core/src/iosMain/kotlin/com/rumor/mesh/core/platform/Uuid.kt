package com.rumor.mesh.core.platform

import platform.Foundation.NSUUID

/**
 * iOS `actual` for [Uuid]. Delegates to `NSUUID`, which is CSPRNG-backed.
 *
 * `NSUUID.UUIDString` returns uppercase; we lowercase to match the JVM
 * actual's `UUID.randomUUID().toString()` format.
 */
actual object Uuid {
    actual fun random(): String = NSUUID().UUIDString.lowercase()
    actual fun randomHex32(): String = NSUUID().UUIDString.replace("-", "").lowercase()
}
