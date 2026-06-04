package com.rumor.mesh.core.platform

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
expect object PlatformRandom {
    /** Fill [buf] with cryptographically secure random bytes. */
    fun nextBytes(buf: ByteArray)

    /** Allocate and return [size] cryptographically secure random bytes. */
    fun nextBytes(size: Int): ByteArray
}
