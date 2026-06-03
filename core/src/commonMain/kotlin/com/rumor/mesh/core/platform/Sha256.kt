package com.rumor.mesh.core.platform

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
expect object Sha256 {
    fun digest(bytes: ByteArray): ByteArray
}
