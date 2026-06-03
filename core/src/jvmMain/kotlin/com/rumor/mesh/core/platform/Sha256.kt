package com.rumor.mesh.core.platform

import java.security.MessageDigest

/**
 * JVM actual for [Sha256]. Delegates to `java.security.MessageDigest`.
 *
 * Each call constructs a fresh digest instance — `MessageDigest` is not
 * thread-safe, and the per-call allocation cost is dominated by the digest
 * itself for any non-trivial input. If profiling ever shows the allocation
 * matters, switch to a `ThreadLocal<MessageDigest>` with explicit reset.
 */
actual object Sha256 {
    actual fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
