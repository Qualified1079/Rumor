package com.rumor.mesh.core.platform

import java.security.SecureRandom

/**
 * JVM `actual` for [PlatformRandom]. Delegates to `java.security.SecureRandom`
 * which is kernel-seeded at boot and uses /dev/urandom on Linux/Android
 * (and the OS CSPRNG on other JVM-supporting OSes).
 *
 * Single shared instance — `SecureRandom` is documented thread-safe.
 */
actual object PlatformRandom {
    private val rng = SecureRandom()

    actual fun nextBytes(buf: ByteArray) {
        rng.nextBytes(buf)
    }

    actual fun nextBytes(size: Int): ByteArray {
        val buf = ByteArray(size)
        rng.nextBytes(buf)
        return buf
    }
}
