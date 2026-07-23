package com.rumor.mesh.plugin.meshtastic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * O118: the hand-rolled reader's length paths must reject adversarial
 * lengths with an exception (callers runCatching) — never desync. The two
 * session-found gaps: readSubMessage had zero bounds validation, and a
 * negative varint length passed the `pos + len <= end` sum check (rewinding
 * pos — silent corruption, worse than a crash).
 */
class MeshtasticProtobufBoundsTest {

    private fun writerBytes(block: (MeshtasticProtobuf.Writer) -> Unit): ByteArray =
        MeshtasticProtobuf.Writer().also(block).toByteArray()

    @Test
    fun `honest sub-message round-trips`() {
        val bytes = writerBytes { w ->
            w.writeSubMessage(2) { sub -> sub.writeString(1, "hello") }
        }
        val r = MeshtasticProtobuf.Reader(bytes)
        r.readVarint() // tag
        val text = r.readSubMessage { sub ->
            sub.readVarint() // inner tag
            sub.readString()
        }
        assertEquals("hello", text)
    }

    @Test
    fun `sub-message length past end throws instead of truncating silently`() {
        // len=100 but only 2 payload bytes follow.
        val bytes = byteArrayOf(100, 1, 2)
        val r = MeshtasticProtobuf.Reader(bytes)
        assertThrows(IllegalArgumentException::class.java) {
            r.readSubMessage { it.readVarint() }
        }
    }

    @Test
    fun `negative varint length throws in readBytes`() {
        // Varint encoding of -1 as int64: 10 bytes of 0xFF then 0x01 —
        // readInt() truncates to -1; the len >= 0 guard must catch it.
        val neg = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x01,
        )
        val r = MeshtasticProtobuf.Reader(neg)
        assertThrows(IllegalArgumentException::class.java) { r.readBytes() }
    }

    @Test
    fun `negative sub-message length throws not rewinds`() {
        val neg = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x01,
        )
        val r = MeshtasticProtobuf.Reader(neg)
        assertThrows(IllegalArgumentException::class.java) {
            r.readSubMessage { it.readVarint() }
        }
    }

    @Test
    fun `huge positive length does not int-overflow past the bound check`() {
        // Int.MAX_VALUE varint then no payload: pos+len would wrap negative in
        // Int math and pass <= end; the Long-math guard must throw.
        val huge = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07,
        )
        val r = MeshtasticProtobuf.Reader(huge)
        assertThrows(IllegalArgumentException::class.java) { r.readBytes() }
    }

    @Test
    fun `skipField rejects negative length-delimited length`() {
        val neg = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x01,
        )
        val r = MeshtasticProtobuf.Reader(neg)
        assertThrows(IllegalArgumentException::class.java) { r.skipField(2) }
    }
}
