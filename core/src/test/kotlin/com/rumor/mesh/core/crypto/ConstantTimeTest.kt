package com.rumor.mesh.core.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConstantTimeTest {

    @Test
    fun `equal arrays match`() {
        val a = byteArrayOf(1, 2, 3, 4, 5)
        assertTrue(ConstantTime.equals(a, a.copyOf()))
    }

    @Test
    fun `different length never matches`() {
        assertFalse(ConstantTime.equals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(ConstantTime.equals(ByteArray(0), byteArrayOf(1)))
    }

    @Test
    fun `single differing byte does not match regardless of position`() {
        val base = ByteArray(32) { it.toByte() }
        // first, middle, and last byte flips — all must be rejected.
        for (pos in intArrayOf(0, 16, 31)) {
            val other = base.copyOf().also { it[pos] = (it[pos] + 1).toByte() }
            assertFalse(ConstantTime.equals(base, other), "flip at $pos should not match")
        }
    }

    @Test
    fun `empty arrays match`() {
        assertTrue(ConstantTime.equals(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun `high-bit bytes compare correctly (no sign-extension bug)`() {
        // 0x80 vs 0x00 differ only in the sign bit — a naive Int compare that
        // sign-extends inconsistently could miss it.
        assertFalse(ConstantTime.equals(byteArrayOf(0x80.toByte()), byteArrayOf(0x00)))
        assertTrue(ConstantTime.equals(byteArrayOf(0xFF.toByte()), byteArrayOf(0xFF.toByte())))
    }
}
