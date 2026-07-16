package com.rumor.mesh.core.wire

import com.rumor.mesh.core.platform.Compression

/**
 * O76 — Pure composition of the *compress* and *pad* steps in the
 * `compress → pad → encrypt → sign → chunk` pipeline.
 *
 * This file holds the two ends of the pipeline that operate on
 * plaintext bytes:
 *
 *  - [encodeForWire]: `plaintext → deflate → pad to nearest bucket →
 *    [Encoded]`. The encrypt step (AES-GCM) and the outer Ed25519 sig
 *    are then applied by `GossipEngine.composeBroadcast` /
 *    `composeDirect`.
 *
 *  - [decodeFromWire]: `padded → unpad → inflate → plaintext`. Called
 *    after the receiver has AEAD-decrypted the inner payload.
 *
 * No state, no IO. Compression backend is the [Compression] expect/actual.
 *
 * **What rides in the wire `_ext`:**
 *
 * Two fields are added to the message wrapper's `_ext` map per the O76
 * spec — both are filled in by the GossipEngine integration layer, not
 * by this file:
 *
 *  - `compressed: Boolean` — present and true iff the payload went
 *    through [encodeForWire].
 *  - `bucketIndex: Byte` — the bucket the padded blob fits in, so the
 *    receiver knows the legitimate decompression upper bound.
 *  - `originalLength: Int` — the pre-pad post-compress byte count, so
 *    [Unpad.unpad] knows where the deflate stream ends.
 *
 * The `originalLength` field rides in AEAD-protected associated data
 * (not `_ext`) so it can't be tampered with by a peer relaying the
 * ciphertext — the AEAD tag covers it, and flipping it breaks the
 * decryption. `_ext` is for unsigned metadata the integration layer
 * actually needs before AEAD decryption (e.g. the routing-layer
 * bucketIndex hint), per CLAUDE.md G8 (O37 wire-format invariants).
 *
 * **What does NOT go through this codec:**
 *
 *  - BINARY / FILE / IMAGE / VOICE payloads. Per CRIME/BREACH safety
 *    (see PaddingBuckets docstring) and the O76 spec: already-compressed
 *    media re-deflated wastes CPU and may leak length information about
 *    embedded structure if the compressor's window aligns badly.
 *  - Messages already chunked by the file-transfer path. The chunker
 *    operates on encrypted bytes; compression happens before encryption
 *    on each pre-chunked TEXT message.
 *
 * The GossipEngine integration is responsible for gating on ContentType
 * and on the HELLO `supportedFeatures: ["compression-v1"]` capability —
 * a peer that doesn't advertise the capability gets uncompressed
 * payloads, full back-compat.
 */
object CompressedPaddedCodec {

    /** What [encodeForWire] returns. The encrypt step takes [bytes]; [bucketIndex] and [originalLength] ride in the metadata. */
    data class Encoded(val bytes: ByteArray, val bucketIndex: Int, val originalLength: Int) {
        override fun equals(other: Any?) = other is Encoded
            && other.bucketIndex == bucketIndex
            && other.originalLength == originalLength
            && other.bytes.contentEquals(bytes)
        override fun hashCode(): Int = bytes.contentHashCode() * 31 + bucketIndex * 17 + originalLength
    }

    /**
     * Compress then pad. Returns null if the *compressed* size still
     * exceeds [PaddingBuckets.MAX_SINGLE_MESSAGE] — caller should chunk.
     *
     * (For typical text inputs, deflate shrinks; the bucket-overflow
     * path is rare. For inputs that don't compress well, the encoded
     * size can exceed the original by a few bytes — still fits in the
     * same or one-higher bucket in practice.)
     */
    fun encodeForWire(plaintext: ByteArray): Encoded? {
        val compressed = Compression.deflate(plaintext)
        val padded = Pad.pad(compressed) ?: return null
        return Encoded(padded.bytes, padded.bucketIndex, compressed.size)
    }

    /**
     * Unpad then decompress. Returns null on tampered padding,
     * malformed deflate stream, or output exceeding [maxOutputBytes].
     *
     * @param bytes The post-AEAD-decrypt bytes (bucket-sized).
     * @param originalLength The compressed-stream length, as
     *   recovered from AEAD-protected associated data.
     * @param maxOutputBytes Hard cap on decompressed size. The
     *   sender's pre-compress payload size is known to the sender
     *   only; the receiver should pass a per-message-class ceiling
     *   (e.g. `PaddingBuckets.MAX_SINGLE_MESSAGE`).
     */
    fun decodeFromWire(bytes: ByteArray, originalLength: Int, maxOutputBytes: Int): ByteArray? {
        val unpadded = Unpad.unpad(bytes, originalLength) ?: return null
        return Compression.inflate(unpadded, maxOutputBytes)
    }
}
