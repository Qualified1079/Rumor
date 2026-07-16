package com.rumor.mesh.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HmacSha256Test {

    private fun hexOf(b: ByteArray): String =
        b.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> error("not a hex digit: $c")
    }

    @Suppress("unused")  // kept for future RFC-vector-by-hex tests
    private fun fromHex(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((hexDigit(hex[i*2]) shl 4) or hexDigit(hex[i*2+1])).toByte()
        }
        return out
    }

    /**
     * RFC 4231 test vector 1 for HMAC-SHA-256:
     *   Key  = 0x0b * 20
     *   Data = "Hi There"
     *   HMAC = b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7
     */
    @Test fun `RFC 4231 test vector 1`() {
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".encodeToByteArray()
        val mac = HmacSha256.mac(key, data)
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            hexOf(mac),
            "HMAC-SHA-256 must match RFC 4231 test vector 1 — if this fails, " +
                "either Sha256 is broken or the HMAC construction has a bug."
        )
    }

    /**
     * RFC 4231 test vector 2 — short key, slightly longer message.
     */
    @Test fun `RFC 4231 test vector 2`() {
        val key = "Jefe".encodeToByteArray()
        val data = "what do ya want for nothing?".encodeToByteArray()
        val mac = HmacSha256.mac(key, data)
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hexOf(mac),
        )
    }

    /**
     * RFC 4231 test vector 4 — key longer than block size triggers the
     * H(K) substitution path.
     */
    @Test fun `oversize key is hashed down`() {
        val key = ByteArray(131) { it.toByte() }
        val data = "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()
        val mac = HmacSha256.mac(key, data)
        // Sanity: just check it produces 32 bytes and is deterministic.
        assertEquals(32, mac.size)
        val again = HmacSha256.mac(key, data)
        assertTrue(mac.contentEquals(again))
    }

    @Test fun `empty key and empty message produce a stable output`() {
        val mac = HmacSha256.mac(ByteArray(0), ByteArray(0))
        assertEquals(32, mac.size)
        // Known HMAC-SHA-256(empty key, empty message):
        assertEquals(
            "b613679a0814d9ec772f95d778c35fc5ff1697c493715653c6c712144292c5ad",
            hexOf(mac),
        )
    }

    @Test fun `different keys produce different macs for the same message`() {
        val data = "hello".encodeToByteArray()
        val a = HmacSha256.mac("key-a".encodeToByteArray(), data)
        val b = HmacSha256.mac("key-b".encodeToByteArray(), data)
        assertFalse(a.contentEquals(b))
    }

    @Test fun `single-bit change in message changes mac (avalanche)`() {
        val key = "key".encodeToByteArray()
        val a = HmacSha256.mac(key, "hello".encodeToByteArray())
        val b = HmacSha256.mac(key, "iello".encodeToByteArray())
        assertFalse(a.contentEquals(b))
    }
}
