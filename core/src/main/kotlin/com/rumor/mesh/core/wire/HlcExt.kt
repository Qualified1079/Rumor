package com.rumor.mesh.core.wire

import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.time.HlcTimestamp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * O95 — `_ext` carrier for the Hybrid Logical Clock stamp.
 *
 * **Field layout in `_ext`:**
 *
 * | Key | JSON type | Meaning |
 * |---|---|---|
 * | `hlc` | string | `"<wallMs>:<counter>"`, both base-10. Stamped at compose from the sender's [com.rumor.mesh.core.time.HlcClock.tick]. |
 *
 * Reserved forever per `RENAMED_FIELDS_NEVER_REUSE.md`. Unsigned (in `_ext`):
 * a relay can tamper it, with display-order-only impact (same class as
 * `_ext.replyTo`); receivers additionally clamp via the HlcClock drift bound
 * before folding it into local state. Display sort is `(hlc, id)` with
 * `displayTimeMs` as the fallback key for pre-HLC messages.
 */
val RumorMessage.hlcTimestamp: HlcTimestamp?
    get() {
        val raw = runCatching { ext?.get("hlc")?.jsonPrimitive?.content }.getOrNull() ?: return null
        val sep = raw.indexOf(':')
        if (sep <= 0) return null
        val wall = raw.substring(0, sep).toLongOrNull() ?: return null
        val counter = raw.substring(sep + 1).toLongOrNull() ?: return null
        return HlcTimestamp(wall, counter)
    }

fun RumorMessage.withHlc(ts: HlcTimestamp): RumorMessage {
    val newExt = (ext ?: emptyMap()) + ("hlc" to JsonPrimitive("${ts.wallMs}:${ts.counter}"))
    return copy(ext = newExt)
}
