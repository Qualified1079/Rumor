package com.rumor.mesh.core.platform

import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * JVM actual for [Compression]. Uses `java.util.zip.Deflater` /
 * `java.util.zip.Inflater` with `nowrap = true` (raw deflate per RFC 1951,
 * no zlib header).
 *
 * Both calls allocate and end the (De|In)flater per invocation. The
 * native resource cost is negligible for our payload sizes (up to 64 KB
 * before chunking); a thread-local cache could be added if profiling
 * ever shows the alloc matters.
 */
actual object Compression {

    actual fun deflate(plaintext: ByteArray): ByteArray {
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

    actual fun inflate(compressed: ByteArray, maxOutputBytes: Int): ByteArray? {
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
