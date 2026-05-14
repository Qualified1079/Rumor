package com.rumor.mesh.core.transport.wifidirect

import java.util.Base64
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Minimal Bloom filter for gossip message ID exchange.
 * Uses double hashing (two independent hash functions derived from one).
 */
class BloomFilterData(
    expectedItems: Int,
    falsePositiveRate: Double = 0.01,
) {
    private val bitCount: Int
    private val hashCount: Int
    private val bits: LongArray

    init {
        bitCount = optimalBitCount(expectedItems, falsePositiveRate)
        hashCount = optimalHashCount(bitCount, expectedItems)
        bits = LongArray((bitCount + 63) / 64)
    }

    fun add(item: String) {
        val h1 = murmur3(item.toByteArray(), 0)
        val h2 = murmur3(item.toByteArray(), h1)
        for (i in 0 until hashCount) {
            val bit = ((h1 + i.toLong() * h2) % bitCount + bitCount) % bitCount
            bits[(bit / 64).toInt()] = bits[(bit / 64).toInt()] or (1L shl (bit % 64).toInt())
        }
    }

    fun mightContain(item: String): Boolean {
        val h1 = murmur3(item.toByteArray(), 0)
        val h2 = murmur3(item.toByteArray(), h1)
        for (i in 0 until hashCount) {
            val bit = ((h1 + i.toLong() * h2) % bitCount + bitCount) % bitCount
            if (bits[(bit / 64).toInt()] and (1L shl (bit % 64).toInt()) == 0L) return false
        }
        return true
    }

    fun serialize(): String {
        val bytes = ByteArray(bits.size * 8)
        for (i in bits.indices) {
            val v = bits[i]
            for (b in 0..7) bytes[i * 8 + b] = ((v shr (b * 8)) and 0xFF).toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun murmur3(data: ByteArray, seed: Long): Long {
        var h = seed
        for (b in data) {
            h = h xor b.toLong()
            h = h * 0x517cc1b727220a95L
            h = h xor (h ushr 32)
        }
        return h
    }

    companion object {
        fun deserialize(b64: String, expectedItems: Int): BloomFilterData {
            val filter = BloomFilterData(expectedItems)
            val bytes = Base64.getDecoder().decode(b64)
            for (i in filter.bits.indices) {
                var v = 0L
                for (b in 0..7) {
                    val idx = i * 8 + b
                    if (idx < bytes.size) v = v or ((bytes[idx].toLong() and 0xFF) shl (b * 8))
                }
                filter.bits[i] = v
            }
            return filter
        }

        private fun optimalBitCount(n: Int, p: Double) =
            ceil(-n * ln(p) / (ln(2.0) * ln(2.0))).toInt().coerceAtLeast(64)

        private fun optimalHashCount(m: Int, n: Int) =
            (m.toDouble() / n * ln(2.0)).toInt().coerceIn(1, 10)
    }
}
