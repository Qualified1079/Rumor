package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * O89 — Posting certificate. A moderator's signed authorization for
 * [userId] to post into [roomId] (optionally restricted to [channel])
 * for a bounded validity window.
 *
 * Carried inline with room messages — sender attaches the cert; receivers
 * verify the cert's [signature] against the moderator's pubkey AND
 * verify the wrapping RumorMessage was signed by [userId] (the cert
 * names who can post; the message proves they did). Cert mismatch =
 * dropped. Cert expired ([nowMs] >= [validToMs]) = dropped.
 *
 * **Why a separate cert instead of just "mods sign each message":**
 * mods aren't online when every member posts. The cert grants standing
 * authorization for a window — typical default 24h with auto-renew for
 * active members — so members post at will without per-message mod
 * involvement. Mods revoke before the window expires via
 * [RoomAction.kind] = `REVOKE_CERT` for immediate-effect kicks; passive
 * "soft kick" happens naturally when a mod simply stops renewing.
 *
 * **Structural enforcement (per docs/ROOMS_THREAT_MODEL.md):** receivers
 * drop messages without a valid cert. A modified client that ignores
 * the cert publishes happily, but every honest peer drops the publish
 * before relay, so the modified client's traffic doesn't propagate.
 * Read access is structurally enforced separately by the O79
 * multi-recipient envelope (omitted recipient = no key wrap = no read).
 *
 * Reserved domain tag: `rumor-room-posting-cert-v1:` — never reuse.
 */
@Serializable
@SerialName("room_posting_cert")
data class RoomPostingCert(
    /** Room this cert grants posting rights for. */
    val roomId: String,
    /**
     * Optional channel restriction. Null = cert is valid for any channel
     * (or for rooms without channels). Non-null = cert only authorizes
     * posts whose `payload.channel` equals this value.
     */
    val channel: String? = null,
    /** The userId being granted posting rights. */
    val userId: String,
    /** Wall-clock epoch ms when this cert becomes valid. */
    val validFromMs: Long,
    /** Wall-clock epoch ms when this cert expires. Receivers drop after. */
    val validToMs: Long,
    /** Moderator userId issuing the cert. */
    val moderatorId: String,
    /** Moderator's Ed25519 public key (Base64) — same key as in HELLO/identity. */
    val moderatorPublicKey: String,
    /** Ed25519 signature by [moderatorPublicKey] over [roomPostingCertSignableBytes]. */
    val signature: String,
    /** Reserved forward-compat carrier. Not covered by [signature]. */
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/**
 * Domain-tagged signable bytes for a [RoomPostingCert]. Binds room +
 * channel + grantee + window + moderator identity. A relay extending
 * the window, changing the grantee, swapping the channel, or
 * substituting another moderator's identity all break the sig.
 */
fun roomPostingCertSignableBytes(
    roomId: String,
    channel: String?,
    userId: String,
    validFromMs: Long,
    validToMs: Long,
    moderatorId: String,
    moderatorPublicKey: String,
): ByteArray = buildString {
    append("rumor-room-posting-cert-v1:")
    append(roomId); append('|')
    append(channel ?: ""); append('|')
    append(userId); append('|')
    append(validFromMs); append("->"); append(validToMs); append('|')
    append(moderatorId); append('|')
    append(moderatorPublicKey)
}.encodeToByteArray()
