package com.rumor.mesh.core.wire

import com.rumor.mesh.core.model.RoomPostingCert
import com.rumor.mesh.core.model.RumorMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive

/**
 * O89 — `_ext.pc` field accessor for a room posting certificate.
 *
 * The cert is itself moderator-signed, so its *contents* are already
 * tamper-evident on their own. What the signed transcript adds is
 * protection against **removal**: `_ext` is generally unsigned, so without
 * binding, any relay could strip `_ext.pc` from a legitimate post and every
 * honest downstream node would then reject it for want of a cert — turning a
 * single malicious relay into amplified censorship, the same shape as the §2
 * id-burn and the O212 retype. Binding it (see
 * [com.rumor.mesh.core.protocol.MessageStore.signableBytes], `rumor-msg-v3:`)
 * means a stripped cert breaks the signature and the message is dropped as
 * forged rather than silently treated as unauthorized.
 *
 * Carried as the WireJson encoding of [RoomPostingCert] in a string, mirroring
 * how `encryptedPayload` carries an envelope. Mirrors the
 * [RoomRoutingTagExt] accessor + single-copy-helper pattern.
 */
object RoomPostingCertExt {
    /** `_ext` key for the room posting certificate. Reserved forever. */
    const val KEY_POSTING_CERT: String = "pc"
}

/** Raw `_ext.pc` string (WireJson-encoded [RoomPostingCert]), or null. */
val RumorMessage.roomPostingCertJson: String?
    get() = (ext?.get(RoomPostingCertExt.KEY_POSTING_CERT) as? JsonPrimitive)?.content

/** Decoded posting cert, or null when absent or malformed. */
val RumorMessage.roomPostingCert: RoomPostingCert?
    get() = roomPostingCertJson?.let {
        runCatching { WireJson.decodeFromString<RoomPostingCert>(it) }.getOrNull()
    }

/** Set `_ext.pc`, returning a new message. Pass null to clear. */
fun RumorMessage.withRoomPostingCert(cert: RoomPostingCert?): RumorMessage {
    val updated = (ext ?: emptyMap()).toMutableMap()
    if (cert == null) {
        updated.remove(RoomPostingCertExt.KEY_POSTING_CERT)
    } else {
        updated[RoomPostingCertExt.KEY_POSTING_CERT] =
            JsonPrimitive(WireJson.encodeToString(cert))
    }
    return copy(ext = if (updated.isEmpty()) null else updated)
}
