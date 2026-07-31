package com.rumor.mesh.core.crypto

/**
 * Constant-time byte-array equality for comparing secret-derived values
 * (MACs, auth tags, routing tags, HMAC outputs).
 *
 * `ByteArray.contentEquals` short-circuits on the first mismatching byte, so the
 * time it takes leaks how long a common prefix the two arrays share. For any
 * value an attacker can (a) supply and (b) time the comparison of against a
 * secret-derived expected value, that leak is a byte-at-a-time forgery oracle.
 * This compare instead XOR-accumulates across every byte and branches only on
 * the final result, so its timing is independent of *where* the arrays differ.
 *
 * The length check is intentionally NOT constant-time: our tags/MACs are all
 * fixed-width, so a length is not itself a secret. If you ever compare
 * variable-length secret material, revisit that.
 *
 * Honest scope (per O27): on a JIT/GC runtime this is best-effort, not a
 * hardware guarantee — the JIT, branch predictor, and memory subsystem can
 * reintroduce data-dependent timing. It is strictly better than an
 * early-returning compare and is the correct default for secret comparisons.
 */
object ConstantTime {
    fun equals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
