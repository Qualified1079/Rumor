package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.data.adapter.toEntity
import com.rumor.mesh.data.adapter.toMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression pin for the v10 schema fix: the O37 `_ext` map must survive the
 * Room entity round-trip. Before v10, MessageEntity had no ext column, so
 * every stored/relayed message silently lost its compression-AAD flags
 * (O76) — DMs then failed the AES-GCM tag check at display time. The
 * simulator's in-memory repo stores the object itself and can never catch
 * this; only the entity mapping can.
 */
class MessageEntityExtRoundTripTest {

    private fun msg(ext: Map<String, kotlinx.serialization.json.JsonElement>?) = RumorMessage(
        id = "cafebabecafebabecafebabecafebabe",
        senderId = "aa".repeat(32),
        senderPublicKey = "cGs=",
        sequenceNumber = 7,
        sentAtMs = 1_000L,
        type = MessageType.DIRECT,
        hopsToLive = 5,
        encryptedPayload = "ZXBoZW1lcmFs.Y2lwaGVydGV4dA==",
        recipientId = "bb".repeat(32),
        signature = "c2ln",
        ext = ext,
    )

    @Test
    fun `ext map survives entity round-trip`() {
        val original = msg(
            mapOf(
                "c" to JsonPrimitive(true),
                "cb" to JsonPrimitive(2),
                "cl" to JsonPrimitive(831),
                "t" to JsonPrimitive("dGFn"),
            )
        )
        val back = original.toEntity().toMessage()
        assertEquals(original.ext, back.ext)
        assertEquals(original, back)
    }

    @Test
    fun `null ext stays null`() {
        assertNull(msg(null).toEntity().toMessage().ext)
    }
}
