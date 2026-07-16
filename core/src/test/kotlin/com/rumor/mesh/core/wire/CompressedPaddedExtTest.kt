package com.rumor.mesh.core.wire

import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompressedPaddedExtTest {
    private fun blank() = RumorMessage(
        id = "test",
        senderId = "alice",
        senderPublicKey = "pk",
        sequenceNumber = 1L,
        sentAtMs = 0L,
        type = MessageType.BROADCAST,
        hopsToLive = 5,
        payload = MessagePayload(ContentType.TEXT, "hi"),
        signature = "sig",
    )

    @Test fun `absent ext means uncompressed and -1 defaults`() {
        val m = blank()
        assertFalse(m.isCompressed)
        assertEquals(-1, m.compressionBucketIndex)
        assertEquals(-1, m.compressionOriginalLength)
    }

    @Test fun `withCompressionMetadata sets all three fields and round-trips`() {
        val m = blank().withCompressionMetadata(bucketIndex = 2, originalLength = 137)
        assertTrue(m.isCompressed)
        assertEquals(2, m.compressionBucketIndex)
        assertEquals(137, m.compressionOriginalLength)
    }

    @Test fun `withCompressionMetadata preserves other ext fields`() {
        val m = blank()
        val withTtl = m.copy(ext = mapOf(
            "floodedHops" to kotlinx.serialization.json.JsonPrimitive(3),
        ))
        val compressed = withTtl.withCompressionMetadata(1, 50)
        assertTrue(compressed.isCompressed)
        assertEquals(3, (compressed.ext!!["floodedHops"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
    }
}
