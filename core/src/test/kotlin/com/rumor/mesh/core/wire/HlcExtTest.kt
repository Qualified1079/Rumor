package com.rumor.mesh.core.wire

import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.time.HlcTimestamp
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** O95 — `_ext.hlc` wire carrier. Field name reserved forever. */
class HlcExtTest {

    private fun msg(ext: Map<String, kotlinx.serialization.json.JsonElement>? = null) = RumorMessage(
        id = "aa".repeat(16), senderId = "bb".repeat(32), senderPublicKey = "cGs=",
        sequenceNumber = 1, sentAtMs = 1_000, type = MessageType.BROADCAST,
        hopsToLive = 3, signature = "c2ln", ext = ext,
    )

    @Test
    fun `round-trips through _ext and pins the field name`() {
        val stamped = msg().withHlc(HlcTimestamp(1_700_000_000_123, 7))
        // Drift guard: the wire key is "hlc" forever (RENAMED_FIELDS policy).
        assertEquals(JsonPrimitive("1700000000123:7"), stamped.ext?.get("hlc"))
        assertEquals(HlcTimestamp(1_700_000_000_123, 7), stamped.hlcTimestamp)
    }

    @Test
    fun `absent and malformed values read as null`() {
        assertNull(msg().hlcTimestamp)
        assertNull(msg(mapOf("hlc" to JsonPrimitive("garbage"))).hlcTimestamp)
        assertNull(msg(mapOf("hlc" to JsonPrimitive(":3"))).hlcTimestamp)
        assertNull(msg(mapOf("hlc" to JsonPrimitive("123:"))).hlcTimestamp)
    }

    @Test
    fun `withHlc preserves other ext fields`() {
        val stamped = msg(mapOf("t" to JsonPrimitive("tag"))).withHlc(HlcTimestamp(5, 0))
        assertEquals(JsonPrimitive("tag"), stamped.ext?.get("t"))
        assertEquals(HlcTimestamp(5, 0), stamped.hlcTimestamp)
    }
}
