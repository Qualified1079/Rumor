package com.rumor.mesh.core.wire

import com.rumor.mesh.core.model.RumorMessage
import kotlinx.serialization.json.JsonPrimitive

/**
 * O53 — `_ext.t` field accessor for the sealed-sender recipient tag.
 *
 * The tag is the 32-byte HMAC-SHA-256 output from
 * [com.rumor.mesh.core.protocol.SealedSenderTag.tagFor]. Base64-encoded on
 * the wire. Coexistence phase: stamped on outbound DIRECTs alongside the
 * plaintext `recipientId` field so older peers still route correctly. A
 * future wire-format break removes the plaintext `recipientId` and makes
 * tag-matching the only routing path; until then, observers still see
 * the plaintext recipient — the privacy win lands when the plaintext field
 * is dropped.
 *
 * Unsigned (in `_ext`). A relay flipping the tag drops the message at
 * every honest recipient (no per-contact key produces the tampered tag),
 * which is no worse than dropping the message outright — relays already
 * have that capability.
 *
 * Mirrors [RoomRoutingTagExt] / [withCompressionMetadata] / [withReplyTo].
 */
object SealedSenderTagExt {
    /** `_ext` key for the sealed-sender tag. Reserved forever. */
    const val KEY_TAG: String = "t"
}

/** Base64-encoded sealed-sender tag, or null when not stamped. */
val RumorMessage.sealedSenderTag: String?
    get() = (ext?.get(SealedSenderTagExt.KEY_TAG) as? JsonPrimitive)?.content

/** Set `_ext.t`, returning a new message. Pass null to clear. */
fun RumorMessage.withSealedSenderTag(tagBase64: String?): RumorMessage {
    val updated = (ext ?: emptyMap()).toMutableMap()
    if (tagBase64 == null) {
        updated.remove(SealedSenderTagExt.KEY_TAG)
    } else {
        updated[SealedSenderTagExt.KEY_TAG] = JsonPrimitive(tagBase64)
    }
    return copy(ext = if (updated.isEmpty()) null else updated)
}
