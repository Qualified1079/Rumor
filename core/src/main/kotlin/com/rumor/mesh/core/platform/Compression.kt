package com.rumor.mesh.core.platform

import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater


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
/**
 * JVM for [Compression]. Uses `java.util.zip.Deflater` /
 * `java.util.zip.Inflater` with `nowrap = true` (raw deflate per RFC 1951,
 * no zlib header).
 *
 * Both calls allocate and end the (De|In)flater per invocation. The
 * native resource cost is negligible for our payload sizes (up to 64 KB
 * before chunking); a thread-local cache could be added if profiling
 * ever shows the alloc matters.
 */
object Compression {

    fun deflate(plaintext: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, /* nowrap = */ true)
        try {
            d.setInput(plaintext)
            d.finish()
            // Worst case output is slightly larger than input for already-
            // compressed content; oversize the buffer and trim.
            val buf = ByteArray(plaintext.size + 64)
            var written = 0
            while (!d.finished()) {
                if (written == buf.size) {
                    // Grow if needed — rare for plain text.
                    val grown = ByteArray(buf.size * 2)
                    buf.copyInto(grown)
                    return deflateGrowing(d, grown, written)
                }
                written += d.deflate(buf, written, buf.size - written)
            }
            return buf.copyOf(written)
        } finally {
            d.end()
        }
    }

    private fun deflateGrowing(d: Deflater, initial: ByteArray, initialWritten: Int): ByteArray {
        var buf = initial
        var written = initialWritten
        while (!d.finished()) {
            if (written == buf.size) {
                val grown = ByteArray(buf.size * 2)
                buf.copyInto(grown)
                buf = grown
            }
            written += d.deflate(buf, written, buf.size - written)
        }
        return buf.copyOf(written)
    }

    fun inflate(compressed: ByteArray, maxOutputBytes: Int): ByteArray? {
        if (maxOutputBytes <= 0) return null
        val i = Inflater(/* nowrap = */ true)
        try {
            i.setInput(compressed)
            // Start at the smaller of maxOutputBytes and 4x input — most
            // text compresses 2-4×, so this avoids the grow path for the
            // common case.
            val initialBufSize = minOf(maxOutputBytes, maxOf(64, compressed.size * 4))
            var buf = ByteArray(initialBufSize)
            var written = 0
            while (!i.finished()) {
                if (i.needsInput()) return null  // truncated
                if (written == buf.size) {
                    if (buf.size >= maxOutputBytes) return null  // hit cap
                    val nextSize = minOf(maxOutputBytes, buf.size * 2)
                    val grown = ByteArray(nextSize)
                    buf.copyInto(grown)
                    buf = grown
                }
                val produced = try {
                    i.inflate(buf, written, buf.size - written)
                } catch (e: DataFormatException) {
                    return null
                }
                if (produced == 0 && !i.finished()) {
                    if (i.needsInput() || i.needsDictionary()) return null
                }
                written += produced
                if (written > maxOutputBytes) return null
            }
            return buf.copyOf(written)
        } finally {
            i.end()
        }
    }
}
