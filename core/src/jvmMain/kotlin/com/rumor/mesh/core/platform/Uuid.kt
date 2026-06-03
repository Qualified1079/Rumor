package com.rumor.mesh.core.platform

import java.util.UUID

/**
 * JVM actual for [Uuid]. Delegates to `java.util.UUID.randomUUID()` which
 * uses a `SecureRandom` internally — CSPRNG-backed.
 */
actual object Uuid {
    actual fun random(): String = UUID.randomUUID().toString()
    actual fun randomHex32(): String = UUID.randomUUID().toString().replace("-", "")
}
