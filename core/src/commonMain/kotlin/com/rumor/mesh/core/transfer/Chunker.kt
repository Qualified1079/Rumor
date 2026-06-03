package com.rumor.mesh.core.transfer

import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.Chunk
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.TransferMetadata
import com.rumor.mesh.core.platform.Sha256
import com.rumor.mesh.core.platform.Base64Codec
import com.rumor.mesh.core.platform.Uuid

/** 60 KB leaves comfortable room for gossip framing under typical Wi-Fi Direct MTUs. */
const val DEFAULT_CHUNK_SIZE = 60_000

object Chunker {

    /**
     * Split [data] into a [TransferMetadata] announcement and a list of [Chunk] payloads.
     *
     * The returned metadata must be sent as a TRANSFER_METADATA message before any CHUNK
     * messages. Receivers buffer chunks keyed by [TransferMetadata.transferId] until the
     * set is complete, then call [reassemble].
     */
    fun chunk(
        data: ByteArray,
        contentType: ContentType,
        mimeType: String? = null,
        title: String? = null,
        recipientId: String? = null,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ): Pair<TransferMetadata, List<Chunk>> {
        require(data.isNotEmpty()) { "Cannot chunk empty payload" }
        require(chunkSize > 0) { "chunkSize must be positive" }

        val transferId = Uuid.randomHex32()
        val totalChunks = (data.size + chunkSize - 1) / chunkSize
        val contentHash = Sha256.digest(data).toBase64()

        val metadata = TransferMetadata(
            transferId = transferId,
            contentType = contentType,
            mimeType = mimeType,
            title = title,
            totalBytes = data.size.toLong(),
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            contentHash = contentHash,
            recipientId = recipientId,
        )

        val chunks = (0 until totalChunks).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, data.size)
            Chunk(
                transferId = transferId,
                chunkIndex = index,
                totalChunks = totalChunks,
                data = Base64Codec.encode(data.copyOfRange(start, end)),
            )
        }

        return metadata to chunks
    }

    /**
     * Reassemble [chunks] back into the original byte array.
     *
     * Returns null if any chunk is missing, if the final size doesn't match
     * [metadata], or if the SHA-256 of the reassembled data doesn't match
     * [TransferMetadata.contentHash].
     */
    fun reassemble(chunks: List<Chunk>, metadata: TransferMetadata): ByteArray? {
        if (chunks.size != metadata.totalChunks) return null
        val sorted = chunks.sortedBy { it.chunkIndex }
        if (sorted.map { it.chunkIndex } != (0 until metadata.totalChunks).toList()) return null

        val result = ByteArray(metadata.totalBytes.toInt())
        var offset = 0
        for (chunk in sorted) {
            val bytes = Base64Codec.decode(chunk.data)
            bytes.copyInto(result, offset)
            offset += bytes.size
        }

        val actualHash = Sha256.digest(result).toBase64()
        if (actualHash != metadata.contentHash) return null

        return result
    }

    /** Returns the indices from [0, totalChunks) that are absent from [received]. */
    fun missingIndices(received: Set<Int>, totalChunks: Int): List<Int> =
        (0 until totalChunks).filter { it !in received }
}
