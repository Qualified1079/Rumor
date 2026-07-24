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

        // O100: slice once, so per-chunk hashes and chunk payloads are derived
        // from the exact same byte ranges (no chance of a hash/payload skew).
        val slices = (0 until totalChunks).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, data.size)
            data.copyOfRange(start, end)
        }
        val chunkHashes = slices.map { chunkHash(it) }

        val metadata = TransferMetadata(
            transferId = transferId,
            contentType = contentType,
            mimeType = mimeType,
            title = title,
            totalBytes = data.size.toLong(),
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            contentHash = contentHash,
            chunkHashes = chunkHashes,
            recipientId = recipientId,
        )

        val chunks = slices.mapIndexed { index, slice ->
            Chunk(
                transferId = transferId,
                chunkIndex = index,
                totalChunks = totalChunks,
                data = Base64Codec.encode(slice),
                contentHash = contentHash,
            )
        }

        return metadata to chunks
    }

    /** O100: canonical per-chunk digest — SHA-256 of the raw (pre-Base64) slice, Base64-encoded. */
    fun chunkHash(bytes: ByteArray): String = Sha256.digest(bytes).toBase64()

    /**
     * O100: verify a single [chunk] against [metadata] before trusting it —
     * the primitive a multi-source assembler needs to drop a poisoned chunk and
     * attribute the seeder without waiting for whole-file reassembly to fail.
     *
     * Passes only when the index is in range, the chunk's own content-group hash
     * (if present) matches, and — when [TransferMetadata.chunkHashes] is present —
     * the decoded bytes hash to the expected per-chunk digest. When the sender
     * predates content-addressing (no chunkHashes), there is nothing to check per
     * chunk, so this returns true and integrity falls back to the whole-file hash
     * in [reassemble].
     */
    fun verifyChunk(chunk: Chunk, metadata: TransferMetadata): Boolean {
        if (chunk.chunkIndex !in 0 until metadata.totalChunks) return false
        if (chunk.contentHash != null && chunk.contentHash != metadata.contentHash) return false
        val expected = metadata.chunkHashes?.getOrNull(chunk.chunkIndex) ?: return true
        val bytes = runCatching { Base64Codec.decode(chunk.data) }.getOrNull() ?: return false
        return chunkHash(bytes) == expected
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

        // O100: when per-chunk hashes are present, reject a bad chunk here rather
        // than only discovering corruption via the whole-file hash at the end.
        if (metadata.chunkHashes != null && sorted.any { !verifyChunk(it, metadata) }) return null

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

    /**
     * O109: the first [window] missing indices only — never the whole set.
     * Memory and NACK-frame size become O(window) regardless of what
     * totalChunks claims, and the loop is provably bounded: every iteration
     * either collects a missing index (≤ window) or hits a received one
     * (≤ received.size), so a lying totalChunks can't buy CPU either.
     * The next window is requested naturally once this one is satisfied
     * (the watchdog recomputes from the updated received set).
     */
    fun missingIndicesWindowed(received: Set<Int>, totalChunks: Int, window: Int = NACK_WINDOW): List<Int> {
        val out = ArrayList<Int>(minOf(window, 64))
        var i = 0
        while (i < totalChunks && out.size < window) {
            if (i !in received) out.add(i)
            i++
        }
        return out
    }

    /** O109: sliding-window size for chunk NACKs. 1024 × 60 KB ≈ 60 MB in flight max. */
    const val NACK_WINDOW = 1024

    // O108: sanity ceilings on peer-asserted TransferMetadata.
    const val MAX_TOTAL_CHUNKS = 100_000
    const val MAX_CHUNK_SIZE = 1_000_000
    const val MAX_TOTAL_BYTES = 1_000_000_000L

    /**
     * O108: is this peer-asserted metadata internally consistent and within
     * absolute caps? The consistency check (totalChunks must equal
     * ceil(totalBytes/chunkSize)) is the strong one — it stops a tiny metadata
     * from committing an honest receiver to Int.MAX_VALUE chunks of work; the
     * caps bound what a consistent-but-huge claim can ask for. Pure so it's
     * unit-testable without standing up the whole receive path.
     */
    fun isPlausibleMetadata(totalChunks: Int, chunkSize: Int, totalBytes: Long): Boolean {
        if (totalChunks !in 1..MAX_TOTAL_CHUNKS) return false
        if (chunkSize !in 1..MAX_CHUNK_SIZE) return false
        if (totalBytes !in 1..MAX_TOTAL_BYTES) return false
        val impliedChunks = (totalBytes + chunkSize - 1) / chunkSize
        return totalChunks.toLong() == impliedChunks
    }
}
