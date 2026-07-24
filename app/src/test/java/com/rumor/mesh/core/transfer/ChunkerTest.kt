package com.rumor.mesh.core.transfer

import com.rumor.mesh.core.model.Chunk
import com.rumor.mesh.core.model.ContentType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {

    @Test
    fun `single-chunk round-trip`() {
        val data = "Hello, mesh!".toByteArray()
        val (meta, chunks) = Chunker.chunk(data, ContentType.TEXT, chunkSize = 1_000)

        assertEquals(1, meta.totalChunks)
        assertEquals(1, chunks.size)
        assertEquals(data.size.toLong(), meta.totalBytes)

        val result = Chunker.reassemble(chunks, meta)
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `multi-chunk round-trip`() {
        val data = ByteArray(250_000) { it.toByte() }
        val chunkSize = 60_000
        val (meta, chunks) = Chunker.chunk(data, ContentType.FILE, chunkSize = chunkSize)

        val expectedChunks = (data.size + chunkSize - 1) / chunkSize
        assertEquals(expectedChunks, meta.totalChunks)
        assertEquals(expectedChunks, chunks.size)

        val result = Chunker.reassemble(chunks, meta)
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `chunk indices are sequential starting at zero`() {
        val data = ByteArray(180_000) { 0xAB.toByte() }
        val (_, chunks) = Chunker.chunk(data, ContentType.IMAGE, chunkSize = 60_000)
        val indices = chunks.map { it.chunkIndex }
        assertEquals(listOf(0, 1, 2), indices)
    }

    @Test
    fun `reassemble tolerates shuffled chunk order`() {
        val data = ByteArray(120_001) { it.toByte() }
        val (meta, chunks) = Chunker.chunk(data, ContentType.FILE, chunkSize = 60_000)

        val shuffled = chunks.reversed()
        val result = Chunker.reassemble(shuffled, meta)
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `reassemble returns null on missing chunk`() {
        val data = ByteArray(120_001) { 1 }
        val (meta, chunks) = Chunker.chunk(data, ContentType.FILE, chunkSize = 60_000)

        val incomplete = chunks.dropLast(1)
        assertNull(Chunker.reassemble(incomplete, meta))
    }

    @Test
    fun `reassemble returns null on corrupted data`() {
        val data = ByteArray(100) { it.toByte() }
        val (meta, chunks) = Chunker.chunk(data, ContentType.TEXT, chunkSize = 1_000)

        // Corrupt the base64 data of the first chunk.
        val corrupted = chunks.map { chunk ->
            if (chunk.chunkIndex == 0) {
                val decoded = java.util.Base64.getDecoder().decode(chunk.data)
                decoded[0] = (decoded[0].toInt() xor 0xFF).toByte()
                chunk.copy(data = java.util.Base64.getEncoder().encodeToString(decoded))
            } else chunk
        }
        assertNull(Chunker.reassemble(corrupted, meta))
    }

    @Test
    fun `reassemble returns null when chunk index gap exists`() {
        // Non-uniform payload so each chunk holds distinct bytes — a uniform
        // fill would make chunk 0 and chunk 1 identical and the substitution
        // below a no-op that legitimately reassembles.
        val data = ByteArray(180_000) { (it % 255).toByte() }
        val (meta, chunks) = Chunker.chunk(data, ContentType.FILE, chunkSize = 60_000)

        // Remove chunk 1, replace with a duplicate of chunk 0 at wrong index.
        val bad = chunks.filter { it.chunkIndex != 1 } + Chunk(
            transferId = chunks[0].transferId,
            chunkIndex = 1,   // right index but wrong data — hash should catch it
            totalChunks = meta.totalChunks,
            data = chunks[0].data,
        )
        // Hash mismatch means reassemble returns null.
        assertNull(Chunker.reassemble(bad, meta))
    }

    @Test
    fun `missingIndices returns correct set`() {
        val received = setOf(0, 2, 4)
        val missing = Chunker.missingIndices(received, totalChunks = 5)
        assertEquals(listOf(1, 3), missing)
    }

    @Test
    fun `missingIndices empty when all chunks received`() {
        val received = (0 until 10).toSet()
        val missing = Chunker.missingIndices(received, totalChunks = 10)
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `missingIndicesWindowed never materializes more than the window`() {
        // O109: a lying totalChunks must not build a giant list. Window=4.
        val missing = Chunker.missingIndicesWindowed(
            received = emptySet(), totalChunks = Int.MAX_VALUE, window = 4,
        )
        assertEquals(listOf(0, 1, 2, 3), missing)
    }

    @Test
    fun `missingIndicesWindowed skips received and fills the window from the front`() {
        val missing = Chunker.missingIndicesWindowed(
            received = setOf(0, 1, 2), totalChunks = 100, window = 3,
        )
        assertEquals(listOf(3, 4, 5), missing)
    }

    @Test
    fun `missingIndicesWindowed returns fewer than window near the end`() {
        val missing = Chunker.missingIndicesWindowed(
            received = setOf(0), totalChunks = 3, window = 1024,
        )
        assertEquals(listOf(1, 2), missing)
    }

    @Test
    fun `isPlausibleMetadata accepts a self-consistent claim`() {
        // 1500 bytes / 600-byte chunks = 3 chunks.
        assertTrue(Chunker.isPlausibleMetadata(totalChunks = 3, chunkSize = 600, totalBytes = 1500))
    }

    @Test
    fun `isPlausibleMetadata rejects Int-MAX chunk count`() {
        // The O108 OOM shape: tiny bytes, absurd chunk count.
        assertTrue(!Chunker.isPlausibleMetadata(Int.MAX_VALUE, chunkSize = 600, totalBytes = 1500))
    }

    @Test
    fun `isPlausibleMetadata rejects inconsistent chunk-count vs bytes`() {
        // Consistent value would be 3; claiming 1 is a lie that would truncate.
        assertTrue(!Chunker.isPlausibleMetadata(totalChunks = 1, chunkSize = 600, totalBytes = 1500))
    }

    @Test
    fun `isPlausibleMetadata rejects zero and oversize fields`() {
        assertTrue(!Chunker.isPlausibleMetadata(0, 600, 1500))
        assertTrue(!Chunker.isPlausibleMetadata(3, 0, 1500))
        assertTrue(!Chunker.isPlausibleMetadata(3, 600, 0))
        assertTrue(!Chunker.isPlausibleMetadata(3, Chunker.MAX_CHUNK_SIZE + 1, 1500))
        assertTrue(!Chunker.isPlausibleMetadata(3, 600, Chunker.MAX_TOTAL_BYTES + 1))
    }

    @Test
    fun `chunk output round-trips through isPlausibleMetadata`() {
        // Whatever the chunker itself produces must always be plausible.
        val (meta, _) = Chunker.chunk(ByteArray(5_000) { 7 }, ContentType.FILE)
        assertTrue(Chunker.isPlausibleMetadata(meta.totalChunks, meta.chunkSize, meta.totalBytes))
    }

    @Test
    fun `metadata fields are populated correctly`() {
        val data = ByteArray(1_500) { 42 }
        val (meta, _) = Chunker.chunk(
            data,
            ContentType.IMAGE,
            mimeType = "image/jpeg",
            title = "photo.jpg",
            recipientId = "user123",
            chunkSize = 1_000,
        )

        assertEquals(ContentType.IMAGE, meta.contentType)
        assertEquals("image/jpeg", meta.mimeType)
        assertEquals("photo.jpg", meta.title)
        assertEquals("user123", meta.recipientId)
        assertEquals(1_500L, meta.totalBytes)
        assertEquals(2, meta.totalChunks)
        assertEquals(1_000, meta.chunkSize)
        assertTrue("transferId must be non-empty", meta.transferId.isNotEmpty())
        assertTrue("contentHash must be non-empty", meta.contentHash.isNotEmpty())
    }

    @Test
    fun `chunk populates per-chunk hashes and content-group hash`() {
        val data = ByteArray(180_000) { (it % 251).toByte() }
        val (meta, chunks) = Chunker.chunk(data, ContentType.FILE, chunkSize = 60_000)

        assertNotNull(meta.chunkHashes)
        assertEquals(meta.totalChunks, meta.chunkHashes!!.size)
        // Every chunk self-identifies its interchangeable content group.
        assertTrue(chunks.all { it.contentHash == meta.contentHash })
        // Each per-chunk hash matches the chunk it indexes.
        chunks.forEach { c ->
            assertTrue("chunk ${c.chunkIndex} must verify", Chunker.verifyChunk(c, meta))
        }
    }

    @Test
    fun `identical content produces identical chunk hashes and group hash`() {
        // The interchange property: two independent sends of the same bytes yield
        // per-chunk-interchangeable chunks (same group hash, same per-chunk hashes)
        // even though the per-send transferId differs.
        val data = ByteArray(150_000) { (it * 7 % 255).toByte() }
        val (m1, _) = Chunker.chunk(data, ContentType.FILE, chunkSize = 60_000)
        val (m2, _) = Chunker.chunk(data, ContentType.FILE, chunkSize = 60_000)

        assertEquals(m1.contentHash, m2.contentHash)
        assertEquals(m1.chunkHashes, m2.chunkHashes)
        assertTrue("transferId is still per-send", m1.transferId != m2.transferId)
    }

    @Test
    fun `verifyChunk rejects a poisoned chunk before reassembly`() {
        val data = ByteArray(180_000) { (it % 251).toByte() }
        val (meta, chunks) = Chunker.chunk(data, ContentType.FILE, chunkSize = 60_000)

        val poisoned = chunks[1].copy(
            data = java.util.Base64.getEncoder().encodeToString(ByteArray(60_000) { 0x7F }),
        )
        assertTrue("good chunk verifies", Chunker.verifyChunk(chunks[0], meta))
        assertTrue("poisoned chunk is rejected", !Chunker.verifyChunk(poisoned, meta))
    }

    @Test
    fun `verifyChunk rejects a chunk claiming the wrong content group`() {
        val data = ByteArray(120_001) { it.toByte() }
        val (meta, chunks) = Chunker.chunk(data, ContentType.FILE, chunkSize = 60_000)

        val wrongGroup = chunks[0].copy(contentHash = "not-this-group")
        assertTrue(!Chunker.verifyChunk(wrongGroup, meta))
    }

    @Test
    fun `reassemble rejects a hash-valid frame carrying poisoned bytes`() {
        // The multi-seeder attack: a chunk whose bytes were swapped. Per-chunk
        // hashes catch it at the poisoned index, not only via the final hash.
        val data = ByteArray(180_000) { (it % 251).toByte() }
        val (meta, chunks) = Chunker.chunk(data, ContentType.FILE, chunkSize = 60_000)

        val poisoned = chunks.map { c ->
            if (c.chunkIndex == 2) c.copy(
                data = java.util.Base64.getEncoder().encodeToString(ByteArray(60_000) { 0x11 }),
            ) else c
        }
        assertNull(Chunker.reassemble(poisoned, meta))
    }

    @Test
    fun `reassemble still works for legacy metadata without per-chunk hashes`() {
        val data = ByteArray(150_000) { it.toByte() }
        val (meta, chunks) = Chunker.chunk(data, ContentType.FILE, chunkSize = 60_000)

        // Simulate a pre-content-addressing sender: strip the new fields.
        val legacyMeta = meta.copy(chunkHashes = null)
        val legacyChunks = chunks.map { it.copy(contentHash = null) }
        val result = Chunker.reassemble(legacyChunks, legacyMeta)
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty data throws`() {
        Chunker.chunk(ByteArray(0), ContentType.FILE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero chunkSize throws`() {
        Chunker.chunk(ByteArray(1), ContentType.FILE, chunkSize = 0)
    }
}
