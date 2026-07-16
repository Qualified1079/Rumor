package com.rumor.mesh.core.platform

import java.security.SecureRandom


/**
 * Platform CSPRNG. Used by the crypto layer for nonces, ephemeral keys, and
 * any other secret-material generation.
 *
 * **Never fall back to `kotlin.random.Random`** for security-sensitive callers;
 * that's a non-cryptographic PRNG. JVM `actual` uses `java.security.SecureRandom`
 * (kernel-seeded). iOS `actual` will use `SecRandomCopyBytes`. Linux/other
 * native targets use `/dev/urandom` via the OS API.
 *
 * Phase 1c shim per docs/IOS_PORT_PHASE_1_HANDOFF.md.
 */
/**
 * JVM `actual` for [PlatformRandom]. Delegates to `java.security.SecureRandom`
 * which is kernel-seeded at boot and uses /dev/urandom on Linux/Android
 * (and the OS CSPRNG on other JVM-supporting OSes).
 *
 * Single shared instance — `SecureRandom` is documented thread-safe.
 */
object PlatformRandom {
    private val rng = SecureRandom()

    fun nextBytes(buf: ByteArray) {
        rng.nextBytes(buf)
    }

    fun nextBytes(size: Int): ByteArray {
        val buf = ByteArray(size)
        rng.nextBytes(buf)
        return buf
    }
}
