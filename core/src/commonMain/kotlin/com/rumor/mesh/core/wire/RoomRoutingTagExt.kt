package com.rumor.mesh.core.wire

import com.rumor.mesh.core.model.RumorMessage
import kotlinx.serialization.json.JsonPrimitive

/**
 * O79 — `_ext.rt` field accessor for the room routing tag.
 *
 * The tag itself is a 16-byte routing identifier produced by
 * [com.rumor.mesh.core.protocol.RoomRoutingTag]. On the wire it
 * rides as a Base64-encoded string in `_ext.rt`. Unsigned (in
 * `_ext`) — a relay could in principle flip the tag, but the
 * consequence is the message gets dropped at every honest peer
 * (no subscription matches a bogus tag), so the relay achieves
 * nothing useful by tampering.
 *
 * Mirrors the [withCompressionMetadata] / [withReplyTo] /
 * [withMentions] pattern: pure accessor + a single copy helper.
 */
object RoomRoutingTagExt {
    /** `_ext` key for the room routing tag. Reserved forever. */
    const val KEY_ROUTING_TAG: String = "rt"
}

/** Base64-encoded room routing tag, or null when not a room message. */
val RumorMessage.roomRoutingTag: String?
    get() = (ext?.get(RoomRoutingTagExt.KEY_ROUTING_TAG) as? JsonPrimitive)?.content

/** Set `_ext.rt`, returning a new message. Pass null to clear. */
fun RumorMessage.withRoomRoutingTag(tagBase64: String?): RumorMessage {
    val updated = (ext ?: emptyMap()).toMutableMap()
    if (tagBase64 == null) {
        updated.remove(RoomRoutingTagExt.KEY_ROUTING_TAG)
    } else {
        updated[RoomRoutingTagExt.KEY_ROUTING_TAG] = JsonPrimitive(tagBase64)
    }
    return copy(ext = if (updated.isEmpty()) null else updated)
}
