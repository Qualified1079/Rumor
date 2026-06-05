package com.rumor.mesh.core.platform

/**
 * Platform shim: raw-deflate one-shot compression.
 *
 * "Raw" deflate means no zlib header / footer, no gzip wrapper — pure
 * RFC 1951 deflate stream. Smaller wire overhead than zlib (no 2-byte
 * header + 4-byte adler32) and the framing is the wire format's job, not
 * the compressor's.
 *
 * **Pipeline position:** these calls are the *compress* step in the
 * `compress → pad → encrypt → sign → chunk` O76 pipeline. They are
 * always called on whole sender-controlled plaintext payloads (a single
 * TEXT message). No streaming, no shared compression context — the
 * CRIME/BREACH constraint documented in `PaddingBuckets.kt` requires
 * one fresh compression per message.
 *
 * **Why raw deflate, not gzip / zstd / brotli?**
 *  - gzip adds 10–20 bytes of header / trailer; on a 256 B bucket
 *    that's a non-trivial overhead tax.
 *  - zstd is better but adds a third-party native dep on most
 *    platforms — a serious portability cost for our cross-platform
 *    target (Android + iOS + Linux relay nodes + MCU eventually).
 *  - brotli is text-optimized but is JS/web-oriented; raw deflate
 *    matches what TLS, HTTP/2 HPACK, PNG, and ZIP already use, so
 *    every platform has a mature impl in the standard library.
 *
 * **No streaming.** If a TEXT payload is larger than the 64 KB top
 * bucket, the chunker takes over; each chunk is compressed
 * independently before chunking.
 *
 * **Failure modes:** [inflate] returns null on malformed input
 * (truncated stream, garbage bytes, exceeded budget). Callers MUST
 * handle null — a peer can send a deliberately-bad blob.
 *
 * Phase 1c shim, sibling of Sha256 / PlatformCrypto / Base64Codec.
 */
expect object Compression {
    /** Compress with raw deflate at default level. Returns the compressed bytes. */
    fun deflate(plaintext: ByteArray): ByteArray

    /**
     * Decompress raw-deflate bytes. Returns null on any error
     * (malformed stream, output larger than [maxOutputBytes]).
     *
     * @param maxOutputBytes Hard upper bound on the decompressed size.
     *   Prevents a malicious peer from sending a 100-byte input that
     *   expands to gigabytes (zip-bomb resistance at the compression
     *   layer; the application layer covers the file-format case in
     *   O14).
     */
    fun inflate(compressed: ByteArray, maxOutputBytes: Int): ByteArray?
}
