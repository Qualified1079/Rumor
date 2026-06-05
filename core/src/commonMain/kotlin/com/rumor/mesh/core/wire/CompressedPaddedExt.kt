package com.rumor.mesh.core.wire

import com.rumor.mesh.core.model.RumorMessage
import kotlinx.serialization.json.JsonPrimitive

/**
 * O76 — `_ext` field accessors for the compress + pad pipeline metadata.
 *
 * Scaffolding for the GossipEngine integration. The fields are NOT yet
 * read by the compose path or the receive path — they live here so the
 * future wire integration has a single canonical place to look up field
 * names, and so the inevitable "where exactly does compressed live" git
 * archaeology has a stable answer.
 *
 * **Field layout in `_ext`:**
 *
 * | Key | JSON type | Meaning |
 * |---|---|---|
 * | `c` | bool | true iff the payload went through [CompressedPaddedCodec.encodeForWire]. Absence = uncompressed. |
 * | `cb` | int | Bucket index (0..5) the padded blob landed in. Only meaningful when `c` is true. |
 * | `cl` | int | originalLength — pre-pad post-compress byte count. Only meaningful when `c` is true. |
 *
 * Short keys are deliberate: every TEXT message carries them, and the
 * `_ext` map is JSON-encoded on the wire — 4 bytes per key adds up at
 * scale. Field names are reserved forever per `RENAMED_FIELDS_NEVER_REUSE.md`.
 *
 * **Security gate before flipping the compose-path switch:**
 *
 * `cl` (originalLength) MUST ride in AEAD-protected associated data
 * before this metadata can be trusted across the network. Today
 * `CryptoManager.aesGcmEncrypt` does not accept associated data; adding
 * it requires:
 *   - `PlatformCrypto.aesGcmEncrypt` / `aesGcmDecrypt` expect/actual
 *     gains an `aad: ByteArray` param.
 *   - JVM actual passes via `Cipher.updateAAD(aad)`.
 *   - iOS Swift bridge `AES.GCM.seal(_, authenticating: aad)`.
 *   - `CryptoGoldenVectorsTest` pins a new AAD-bearing vector.
 *
 * Until that lands, a relay can flip `cl` on a passing message — at
 * worst causing a receive-side decode failure (the inflate budget
 * mismatch or a truncated stream), at best wasting CPU. Not an
 * integrity attack on the message contents (AEAD on the body still
 * has to verify), but a DoS opportunity worth closing first.
 *
 * **Why placed in `core/wire/` and not `core/model/Message.kt`:**
 *
 * The split-TTL helpers (`floodedHops` / `routedHops`) live in
 * Message.kt because they belong to the *protocol* layer (routing TTL).
 * Compression metadata belongs to the *wire-format* / codec layer —
 * the model doesn't know about compression, only that some payloads
 * carry these annotations. Keeping the helpers next to the codec
 * surface means a reader of `CompressedPaddedCodec` sees the full
 * picture in one place.
 */
object CompressedPaddedExt {
    const val KEY_COMPRESSED: String = "c"
    const val KEY_BUCKET_INDEX: String = "cb"
    const val KEY_ORIGINAL_LENGTH: String = "cl"
}

/** True iff this message's payload was deflate-compressed before AEAD wrap. */
val RumorMessage.isCompressed: Boolean
    get() = (ext?.get(CompressedPaddedExt.KEY_COMPRESSED) as? JsonPrimitive)
        ?.content?.toBooleanStrictOrNull() ?: false

/** Bucket index the compressed-and-padded blob landed in; -1 if not compressed. */
val RumorMessage.compressionBucketIndex: Int
    get() = (ext?.get(CompressedPaddedExt.KEY_BUCKET_INDEX) as? JsonPrimitive)
        ?.content?.toIntOrNull() ?: -1

/**
 * Pre-pad post-compress byte count; -1 if not compressed.
 *
 * **Read this only after AEAD-decrypt has verified the message body.**
 * Until the AAD-bearing API ships (see [CompressedPaddedExt] docstring),
 * this value is unsigned — a relay can tamper with it. Once AAD is
 * wired, this becomes integrity-protected.
 */
val RumorMessage.compressionOriginalLength: Int
    get() = (ext?.get(CompressedPaddedExt.KEY_ORIGINAL_LENGTH) as? JsonPrimitive)
        ?.content?.toIntOrNull() ?: -1

/**
 * Set the three compression-metadata fields in `_ext`, returning a new
 * [RumorMessage]. Caller is responsible for already having run the
 * compress+pad codec and passing the bucketIndex / originalLength that
 * came out of it.
 */
fun RumorMessage.withCompressionMetadata(bucketIndex: Int, originalLength: Int): RumorMessage {
    val updated = (ext ?: emptyMap()).toMutableMap().apply {
        this[CompressedPaddedExt.KEY_COMPRESSED] = JsonPrimitive(true)
        this[CompressedPaddedExt.KEY_BUCKET_INDEX] = JsonPrimitive(bucketIndex)
        this[CompressedPaddedExt.KEY_ORIGINAL_LENGTH] = JsonPrimitive(originalLength)
    }
    return copy(ext = updated)
}
