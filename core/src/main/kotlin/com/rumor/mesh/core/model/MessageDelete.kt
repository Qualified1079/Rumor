package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * O40 — Wire-format payload for a `MessageType.MESSAGE_DELETE` request.
 *
 * **Purpose:** tighten the forward-secrecy window for stored DM
 * ciphertext. Today a relay holds a DIRECT message in its
 * `MessageRepository` until natural expiry; if the recipient's static
 * X25519 key leaks weeks later, every stored ciphertext is recoverable.
 * O38 (receiver-side FS via rotating prekeys) is the longer-term fix;
 * O40 bounds the storage window: once the recipient has ingested and
 * read the message, they (or the sender) can request all relays purge
 * it, removing future-compromise material.
 *
 * **Trust model:** relays honor the request only if the issuer is the
 * original DM's `senderId` OR `recipientId`. Any third party signing a
 * delete for someone else's message is dropped — no quorum, no
 * authority. The check costs one lookup against MessageRepository for
 * the targeted messageId; relays that no longer hold the message
 * silently no-op.
 *
 * **Replay protection:** the deleted messageId is kept in the dedup
 * set so the same ciphertext can't be re-ingested via gossip
 * exchange. Without that, a peer holding the pre-delete copy could
 * inject it back into the mesh after the delete, defeating the
 * purpose.
 *
 * **Out of scope:** does NOT recover FS against an attacker who
 * already exfiltrated the ciphertext (that's structurally impossible
 * without time travel). Bounds the storage window for FUTURE
 * compromises only.
 */
@Serializable
@SerialName("message_delete")
data class MessageDeletePayload(
    /** id of the [RumorMessage] to delete from local stores. */
    val messageId: String,
    /**
     * Base64-encoded Ed25519 public key of the issuer (sender or
     * recipient of the original DM). The same key is duplicated in
     * the wrapping RumorMessage's `senderPublicKey` — this field
     * exists so the payload is self-contained for signature
     * verification.
     */
    val issuerPublicKey: String,
    /**
     * Ed25519 signature over `messageDeleteSignableBytes(messageId,
     * issuerPublicKey)`. Verified by every relay BEFORE the purge.
     * Base64.
     */
    val signature: String,
    /** Reserved forward-compat carrier. Not covered by [signature]. */
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/**
 * Domain-tagged signable bytes for a [MessageDeletePayload]. The
 * delete is bound to the specific messageId AND the issuer's pubkey
 * so a sig over delete(X) by key K1 can't be lifted onto delete(X)
 * by key K2 (UKS resistance).
 */
fun messageDeleteSignableBytes(messageId: String, issuerPublicKey: String): ByteArray =
    "rumor-message-delete-v1:$messageId|$issuerPublicKey".encodeToByteArray()
