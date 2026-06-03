package com.rumor.mesh.core.platform

/**
 * Platform shim: Base64 encode / decode. Standard alphabet (RFC 4648 §4),
 * with padding. Throws on malformed input — configuration error, not a
 * recoverable runtime condition.
 *
 * No URL-safe variant. Add one if a real use case appears. Current callers
 * (wire frames, ciphertext envelopes, signatures) all use the standard
 * alphabet; URL-safe is for query-string-style transport which we don't have.
 *
 * Phase 1c shim per docs/PHASE_1C_SHIM_SURFACE.md. Highest-fanin shim
 * (5 callers: CryptoManager, Chunker, TransferSender, TransferAssembler,
 * BloomFilterData).
 */
expect object Base64Codec {
    fun encode(bytes: ByteArray): String
    fun decode(s: String): ByteArray
}
