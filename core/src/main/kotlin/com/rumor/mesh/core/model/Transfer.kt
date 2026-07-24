package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Payload for a TRANSFER_METADATA message. Sent first to announce a chunked transfer.
 * Receivers store this and use it to track progress / detect missing chunks.
 */
@Serializable
data class TransferMetadata(
    /** Stable ID linking this metadata to its CHUNK messages. */
    val transferId: String,
    val contentType: ContentType,
    val mimeType: String? = null,
    /** Filename or human-readable title. */
    val title: String? = null,
    val totalBytes: Long,
    val totalChunks: Int,
    /** Payload bytes per chunk (last chunk may be smaller). */
    val chunkSize: Int,
    /** SHA-256 of the original data, Base64-encoded. Verified after reassembly. */
    val contentHash: String,
    /**
     * O100: per-chunk SHA-256 (Base64), indexed by chunkIndex — the "hard design
     * constraint" for multi-source swarming. A whole-file hash only tells you the
     * assembled result is wrong; it cannot say *which* chunk (or which seeder)
     * poisoned it. With this list a bad chunk is detectable on arrival and its
     * source is attributable/evictable. Rides inside the Ed25519-signed message
     * payload, so a relay cannot swap a chunk's expected hash. Nullable for
     * backward compatibility with metadata that predates content-addressing.
     */
    val chunkHashes: List<String>? = null,
    /** Non-null for a direct (targeted) transfer; null for a broadcast transfer. */
    val recipientId: String? = null,
    /** Reserved forward-compat carrier. See [RumorMessage.ext]. */
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/**
 * Payload for a CHUNK message. Each chunk carries a fragment of the original payload.
 */
@Serializable
data class Chunk(
    val transferId: String,
    /** Zero-based index. */
    val chunkIndex: Int,
    /** Duplicated from metadata so receivers can detect obvious mismatch without a DB lookup. */
    val totalChunks: Int,
    /** Base64-encoded raw bytes for this slice of the original payload. */
    val data: String,
    /**
     * O100: the content-group hash (= [TransferMetadata.contentHash]) this chunk
     * belongs to. Because chunking is deterministic on content, a chunk carrying
     * group H at index i is interchangeable with the same (H, i) chunk from any
     * other seeder — this field is what lets a future multi-source assembler
     * recombine chunks from many senders. Nullable for backward compatibility.
     */
    val contentHash: String? = null,
    /** Reserved forward-compat carrier. See [RumorMessage.ext]. */
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/**
 * Payload for a CHUNK_REQUEST message. Sent by the receiver when reassembly stalls.
 * Routed as a DIRECT message to the original sender.
 */
@Serializable
data class ChunkRequest(
    val transferId: String,
    /** Indices the receiver still needs. Sender retransmits only these. */
    val missingIndices: List<Int>,
    /** Reserved forward-compat carrier. See [RumorMessage.ext]. */
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/**
 * Body of a [com.rumor.mesh.core.model.MessageType.TRANSFER_CANCEL] message (O18).
 * Sent receiver→sender to abort an in-flight chunked transfer. Sender stops
 * responding to chunk-requests for [transferId] on receipt.
 */
@Serializable
data class TransferCancel(
    val transferId: String,
    /** Reserved forward-compat carrier. See [RumorMessage.ext]. */
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/** Lifecycle state of a chunked transfer tracked in local storage. */
@Serializable
enum class TransferStatus {
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("complete")    COMPLETE,
    @SerialName("failed")      FAILED,
    /** Abandoned after max NACK retries. */
    @SerialName("abandoned")   ABANDONED,
}

/** Whether this node originated the transfer or is receiving it. */
@Serializable
enum class TransferDirection {
    @SerialName("sending")   SENDING,
    @SerialName("receiving") RECEIVING,
}
