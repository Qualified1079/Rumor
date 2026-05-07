package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire-format message — exactly what travels between nodes. */
@Serializable
data class RumorMessage(
    /** 128-bit random ID, hex-encoded. Used for duplicate suppression. */
    val id: String,
    /** Sender's User ID (hex fingerprint of their Ed25519 public key). */
    val senderId: String,
    /** Sender's raw Ed25519 public key (Base64). Included so recipients can verify without a prior key exchange. */
    val senderPublicKey: String,
    /** Per-sender monotonic counter. Used for ordering messages from the same sender. */
    val sequenceNumber: Long,
    /** Milliseconds since message was first created. Each relay adds its hold time. */
    val elapsedMs: Long,
    val type: MessageType,
    /** Remaining hops. Decremented at each relay; message dies at zero. Applies to BROADCAST and DIRECT. */
    val ttl: Int,
    /** Plaintext for BROADCAST; absent for DIRECT (use [encryptedPayload]). */
    val payload: MessagePayload? = null,
    /** AES-256-GCM ciphertext for DIRECT messages. */
    val encryptedPayload: String? = null,
    /** Recipient User ID — present only for DIRECT messages. */
    val recipientId: String? = null,
    /** Ed25519 signature over all other fields (Base64). */
    val signature: String,
    /** Unix epoch millis when this node first saw the message. Not propagated. */
    @kotlinx.serialization.Transient
    val receivedAtMs: Long = System.currentTimeMillis(),
    /** Whether the local user has read this message. Not propagated. */
    @kotlinx.serialization.Transient
    val isRead: Boolean = false,
    /** Whether the local user manually relayed this (reset TTL). Not propagated. */
    @kotlinx.serialization.Transient
    val wasRelayed: Boolean = false,
)

@Serializable
enum class MessageType {
    @SerialName("broadcast") BROADCAST,
    @SerialName("direct")    DIRECT,
    @SerialName("ping")      PING,
    @SerialName("pong")      PONG,
}

@Serializable
data class MessagePayload(
    val contentType: ContentType,
    /** Text content (for TEXT type) or Base64-encoded binary (IMAGE, VOICE, FILE). */
    val content: String,
    /** Original filename — present for FILE type. */
    val filename: String? = null,
    /** MIME type hint. */
    val mimeType: String? = null,
    /** Byte size of the original content before Base64. */
    val sizeBytes: Long = 0,
)

@Serializable
enum class ContentType {
    @SerialName("text")  TEXT,
    @SerialName("image") IMAGE,
    @SerialName("voice") VOICE,
    @SerialName("file")  FILE,
}
