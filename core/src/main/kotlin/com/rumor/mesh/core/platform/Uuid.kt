package com.rumor.mesh.core.platform

import java.util.UUID


/**
 * Platform shim: random 128-bit identifier in canonical 8-4-4-4-12 hex form,
 * lowercase. CSPRNG-backed.
 *
 * Note: callers currently call `random().replace("-", "")` to get a raw
 * 32-hex-char form. A second variant `randomHex32()` returns that directly
 * to remove the post-processing pattern.
 *
 * Phase 1c shim per docs/PHASE_1C_SHIM_SURFACE.md.
 *
 * Caveat documented in CLAUDE.md scenario-17 note: random UUIDs are a source
 * of bloom-false-positive divergence in deterministic-replay scenarios.
 * Fixing it requires deriving message IDs from content (out of scope here);
 * this shim only preserves current behaviour cross-platform.
 */
/**
 * JVM for [Uuid]. Delegates to `java.util.UUID.randomUUID()` which
 * uses a `SecureRandom` internally — CSPRNG-backed.
 */
object Uuid {
    fun random(): String = UUID.randomUUID().toString()
    fun randomHex32(): String = UUID.randomUUID().toString().replace("-", "")
}
