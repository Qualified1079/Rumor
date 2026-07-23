package com.rumor.mesh.core.transport.wifidirect

import org.apache.commons.codec.digest.MurmurHash3
import com.rumor.mesh.core.platform.Base64Codec
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Minimal Bloom filter for gossip message ID exchange.
 * Uses double hashing (two independent hash functions derived from one).
 *
 * Lives in :core so both the Android transport (GossipSession) and the
 * simulator (SimTransport) exercise the same code path. False positives are
 * the protocol's primary failure mode at scale — a peer that thinks it has
 * a message it doesn't will silently miss it (until its set changes and the
 * filter shifts). At the default rate that's a skipped/late chat message ~1
 * in several thousand — see [DEFAULT_FALSE_POSITIVE_RATE].
 *
 * The rate is an implicit wire constant: [deserialize] rebuilds the filter at
 * the default rate, so both peers MUST use the same value or their bit layouts
 * won't align. Change it only via the default here.
 */
class BloomFilterData(
    expectedItems: Int,
    falsePositiveRate: Double = DEFAULT_FALSE_POSITIVE_RATE,
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
        val (h1, h2) = itemHashes(item)
        for (i in 0 until hashCount) {
            val bit = ((h1 + i.toLong() * h2) % bitCount + bitCount) % bitCount
            bits[(bit / 64).toInt()] = bits[(bit / 64).toInt()] or (1L shl (bit % 64).toInt())
        }
    }

    fun mightContain(item: String): Boolean {
        val (h1, h2) = itemHashes(item)
        for (i in 0 until hashCount) {
            val bit = ((h1 + i.toLong() * h2) % bitCount + bitCount) % bitCount
            if (bits[(bit / 64).toInt()] and (1L shl (bit % 64).toInt()) == 0L) return false
        }
        return true
    }

    /**
     * Both double-hashing bases from one MurmurHash3 x64/128 pass
     * (commons-codec). Replaces a hand-rolled byte-wise xor-multiply mix that
     * was labelled murmur3 but wasn't — real murmur has characterized
     * avalanche/independence, which the double-hashing scheme assumes.
     * Wire-affecting: bit layouts differ from the old mix, so peers must run
     * the same build (bundled with the 0.01%-FP change in the same
     * pre-release window — see DEFAULT_FALSE_POSITIVE_RATE).
     */
    private fun itemHashes(item: String): Pair<Long, Long> {
        val h = MurmurHash3.hash128x64(item.toByteArray())
        return h[0] to h[1]
    }

    fun serialize(): String {
        val bytes = ByteArray(bits.size * 8)
        for (i in bits.indices) {
            val v = bits[i]
            for (b in 0..7) bytes[i * 8 + b] = ((v shr (b * 8)) and 0xFF).toByte()
        }
        return Base64Codec.encode(bytes)
    }

    companion object {
        /**
         * O42/UX: 0.01% — empirically ~1 skipped message in 5,000-8,000 per
         * exchange (measured in `BloomFalsePositiveTest`; the custom murmur mix
         * + hashCount cap keep it close to configured). The old 0.01 (1%) meant
         * ~1 in 100, which on a quiet mesh (static filter) strands a chat message
         * noticeably. Adaptive selection (O42) keeps bloom on small sets only, so
         * the ~2× wire cost vs 1% is still under 2 KB — a near-free UX win. Above
         * the adaptive set-size threshold, RBSR takes over and is exact (zero FP).
         */
        const val DEFAULT_FALSE_POSITIVE_RATE: Double = 0.0001

        /**
         * O13: graceful-fallback variant. A peer can send an adversarial
         * `expectedItems` (e.g. `Int.MAX_VALUE`) that drives the constructor
         * into a multi-GB allocation. Returns null on OOM / IllegalArgumentException
         * so the caller can fall through to "no bloom filter, capped offer batch."
         * Catching OutOfMemoryError is unusual but safe here: we only allocate
         * inside the constructor (no shared state to leave in a bad mode), and
         * the alternative is the peer crashing the whole engine for free.
         */
        fun deserializeOrNull(b64: String, expectedItems: Int): BloomFilterData? = try {
            deserialize(b64, expectedItems)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: NegativeArraySizeException) {
            null
        }

        fun deserialize(b64: String, expectedItems: Int): BloomFilterData {
            // O117: bind the claimed size to the actual payload BEFORE any
            // allocation. serialize() emits exactly bits.size*8 bytes and the
            // bit layout is a pure function of expectedItems, so an honest
            // claim always matches — while a fabricated huge expectedItems
            // would have to ship the matching multi-MB payload through the
            // frame cap to get past this. Closes the slow-DoS half of O13
            // (heavy allocation + GC pressure below the OOM the catch sees).
            require(expectedItems >= 0) { "negative expectedItems" }
            val bytes = Base64Codec.decode(b64)
            val claimedBits = optimalBitCount(expectedItems, DEFAULT_FALSE_POSITIVE_RATE)
            val claimedBytes = ((claimedBits.toLong() + 63) / 64) * 8
            require(claimedBytes == bytes.size.toLong()) {
                "bloom size mismatch: expectedItems=$expectedItems implies $claimedBytes bytes, got ${bytes.size}"
            }
            val filter = BloomFilterData(expectedItems)
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
