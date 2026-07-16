package com.rumor.mesh.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class HkdfSha256Test {

    private fun hexOf(b: ByteArray): String =
        b.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    private fun fromHex(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexDigit(hex[i*2]); val lo = hexDigit(hex[i*2+1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> error("not a hex digit: $c")
    }

    // ── RFC 5869 Appendix A test vectors ──────────────────────────────────────

    /**
     * RFC 5869 Test Case 1 — Basic test case with SHA-256.
     *   IKM  = 0x0b * 22
     *   salt = 0x000102030405060708090a0b0c
     *   info = 0xf0f1f2f3f4f5f6f7f8f9
     *   L    = 42
     *   PRK  = 077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5
     *   OKM  = 3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf
     *          34007208d5b887185865
     */
    @Test fun `RFC 5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = fromHex("000102030405060708090a0b0c")
        val info = fromHex("f0f1f2f3f4f5f6f7f8f9")
        val prk = HkdfSha256.extract(salt, ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            hexOf(prk),
        )
        val okm = HkdfSha256.expand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hexOf(okm),
        )
        val combined = HkdfSha256.deriveKey(salt, ikm, info, 42)
        assertEquals(hexOf(okm), hexOf(combined),
            "deriveKey must equal expand(extract(salt, ikm), info, L)")
    }

    /**
     * RFC 5869 Test Case 3 — Empty salt and info.
     *   IKM  = 0x0b * 22
     *   salt = empty
     *   info = empty
     *   L    = 42
     *   PRK  = 19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04
     *   OKM  = 8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d
     *          9d201395faa4b61a96c8
     */
    @Test fun `RFC 5869 test case 3 (empty salt and info)`() {
        val ikm = ByteArray(22) { 0x0b }
        val prk = HkdfSha256.extract(ByteArray(0), ikm)
        assertEquals(
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
            hexOf(prk),
        )
        val okm = HkdfSha256.expand(prk, ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            hexOf(okm),
        )
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test fun `extract with empty salt uses zero-byte salt per RFC default`() {
        val ikm = "anything".encodeToByteArray()
        val empty = HkdfSha256.extract(ByteArray(0), ikm)
        val zero32 = HkdfSha256.extract(ByteArray(32), ikm)
        assertEquals(hexOf(empty), hexOf(zero32),
            "empty salt must behave identically to a 32-byte zero salt (RFC 5869 §2.2)")
    }

    @Test fun `expand length=32 returns one SHA-256 block`() {
        val prk = ByteArray(32) { 0x42 }
        val out = HkdfSha256.expand(prk, "info".encodeToByteArray(), 32)
        assertEquals(32, out.size)
    }

    @Test fun `expand length=64 returns two blocks of stable content`() {
        val prk = ByteArray(32) { 0x42 }
        val out64 = HkdfSha256.expand(prk, "info".encodeToByteArray(), 64)
        val out32 = HkdfSha256.expand(prk, "info".encodeToByteArray(), 32)
        // First 32 bytes of length=64 must equal length=32 output (T(1) is identical).
        assertEquals(hexOf(out32), hexOf(out64.sliceArray(0 until 32)))
        assertEquals(64, out64.size)
    }

    @Test fun `expand length=1 returns a single byte`() {
        val prk = ByteArray(32) { 0x42 }
        val out = HkdfSha256.expand(prk, ByteArray(0), 1)
        assertEquals(1, out.size)
    }

    @Test fun `expand rejects zero length`() {
        assertFails { HkdfSha256.expand(ByteArray(32), ByteArray(0), 0) }
    }

    @Test fun `expand rejects negative length`() {
        assertFails { HkdfSha256.expand(ByteArray(32), ByteArray(0), -1) }
    }

    @Test fun `expand rejects length above 255 blocks`() {
        // 255 * 32 = 8160 is the maximum; 8161 must fail.
        assertFails { HkdfSha256.expand(ByteArray(32), ByteArray(0), 8161) }
    }

    @Test fun `different info strings produce different keys (domain separation)`() {
        val prk = ByteArray(32) { 0x42 }
        val a = HkdfSha256.expand(prk, "context-a".encodeToByteArray(), 32)
        val b = HkdfSha256.expand(prk, "context-b".encodeToByteArray(), 32)
        assertFalse(a.contentEquals(b),
            "info-string domain separation is the whole point of HKDF — different " +
                "info must produce different keys.")
    }
}
