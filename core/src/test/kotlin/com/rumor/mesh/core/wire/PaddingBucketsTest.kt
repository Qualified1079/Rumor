package com.rumor.mesh.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaddingBucketsTest {

    @Test
    fun `bucket selection rounds up to the smallest fit`() {
        assertEquals(0, PaddingBuckets.bucketIndexFor(0))
        assertEquals(0, PaddingBuckets.bucketIndexFor(1))
        assertEquals(0, PaddingBuckets.bucketIndexFor(64))
        assertEquals(1, PaddingBuckets.bucketIndexFor(65))
        assertEquals(1, PaddingBuckets.bucketIndexFor(256))
        assertEquals(2, PaddingBuckets.bucketIndexFor(257))
        assertEquals(2, PaddingBuckets.bucketIndexFor(1024))
        assertEquals(3, PaddingBuckets.bucketIndexFor(1025))
        assertEquals(4, PaddingBuckets.bucketIndexFor(16_384))
        assertEquals(5, PaddingBuckets.bucketIndexFor(16_385))
        assertEquals(5, PaddingBuckets.bucketIndexFor(65_536))
    }

    @Test
    fun `oversized payload returns minus one (caller chunks)`() {
        assertEquals(-1, PaddingBuckets.bucketIndexFor(65_537))
        assertEquals(-1, PaddingBuckets.bucketIndexFor(1_000_000))
        assertEquals(-1, PaddingBuckets.bucketSizeFor(65_537))
    }

    @Test
    fun `pad fills smaller payloads with zeros up to the bucket`() {
        val input = "hello".encodeToByteArray()  // 5 bytes → 64-byte bucket
        val padded = Pad.pad(input)
        assertNotNull(padded)
        assertEquals(64, padded.bytes.size)
        assertEquals(0, padded.bucketIndex)
        assertEquals(5, padded.originalLength)
        assertTrue(padded.bytes.copyOfRange(0, 5).contentEquals(input))
        // Remainder must be zeros (TLS 1.3 pattern, not random — see file docstring).
        for (i in 5 until 64) assertEquals(0.toByte(), padded.bytes[i])
    }

    @Test
    fun `pad on exact-bucket-size input does not allocate larger`() {
        val input = ByteArray(64) { 0xAB.toByte() }
        val padded = Pad.pad(input)
        assertNotNull(padded)
        assertEquals(64, padded.bytes.size)
        assertEquals(0, padded.bucketIndex)
        assertEquals(64, padded.originalLength)
    }

    @Test
    fun `pad on oversized returns null`() {
        val tooBig = ByteArray(65_537)
        assertNull(Pad.pad(tooBig))
    }

    @Test
    fun `round trip pad and unpad recovers the original`() {
        for (size in listOf(0, 1, 5, 64, 65, 256, 1024, 4095, 16_384, 65_535, 65_536)) {
            val input = ByteArray(size) { (it and 0xFF).toByte() }
            val padded = Pad.pad(input)
            assertNotNull(padded, "size=$size should pad")
            val recovered = Unpad.unpad(padded.bytes, padded.originalLength)
            assertNotNull(recovered, "size=$size should unpad")
            assertTrue(recovered.contentEquals(input), "size=$size content mismatch")
        }
    }

    @Test
    fun `unpad rejects originalLength larger than padded buffer`() {
        val padded = ByteArray(64)
        assertNull(Unpad.unpad(padded, 65))
        assertNull(Unpad.unpad(padded, Int.MAX_VALUE))
    }

    @Test
    fun `unpad rejects negative originalLength`() {
        val padded = ByteArray(64)
        assertNull(Unpad.unpad(padded, -1))
    }

    @Test
    fun `bucket sizes match the six declared in the docstring`() {
        assertEquals(listOf(64, 256, 1024, 4096, 16_384, 65_536), PaddingBuckets.SIZES.toList())
    }

    @Test
    fun `worst-case overhead is bounded near 4x`() {
        // 65 bytes pads to 256 (≈ 3.94×). 257 → 1024 (≈ 3.98×). 1025 → 4096 (≈ 4.0×).
        // These are the worst-case ratios within each bucket — confirm none exceed 4×.
        for (size in listOf(65, 257, 1025, 4097, 16_385)) {
            val bucket = PaddingBuckets.bucketSizeFor(size)
            val ratio = bucket.toDouble() / size.toDouble()
            assertTrue(ratio <= 4.0, "size=$size bucket=$bucket ratio=$ratio exceeds 4×")
        }
    }
}
