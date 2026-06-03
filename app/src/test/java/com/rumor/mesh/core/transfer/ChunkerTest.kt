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
        // Each byte takes the value of its index → every chunk has different
        // content. Critical: the previous fixture `ByteArray(180_000) { 1 }`
        // made every chunk byte-identical, so replacing chunk 1 with chunk 0
        // produced the same reassembled output and the hash check passed.
        val data = ByteArray(180_000) { it.toByte() }
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

    @Test(expected = IllegalArgumentException::class)
    fun `empty data throws`() {
        Chunker.chunk(ByteArray(0), ContentType.FILE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero chunkSize throws`() {
        Chunker.chunk(ByteArray(1), ContentType.FILE, chunkSize = 0)
    }
}
