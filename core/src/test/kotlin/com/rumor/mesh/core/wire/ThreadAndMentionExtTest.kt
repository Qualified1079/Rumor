package com.rumor.mesh.core.wire

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadAndMentionExtTest {

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

    // ── replyTo ───────────────────────────────────────────────────────────────

    @Test fun `absent ext yields null replyTo`() {
        assertNull(blank().replyTo)
    }

    @Test fun `withReplyTo round-trips`() {
        val m = blank().withReplyTo("parent-id-123")
        assertEquals("parent-id-123", m.replyTo)
    }

    @Test fun `withReplyTo null clears the field`() {
        val m = blank().withReplyTo("first").withReplyTo(null)
        assertNull(m.replyTo)
    }

    // ── mentions ──────────────────────────────────────────────────────────────

    @Test fun `absent ext yields empty mentions list`() {
        val m = blank()
        assertEquals(emptyList(), m.mentions)
    }

    @Test fun `withMentions round-trips`() {
        val m = blank().withMentions(listOf("alice", "bob", "charlie"))
        assertEquals(listOf("alice", "bob", "charlie"), m.mentions)
    }

    @Test fun `withMentions empty list clears the field`() {
        val seeded = blank().withMentions(listOf("a", "b"))
        assertEquals(listOf("a", "b"), seeded.mentions)
        val cleared = seeded.withMentions(emptyList())
        // Empty list and absent field collapse to the same observable: empty list.
        assertEquals(emptyList(), cleared.mentions)
    }

    @Test fun `malformed mentions field returns null (caller can distinguish from empty)`() {
        // Manually plant a non-array value at the mentions key — simulates a
        // tampered or pre-v0.2 message claiming the field.
        val tampered = blank().copy(
            ext = mapOf(ThreadAndMentionExt.KEY_MENTIONS to JsonPrimitive("not-an-array"))
        )
        assertNull(tampered.mentions, "non-array value at mentions key must yield null")
    }

    // ── interaction ───────────────────────────────────────────────────────────

    @Test fun `replyTo and mentions coexist in the same ext map`() {
        val m = blank()
            .withReplyTo("parent")
            .withMentions(listOf("bob"))
        assertEquals("parent", m.replyTo)
        assertEquals(listOf("bob"), m.mentions)
    }

    @Test fun `withReplyTo preserves other ext fields including mentions`() {
        val m = blank()
            .withMentions(listOf("bob"))
            .withReplyTo("parent")
        assertEquals("parent", m.replyTo)
        assertEquals(listOf("bob"), m.mentions)
    }

    @Test fun `withMentions preserves other ext fields including replyTo`() {
        val m = blank()
            .withReplyTo("parent")
            .withMentions(listOf("bob", "alice"))
        assertEquals("parent", m.replyTo)
        assertEquals(listOf("bob", "alice"), m.mentions)
    }

    @Test fun `clearing replyTo then mentions leaves ext null`() {
        val m = blank().withReplyTo("p").withMentions(listOf("a"))
        assertTrue(m.ext != null && m.ext!!.isNotEmpty())
        val cleared = m.withReplyTo(null).withMentions(emptyList())
        assertNull(cleared.ext, "clearing all known _ext keys should yield null ext (no empty map litter)")
    }

    @Test fun `field-name constants match the documented short keys`() {
        // Drift guard — if a refactor renames KEY_REPLY_TO or KEY_MENTIONS,
        // every existing message in the wild that uses the old name becomes
        // unreadable. Field names are forever (see RENAMED_FIELDS_NEVER_REUSE).
        assertEquals("replyTo", ThreadAndMentionExt.KEY_REPLY_TO)
        assertEquals("mentions", ThreadAndMentionExt.KEY_MENTIONS)
    }

    @Test fun `withMentions encodes as a JsonArray of JsonPrimitive strings`() {
        // Wire-format pin — receivers expect JSON `["a","b"]`, not `["a","b"]` as a
        // single string or any other shape. If this fails, the wire format drifted.
        val m = blank().withMentions(listOf("a", "b"))
        val arr = m.ext?.get(ThreadAndMentionExt.KEY_MENTIONS) as? JsonArray
        assertTrue(arr != null && arr.size == 2)
        assertTrue(arr[0] is JsonPrimitive && (arr[0] as JsonPrimitive).content == "a")
        assertTrue(arr[1] is JsonPrimitive && (arr[1] as JsonPrimitive).content == "b")
    }
}
