package com.rumor.mesh.core.platform

import java.security.MessageDigest


/**
 * Platform shim: SHA-256 one-shot digest.
 *
 * Returns 32 bytes. Throws on no-such-algorithm — every reasonable platform
 * ships SHA-256, and a missing implementation is a configuration error, not
 * a recoverable runtime condition.
 *
 * No streaming variant. Add one if a real use case appears; current callers
 * (Rbsr, CryptoManager, Chunker) all do one-shot digests over reasonably-
 * sized inputs.
 *
 * Phase 1c shim, first of the set per docs/PHASE_1C_SHIM_SURFACE.md.
 */
/**
 * JVM for [Sha256]. Delegates to `java.security.MessageDigest`.
 *
 * Each call constructs a fresh digest instance — `MessageDigest` is not
 * thread-safe, and the per-call allocation cost is dominated by the digest
 * itself for any non-trivial input. If profiling ever shows the allocation
 * matters, switch to a `ThreadLocal<MessageDigest>` with explicit reset.
 */
object Sha256 {
    fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
