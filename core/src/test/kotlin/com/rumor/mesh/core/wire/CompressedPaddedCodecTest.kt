package com.rumor.mesh.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompressedPaddedCodecTest {

    @Test fun `round-trip on a typical chat message`() {
        val plain = "Hello, this is a test message in the Rumor mesh.".encodeToByteArray()
        val encoded = CompressedPaddedCodec.encodeForWire(plain)
        assertNotNull(encoded)
        // Compressed + padded must land in one of the six buckets.
        assertTrue(encoded.bytes.size in PaddingBuckets.SIZES.toList())
        val back = CompressedPaddedCodec.decodeFromWire(
            encoded.bytes,
            originalLength = encoded.originalLength,
            maxOutputBytes = 64 * 1024,
        )
        assertNotNull(back)
        assertTrue(back.contentEquals(plain))
    }

    @Test fun `round-trip on the empty payload (pads to smallest bucket)`() {
        val encoded = CompressedPaddedCodec.encodeForWire(ByteArray(0))
        assertNotNull(encoded)
        // Empty compresses to a small fixed deflate trailer (~2 bytes);
        // padded to 64-byte bucket.
        assertEquals(64, encoded.bytes.size)
        val back = CompressedPaddedCodec.decodeFromWire(
            encoded.bytes,
            originalLength = encoded.originalLength,
            maxOutputBytes = 1024,
        )
        assertNotNull(back); assertEquals(0, back.size)
    }

    @Test fun `large highly-redundant payload shrinks below the original bucket`() {
        // 4 KB of repetitive English compresses to a few hundred bytes
        // — should land in the 1 KB bucket, not the 4 KB bucket.
        val plain = "the quick brown fox jumps over the lazy dog ".repeat(95).encodeToByteArray()
        assertTrue(plain.size > 4000 && plain.size < 4500, "test setup: got ${plain.size}")
        val encoded = CompressedPaddedCodec.encodeForWire(plain)
        assertNotNull(encoded)
        assertTrue(encoded.bytes.size < 4096,
            "compressed+padded should be < 4 KB bucket, got ${encoded.bytes.size}")
        val back = CompressedPaddedCodec.decodeFromWire(
            encoded.bytes,
            originalLength = encoded.originalLength,
            maxOutputBytes = plain.size + 64,
        )
        assertNotNull(back); assertTrue(back.contentEquals(plain))
    }

    @Test fun `truncated originalLength fails the inflate step`() {
        val plain = "the quick brown fox jumps".repeat(50).encodeToByteArray()
        val encoded = CompressedPaddedCodec.encodeForWire(plain)
        assertNotNull(encoded)
        // Claim FEWER bytes than were actually compressed — inflate sees
        // a truncated stream and refuses. (Claiming MORE bytes works on
        // deflate because the end-of-stream marker terminates inflate
        // before the bogus trailing zeros matter; that path is benign.)
        assertTrue(encoded.originalLength > 8, "test setup: stream too short")
        val bogus = CompressedPaddedCodec.decodeFromWire(
            encoded.bytes,
            originalLength = encoded.originalLength - 8,
            maxOutputBytes = 4096,
        )
        assertNull(bogus)
    }

    @Test fun `originalLength larger than padded buffer returns null`() {
        val plain = "x".encodeToByteArray()
        val encoded = CompressedPaddedCodec.encodeForWire(plain)
        assertNotNull(encoded)
        assertNull(CompressedPaddedCodec.decodeFromWire(
            encoded.bytes,
            originalLength = encoded.bytes.size + 1,
            maxOutputBytes = 1024,
        ))
    }

    @Test fun `decode honours maxOutputBytes zip-bomb cap`() {
        // Encode 64 KB of zeros → compresses to ~70 bytes → pads to 256 B.
        val plain = ByteArray(65_536)
        val encoded = CompressedPaddedCodec.encodeForWire(plain)
        assertNotNull(encoded)
        // Receiver caps output at 1 KB — must refuse the legitimate 64 KB.
        assertNull(CompressedPaddedCodec.decodeFromWire(
            encoded.bytes,
            originalLength = encoded.originalLength,
            maxOutputBytes = 1024,
        ))
    }

    @Test fun `round-trip across every bucket boundary`() {
        // Choose plaintext sizes that, after compression, fall in different
        // buckets. Use random-ish bytes so compression doesn't surprise us.
        for (size in listOf(1, 100, 500, 2000, 8000, 30_000)) {
            val plain = ByteArray(size) { (it * 31 xor 0x5A).toByte() }
            val encoded = CompressedPaddedCodec.encodeForWire(plain)
            assertNotNull(encoded, "encode failed at size=$size")
            val back = CompressedPaddedCodec.decodeFromWire(
                encoded.bytes,
                originalLength = encoded.originalLength,
                maxOutputBytes = size + 256,
            )
            assertNotNull(back, "decode failed at size=$size")
            assertTrue(back.contentEquals(plain), "round-trip mismatch at size=$size")
        }
    }
}
