package com.rumor.mesh.core.platform

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
expect object Uuid {
    /** Canonical 36-char form (8-4-4-4-12 hex with dashes), lowercase. */
    fun random(): String

    /** 32-char raw hex form, lowercase. Equivalent to `random().replace("-", "")`. */
    fun randomHex32(): String
}
