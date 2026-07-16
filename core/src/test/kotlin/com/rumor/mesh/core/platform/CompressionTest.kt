package com.rumor.mesh.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompressionTest {

    @Test fun `round-trip on a small text payload`() {
        val plain = "Hello, mesh.".encodeToByteArray()
        val z = Compression.deflate(plain)
        val back = Compression.inflate(z, maxOutputBytes = 1024)
        assertNotNull(back)
        assertTrue(back.contentEquals(plain))
    }

    @Test fun `round-trip on the empty payload`() {
        val z = Compression.deflate(ByteArray(0))
        val back = Compression.inflate(z, maxOutputBytes = 16)
        assertNotNull(back)
        assertEquals(0, back.size)
    }

    @Test fun `compresses a highly-redundant payload below the original size`() {
        val plain = "abcdefghijklm ".repeat(200).encodeToByteArray()  // ~2.8 KB, very repetitive
        val z = Compression.deflate(plain)
        assertTrue(z.size < plain.size, "deflate did not shrink: ${z.size} vs ${plain.size}")
        val back = Compression.inflate(z, maxOutputBytes = plain.size + 64)
        assertNotNull(back); assertTrue(back.contentEquals(plain))
    }

    @Test fun `round-trip on random-looking bytes (compression may not shrink)`() {
        val plain = ByteArray(512) { (it * 37 xor 0x5A).toByte() }
        val z = Compression.deflate(plain)
        val back = Compression.inflate(z, maxOutputBytes = 4096)
        assertNotNull(back); assertTrue(back.contentEquals(plain))
        // We don't assert on size — pseudo-random doesn't compress.
    }

    @Test fun `inflate returns null on truncated stream`() {
        val plain = "the quick brown fox".repeat(20).encodeToByteArray()
        val z = Compression.deflate(plain)
        val truncated = z.copyOfRange(0, z.size - 3)
        assertNull(Compression.inflate(truncated, maxOutputBytes = 4096))
    }

    @Test fun `inflate returns null on garbage input`() {
        val garbage = ByteArray(32) { 0xFF.toByte() }
        assertNull(Compression.inflate(garbage, maxOutputBytes = 4096))
    }

    @Test fun `inflate respects maxOutputBytes (zip-bomb defense)`() {
        // 64 KB of zero bytes compresses to ~70 bytes. Setting cap below
        // the real output must force inflate to return null without
        // allocating gigabytes.
        val plain = ByteArray(65_536)
        val z = Compression.deflate(plain)
        assertTrue(z.size < 500, "zeros should compress to a small blob (got ${z.size})")
        // With cap 1024, the inflate must refuse — the legitimate output is 65 KB.
        assertNull(Compression.inflate(z, maxOutputBytes = 1024))
    }

    @Test fun `inflate returns null on negative or zero maxOutputBytes`() {
        val z = Compression.deflate("anything".encodeToByteArray())
        assertNull(Compression.inflate(z, maxOutputBytes = 0))
        assertNull(Compression.inflate(z, maxOutputBytes = -1))
    }

    @Test fun `round-trip across the 64 KB max single message size`() {
        // The chunker takes over above 64 KB, but at exactly 64 KB
        // (top bucket) we still ride a single deflate.
        val plain = "rumor mesh test payload ".repeat(3000).encodeToByteArray()  // > 64 KB
        val truncated = plain.copyOfRange(0, 65_536)
        val z = Compression.deflate(truncated)
        val back = Compression.inflate(z, maxOutputBytes = 65_536)
        assertNotNull(back); assertTrue(back.contentEquals(truncated))
    }
}
