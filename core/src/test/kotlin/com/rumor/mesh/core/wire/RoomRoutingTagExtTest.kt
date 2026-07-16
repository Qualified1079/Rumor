package com.rumor.mesh.core.wire

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomRoutingTagExtTest {

    private fun blank() = RumorMessage(
        id = "test",
        senderId = "alice",
        senderPublicKey = "pk",
        sequenceNumber = 1L,
        sentAtMs = 0L,
        type = MessageType.ROOM_MESSAGE,
        hopsToLive = 5,
        payload = MessagePayload(ContentType.TEXT, "hi"),
        signature = "sig",
    )

    @Test fun `absent ext means null routing tag`() {
        assertNull(blank().roomRoutingTag)
    }

    @Test fun `withRoomRoutingTag round-trips`() {
        val m = blank().withRoomRoutingTag("AAECAwQF")
        assertEquals("AAECAwQF", m.roomRoutingTag)
    }

    @Test fun `withRoomRoutingTag null clears the field`() {
        val m = blank().withRoomRoutingTag("first").withRoomRoutingTag(null)
        assertNull(m.roomRoutingTag)
    }

    @Test fun `key constant is "rt" (drift guard)`() {
        // Field name is forever per RENAMED_FIELDS_NEVER_REUSE — pin it.
        assertEquals("rt", RoomRoutingTagExt.KEY_ROUTING_TAG)
    }

    @Test fun `withRoomRoutingTag preserves other ext fields`() {
        val m = blank().copy(
            ext = mapOf("floodedHops" to JsonPrimitive(3))
        )
        val tagged = m.withRoomRoutingTag("tag-bytes")
        assertEquals("tag-bytes", tagged.roomRoutingTag)
        assertEquals(3, (tagged.ext!!["floodedHops"] as JsonPrimitive).content.toInt())
    }

    @Test fun `clearing rt leaves ext empty when no other fields`() {
        val m = blank().withRoomRoutingTag("tag").withRoomRoutingTag(null)
        // Same pattern as ThreadAndMentionExt — clear all known keys ->
        // null ext (no empty-map litter on the wire).
        assertNull(m.ext)
    }

    @Test fun `wire format pin — rt is a JsonPrimitive string in ext`() {
        val m = blank().withRoomRoutingTag("foo")
        val raw = m.ext?.get("rt")
        assertTrue(raw is JsonPrimitive, "rt must be a JSON string primitive on the wire")
        assertEquals("foo", raw.content)
    }
}
