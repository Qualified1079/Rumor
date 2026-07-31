package com.rumor.mesh.core.wire

import com.rumor.mesh.core.model.RumorMessage
import kotlinx.serialization.json.JsonPrimitive

/**
 * O79 — `_ext.rt` field accessor for the room routing tag.
 *
 * The tag itself is a 16-byte routing identifier produced by
 * [com.rumor.mesh.core.protocol.RoomRoutingTag]. On the wire it
 * rides as a Base64-encoded string in `_ext.rt`.
 *
 * O157: although `_ext` is generally unsigned, for `ROOM_MESSAGE`
 * the routing tag IS bound into the signed transcript
 * ([com.rumor.mesh.core.protocol.MessageStore.signableBytes]) — it
 * is stamped before signing in `buildMessage`. This closes the
 * keyless-retag attack: without it, an attacker could take any
 * validly-signed OPEN room message and overwrite `_ext.rt` with the
 * (public, keyless) tag of a different room, re-homing the sender's
 * content into a room they never posted to. Any post-signing change
 * to a room message's tag now breaks the signature and is dropped at
 * verify — not merely unmatched.
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
