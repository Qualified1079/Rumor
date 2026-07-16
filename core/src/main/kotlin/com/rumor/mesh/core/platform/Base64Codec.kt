package com.rumor.mesh.core.platform

import java.util.Base64


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
/**
 * JVM for [Base64Codec]. Delegates to `java.util.Base64`, which is
 * the standard-alphabet codec since JDK 8. Throws `IllegalArgumentException`
 * on malformed decode input.
 */
object Base64Codec {
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun decode(s: String): ByteArray = decoder.decode(s)
}
