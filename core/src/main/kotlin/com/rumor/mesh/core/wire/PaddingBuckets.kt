package com.rumor.mesh.core.wire

/**
 * O76 — Length-bucket padding for DM and broadcast text payloads.
 *
 * Pipeline order is **compress → pad → encrypt → sign → chunk**. This file
 * owns the *pad* step: given a (possibly pre-compressed) plaintext blob,
 * pick the smallest bucket that fits and zero-pad up to that size before
 * the AEAD wrap. The receiver strips trailing zeros after decrypt using
 * the [Pad] / [Unpad] helpers below.
 *
 * **Buckets (six):** 64 B · 256 B · 1 KB · 4 KB · 16 KB · 64 KB.
 *
 * Chosen for the real content distribution: quick replies / acks fit in
 * 64 B without 50× overhead; typical chat in 256 B; paragraphs in 1 KB;
 * blog-length posts in 16 KB without auto-chunking; only true outliers
 * above 64 KB go to the chunker. The 16 KB bucket sits exactly at the
 * INFRA/REALTIME ceiling (`TrafficClass.kt`) so anything above demotes to
 * BULK class anyway.
 *
 * **Privacy leakage:** with six buckets, an observer learns at most
 * `log2(6) ≈ 2.6 bits` of length information per encrypted message — vs
 * the full byte-length leak the current un-padded raw-bytes wire format
 * exposes today. Worst-case overhead is `~4×` (a 65-byte payload pads to
 * 256 B; bad luck threshold).
 *
 * **Why zeros, not random?** TLS 1.3 pattern. The AEAD makes ciphertext
 * indistinguishable from any other ciphertext regardless of what was
 * inside the plaintext, so padding bytes don't need to be unpredictable —
 * they only need to be removable by the receiver without ambiguity. Zeros
 * stripped from the trailing end is the standard unambiguous scheme when
 * the original length is signalled alongside (we carry it in the AD or
 * the `_ext` field; see [Padded.originalLength]).
 *
 * **What NOT to do here:** never use OS sensor entropy (gyro / temp /
 * camera) as a CSPRNG upgrade. A kernel-seeded CSPRNG cannot be
 * strengthened by XORing low-entropy sources, and reading sensors adds
 * permission prompts, battery drain, and a fingerprinting vector. See
 * CLAUDE.md O76 row for the full rationale.
 *
 * **CRIME/BREACH constraint reminder:** never share a compression context
 * across messages, and never mix attacker-controlled bytes with secret
 * bytes inside one compression pass. Current DM model is safe (sender
 * controls 100% of plaintext); any future group-chat envelope (O52) or
 * header-mixing scheme must audit before turning compression on for that
 * path.
 */
object PaddingBuckets {

    /** The six bucket sizes, ascending. Stored as `Int` because none exceed 2^31. */
    val SIZES: IntArray = intArrayOf(
        64,
        256,
        1_024,
        4_096,
        16_384,
        65_536,
    )

    /** Anything strictly larger than this goes to the chunker; the wire never carries one message above. */
    const val MAX_SINGLE_MESSAGE: Int = 65_536

    /**
     * Pick the smallest bucket index that fits [length] bytes, or `-1` if
     * the input exceeds [MAX_SINGLE_MESSAGE] (caller chunks instead).
     */
    fun bucketIndexFor(length: Int): Int {
        require(length >= 0) { "negative length: $length" }
        for ((i, size) in SIZES.withIndex()) if (length <= size) return i
        return -1
    }

    /** Bucket size for [length], or `-1` for chunker path. */
    fun bucketSizeFor(length: Int): Int {
        val i = bucketIndexFor(length)
        return if (i < 0) -1 else SIZES[i]
    }
}

/**
 * Padded blob: the bytes that go into the AEAD, plus the metadata the
 * receiver needs to strip padding correctly.
 *
 * [bucketIndex] is the byte that rides in the message `_ext` alongside
 * `compressed: Boolean`; together they cost 2 bytes of wire overhead.
 * [originalLength] is duplicated in the AEAD's associated data so the
 * receiver can't be tricked by a flipped padding byte (the AEAD tag
 * covers it; flipping would break decryption).
 */
data class Padded(
    val bytes: ByteArray,
    val bucketIndex: Int,
    val originalLength: Int,
) {
    override fun equals(other: Any?) = other is Padded
        && other.bucketIndex == bucketIndex
        && other.originalLength == originalLength
        && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + bucketIndex * 17 + originalLength
}

object Pad {
    /**
     * Pad [input] to the smallest bucket that fits. Returns null if
     * [input] is too large for any bucket — caller should chunk.
     */
    fun pad(input: ByteArray): Padded? {
        val idx = PaddingBuckets.bucketIndexFor(input.size)
        if (idx < 0) return null
        val size = PaddingBuckets.SIZES[idx]
        if (size == input.size) return Padded(input.copyOf(), idx, input.size)
        val out = ByteArray(size)
        input.copyInto(out)
        // Remaining bytes are already 0 from ByteArray init.
        return Padded(out, idx, input.size)
    }
}

object Unpad {
    /**
     * Strip padding using the trusted [originalLength] (recovered from
     * AEAD-protected associated data). Validates that [padded] is at
     * least [originalLength] bytes — anything else is a tampered or
     * corrupt frame and returns null.
     */
    fun unpad(padded: ByteArray, originalLength: Int): ByteArray? {
        if (originalLength < 0) return null
        if (originalLength > padded.size) return null
        return padded.copyOfRange(0, originalLength)
    }
}
