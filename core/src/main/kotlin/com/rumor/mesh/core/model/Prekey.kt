package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * O38 — Wire-format payload for a `MessageType.PREKEY_PUBLISH` broadcast.
 *
 * **Purpose:** receiver-side forward secrecy. Today's DM crypto uses
 * an ephemeral sender X25519 + recipient long-term static X25519, giving
 * sender-side FS only. A relay holding stored ciphertext can decrypt
 * every past DM the moment the recipient's long-term static key leaks.
 *
 * Fix: recipient periodically generates a short-lived X25519 prekey
 * `R_t`, signed by their long-term Ed25519 identity, and gossips it as
 * an INFRASTRUCTURE broadcast. Senders cache the freshest valid R_t
 * for each known contact and DH against it instead of the long-term
 * static. Recipient deletes `R_t_priv` after `validToMs` expiry; stored
 * ciphertext encrypted to an expired R_t becomes unrecoverable even
 * with the long-term key.
 *
 * **Validity window:** sender refuses to use a prekey outside
 * `[validFromMs, validToMs)`. Recipient should generate fresh prekeys
 * at a cadence shorter than the validity window (e.g. rotate every
 * hour with a 24-hour validity window so a few minutes of clock skew
 * between sender and recipient is fine).
 *
 * **Offline-for-weeks fallback:** documented honestly. A sender who
 * holds no valid prekey for a recipient (because the recipient hasn't
 * broadcast one within the cached window) falls back to the long-term
 * static path; FS regression for that case is acknowledged.
 *
 * **Wiring NOT done in this commit** — see CLAUDE.md O38 row. This
 * file is the wire shape + signing bytes so the rotation scheduler,
 * cache, and composeDirect-side prekey selection can be added in
 * follow-up commits with a stable contract.
 */
@Serializable
@SerialName("prekey_publish")
data class PrekeyPublish(
    /** Publisher's userId — receiver's long-term identity. */
    val publisherId: String,
    /**
     * Publisher's long-term Ed25519 public key (Base64). The same key
     * appears in the wrapping RumorMessage's `senderPublicKey`;
     * duplicated here so the payload is self-contained for sig verify.
     */
    val publisherPublicKey: String,
    /** X25519 public key of the new short-lived prekey (Base64, 32 bytes). */
    val prekeyPublic: String,
    /** Wall-clock epoch ms when this prekey becomes valid. Usually = composeMs. */
    val validFromMs: Long,
    /** Wall-clock epoch ms when this prekey expires. Senders refuse use after. */
    val validToMs: Long,
    /**
     * Ed25519 signature by [publisherPublicKey] over the canonical bytes
     * (see [prekeyPublishSignableBytes]). Base64.
     */
    val signature: String,
    /** Reserved forward-compat carrier. Not covered by [signature]. */
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/**
 * Domain-tagged signable bytes for a [PrekeyPublish]. Binds the
 * prekey to its publisher AND its validity window so a relay cannot
 * extend the window or substitute the prekey without breaking the sig.
 */
fun prekeyPublishSignableBytes(
    publisherId: String,
    publisherPublicKey: String,
    prekeyPublic: String,
    validFromMs: Long,
    validToMs: Long,
): ByteArray = buildString {
    append("rumor-prekey-v1:")
    append(publisherId)
    append('|')
    append(publisherPublicKey)
    append('|')
    append(prekeyPublic)
    append('|')
    append(validFromMs)
    append("->")
    append(validToMs)
}.encodeToByteArray()
