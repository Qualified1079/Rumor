package com.rumor.mesh.core.wire

import com.rumor.mesh.core.model.RumorMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * O90 — `_ext` field accessors for thread + mention UI metadata.
 *
 * **Field layout in `_ext`:**
 *
 * | Key | JSON type | Meaning |
 * |---|---|---|
 * | `replyTo` | string | Parent messageId this is a reply to. Absent = top-level message. |
 * | `mentions` | array<string> | userIds explicitly mentioned by the sender. Absent / empty = no mentions. |
 *
 * **Both fields are unsigned** (placed in `_ext`, the forward-compat
 * carrier that is NOT covered by the message's Ed25519 signature). A
 * malicious sender can claim arbitrary parents or mentions; the impact
 * is local-display-only:
 *  - Wrong `replyTo`: UI shows the reply in the wrong thread; user
 *    notices the mismatch in the thread's flow and can ignore. Same
 *    impact as a sender pretending to quote a message they didn't see.
 *  - Wrong `mentions`: UI may notify a user who wasn't actually
 *    mentioned in the text. Same impact as a sender plaintext-typing
 *    `@username` when speaking ABOUT someone, not TO them. Real
 *    mention notifications cross-check against the actual content
 *    text in the UI layer.
 *
 * **No relay or routing implications.** These fields are inspected
 * only at display time by UI code (plugins, ThreadScreen, mention-
 * aggregator views). The relay path is unchanged.
 *
 * Field names reserved forever per `docs/RENAMED_FIELDS_NEVER_REUSE.md`.
 *
 * Mirrors the `withCompressionMetadata` / `withTtlSplit` pattern: pure
 * accessors + a single copy helper per concern; tests pin the field
 * format so a future refactor can't silently drift.
 */
object ThreadAndMentionExt {
    const val KEY_REPLY_TO: String = "replyTo"
    const val KEY_MENTIONS: String = "mentions"
}

/** Parent messageId this is a reply to, or null for top-level messages. */
val RumorMessage.replyTo: String?
    get() = (ext?.get(ThreadAndMentionExt.KEY_REPLY_TO) as? JsonPrimitive)?.content

/**
 * UserIds the sender claims to have mentioned in the message content.
 * Empty list (not null) when the field is absent — simpler for callers.
 *
 * Returns null only on a malformed `_ext.mentions` value (wrong JSON
 * type), so callers can distinguish "no mentions claimed" from "the
 * field is broken." Most callers should just treat null as empty.
 */
val RumorMessage.mentions: List<String>?
    get() {
        val raw = ext?.get(ThreadAndMentionExt.KEY_MENTIONS) ?: return emptyList()
        val arr = raw as? JsonArray ?: return null
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }

/** Set `_ext.replyTo`, returning a new message. Pass null to clear. */
fun RumorMessage.withReplyTo(parentMessageId: String?): RumorMessage {
    val updated = (ext ?: emptyMap()).toMutableMap()
    if (parentMessageId == null) {
        updated.remove(ThreadAndMentionExt.KEY_REPLY_TO)
    } else {
        updated[ThreadAndMentionExt.KEY_REPLY_TO] = JsonPrimitive(parentMessageId)
    }
    return copy(ext = if (updated.isEmpty()) null else updated)
}

/**
 * Set `_ext.mentions`, returning a new message. Pass an empty list to
 * clear the field entirely (rather than emit an empty array, which
 * would waste a few bytes per message for no benefit).
 */
fun RumorMessage.withMentions(userIds: List<String>): RumorMessage {
    val updated = (ext ?: emptyMap()).toMutableMap()
    if (userIds.isEmpty()) {
        updated.remove(ThreadAndMentionExt.KEY_MENTIONS)
    } else {
        updated[ThreadAndMentionExt.KEY_MENTIONS] = JsonArray(userIds.map { JsonPrimitive(it) })
    }
    return copy(ext = if (updated.isEmpty()) null else updated)
}
