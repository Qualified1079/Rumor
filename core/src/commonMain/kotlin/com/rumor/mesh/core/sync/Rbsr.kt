package com.rumor.mesh.core.sync

import com.rumor.mesh.core.platform.Sha256

/**
 * Range-Based Set Reconciliation (O42).
 *
 * A drop-in replacement for the bloom-filter offer/want phase that gives
 * O(d log N) bandwidth without parameter tuning and without a decode-fail mode.
 * Algorithm follows Meijers 2023 (arXiv 2212.13567) — the same shape that
 * Nostr's strfry deploys via NIP-77. This implementation is not wire-compatible
 * with strfry; it is tailored to Rumor's `(sentAtMs, id)` ordering and uses
 * Rumor's existing `WireJson` envelope.
 *
 * Concept: both peers hold an ordered set of items keyed by `(timestamp, id)`.
 * They compare XOR fingerprints of ranges; ranges whose fingerprints match are
 * reconciled, ranges that don't get bisected. Recursion continues until every
 * range is either skipped (agreement) or small enough to enumerate by id-list.
 *
 * Integration into [com.rumor.mesh.core.transport.wifidirect.GossipSession] is
 * gated on both peers advertising the `rbsr-v1` feature in HELLO. Until that
 * lands, the algorithm is unwired — exercised only by tests. Wire-locking the
 * format requires a review pass against the reference impl (hoytech/negentropy)
 * for fingerprint stability under bound-edge items.
 */

/** A single item participating in the set being reconciled. */
data class RbsrItem(val timestamp: Long, val id: String) : Comparable<RbsrItem> {
    override fun compareTo(other: RbsrItem): Int {
        val ts = timestamp.compareTo(other.timestamp)
        return if (ts != 0) ts else id.compareTo(other.id)
    }
}

/**
 * Range boundary. The protocol enumerates half-open ranges `[lower, upper)`
 * over the total ordering on [RbsrItem]. `lower == MIN` and `upper == MAX`
 * cover the universe.
 */
data class RbsrBound(val timestamp: Long, val id: String) : Comparable<RbsrBound> {
    override fun compareTo(other: RbsrBound): Int {
        val ts = timestamp.compareTo(other.timestamp)
        return if (ts != 0) ts else id.compareTo(other.id)
    }

    companion object {
        val MIN = RbsrBound(Long.MIN_VALUE, "")
        // A sentinel above any plausible UTF-16 id string.
        val MAX = RbsrBound(Long.MAX_VALUE, "￿")
    }
}

/** Storage interface the algorithm queries against. Implementations are responsible for sort order. */
/** Maximum bisection rounds before falling through with whatever diffs we have. */
/**
 * Maximum bisection rounds before exiting with whatever diff we have. With
 * `bisectionFactor = 16`, N rounds covers 16^N distinct sub-ranges in theory.
 * Set to 12 — that's 16^12 sub-ranges, astronomically large — so the cap
 * functions as a safety against infinite recursion, not a budget. Per O61:
 * an earlier value of 5 was hitting the cap on partition-heal scenarios
 * (~1M ranges turned out not to be enough headroom in practice). If a
 * future scenario hits 12, the algorithm has a real bug — not a tuning gap.
 */
const val MAX_RBSR_ROUNDS: Int = 12

interface RbsrStorage {
    /** Items in `[lower, upper)` in ascending order. */
    fun items(lower: RbsrBound, upper: RbsrBound): List<RbsrItem>

    /** XOR fingerprint of items in `[lower, upper)`. Must equal across peers iff the sets match. */
    fun fingerprint(lower: RbsrBound, upper: RbsrBound): ByteArray =
        Rbsr.xorFingerprint(items(lower, upper))
}

/** Sorted in-memory storage. Useful for tests and small in-memory deltas. */
class SortedListRbsrStorage(items: Collection<RbsrItem>) : RbsrStorage {
    private val sorted: List<RbsrItem> = items.sorted()

    override fun items(lower: RbsrBound, upper: RbsrBound): List<RbsrItem> =
        sorted.filter { item ->
            val lowOk = lower.timestamp < item.timestamp ||
                (lower.timestamp == item.timestamp && lower.id <= item.id) ||
                lower == RbsrBound.MIN
            val highOk = item.timestamp < upper.timestamp ||
                (item.timestamp == upper.timestamp && item.id < upper.id) ||
                upper == RbsrBound.MAX
            lowOk && highOk
        }
}

/** Frame exchanged between peers during reconciliation. */
sealed class RbsrFrame {
    abstract val lower: RbsrBound
    abstract val upper: RbsrBound

    /** "I have the same fingerprint for this range — we agree." */
    data class Skip(override val lower: RbsrBound, override val upper: RbsrBound) : RbsrFrame()

    /** "Here's my fingerprint for this range; respond if you differ." */
    data class Fingerprint(
        override val lower: RbsrBound,
        override val upper: RbsrBound,
        val fp: ByteArray,
    ) : RbsrFrame() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Fingerprint) return false
            return lower == other.lower && upper == other.upper && fp.contentEquals(other.fp)
        }
        override fun hashCode(): Int =
            (lower.hashCode() * 31 + upper.hashCode()) * 31 + fp.contentHashCode()
    }

    /** "Here are my item IDs in this range." Sent for small ranges or as the terminal step. */
    data class IdList(
        override val lower: RbsrBound,
        override val upper: RbsrBound,
        val ids: List<String>,
    ) : RbsrFrame()
}

/** Result of processing a peer's frames. */
data class RbsrResponse(
    /** Frames to send back to the peer for the next round. */
    val outgoing: List<RbsrFrame>,
    /** Item IDs the peer has that we don't — we should request these. */
    val peerHas: List<String>,
    /** Item IDs we have that the peer doesn't — we should offer these. */
    val peerNeeds: List<String>,
) {
    val done: Boolean get() = outgoing.isEmpty()
}

class Rbsr(
    private val storage: RbsrStorage,
    /** How many subranges to bisect into when a fingerprint mismatch needs splitting. */
    private val bisectionFactor: Int = 16,
    /** Below this many items in a range, send IDs instead of bisecting further. */
    private val idListThreshold: Int = 16,
) {
    /**
     * Produce the initial frame(s) for an exchange. If our set fits in an
     * IdList, send it directly — peer can compute the diff in one round-trip.
     */
    fun initiate(): List<RbsrFrame> {
        val all = storage.items(RbsrBound.MIN, RbsrBound.MAX)
        return if (all.size <= idListThreshold) {
            listOf(RbsrFrame.IdList(RbsrBound.MIN, RbsrBound.MAX, all.map { it.id }))
        } else {
            listOf(RbsrFrame.Fingerprint(RbsrBound.MIN, RbsrBound.MAX, storage.fingerprint(RbsrBound.MIN, RbsrBound.MAX)))
        }
    }

    /**
     * Process a batch of frames from the peer. Returns frames to send back,
     * plus accumulated diffs against this side's storage.
     */
    fun respond(incoming: List<RbsrFrame>): RbsrResponse {
        val outgoing = mutableListOf<RbsrFrame>()
        val peerHas = mutableListOf<String>()
        val peerNeeds = mutableListOf<String>()

        for (frame in incoming) {
            when (frame) {
                is RbsrFrame.Skip -> {
                    // Peer confirmed agreement on this range — nothing to do.
                }
                is RbsrFrame.IdList -> {
                    val ours = storage.items(frame.lower, frame.upper).mapTo(mutableSetOf()) { it.id }
                    val theirs = frame.ids.toSet()
                    (theirs - ours).forEach { peerHas.add(it) }
                    (ours - theirs).forEach { peerNeeds.add(it) }
                }
                is RbsrFrame.Fingerprint -> {
                    val ourFp = storage.fingerprint(frame.lower, frame.upper)
                    if (ourFp.contentEquals(frame.fp)) {
                        outgoing.add(RbsrFrame.Skip(frame.lower, frame.upper))
                    } else {
                        val ourItems = storage.items(frame.lower, frame.upper)
                        if (ourItems.size <= idListThreshold) {
                            outgoing.add(RbsrFrame.IdList(frame.lower, frame.upper, ourItems.map { it.id }))
                        } else {
                            for ((lo, hi) in subdivide(frame.lower, frame.upper, ourItems)) {
                                outgoing.add(RbsrFrame.Fingerprint(lo, hi, storage.fingerprint(lo, hi)))
                            }
                        }
                    }
                }
            }
        }
        return RbsrResponse(outgoing, peerHas, peerNeeds)
    }

    private fun subdivide(
        lower: RbsrBound,
        upper: RbsrBound,
        items: List<RbsrItem>,
    ): List<Pair<RbsrBound, RbsrBound>> {
        val n = items.size
        val factor = bisectionFactor.coerceAtMost(n)
        if (factor < 2) return listOf(lower to upper)
        val chunkSize = (n + factor - 1) / factor
        val result = mutableListOf<Pair<RbsrBound, RbsrBound>>()
        var prev = lower
        var i = chunkSize
        while (i < n) {
            val boundary = RbsrBound(items[i].timestamp, items[i].id)
            result.add(prev to boundary)
            prev = boundary
            i += chunkSize
        }
        result.add(prev to upper)
        return result
    }

    companion object {
        /**
         * v1 (legacy, Rumor-original): XOR over SHA-256 of each domain-tagged item.
         * Order-independent. Empty range yields the 32-byte zero block.
         *
         * Used when peers negotiate `rbsr-v1` in HELLO `supportedFeatures`. Not
         * byte-compatible with hoytech/Nostr-NIP-77 — use [nip77Fingerprint] for
         * interop with strfry / any NIP-77 implementation. See O42 in CLAUDE.md
         * and `RbsrFormulaComparisonTest` for the divergence proof.
         */
        fun xorFingerprint(items: List<RbsrItem>): ByteArray {
            val acc = ByteArray(32)
            for (item in items) {
                val hash = Sha256.digest("rumor-rbsr-v1:${item.timestamp}:${item.id}".toByteArray(Charsets.UTF_8))
                for (i in acc.indices) acc[i] = (acc[i].toInt() xor hash[i].toInt()).toByte()
            }
            return acc
        }

        /**
         * v2 (NIP-77 / hoytech-compatible): `SHA-256( sum_mod_2^256(item_ids) || varint(count) )`.
         *
         * Item ids are interpreted as 32-byte little-endian unsigned ints (with
         * zero-padding if the id is shorter than 32 bytes — Rumor's 128-bit
         * random ids are 16 bytes hex-encoded = 16 raw bytes, padded to 32 with
         * leading zeros). Sum mod 2^256 of the raw byte arrays is computed
         * little-endian with carry. The count is appended as a varint (LEB128).
         * SHA-256 of the concatenation is the fingerprint.
         *
         * This formula is byte-compatible with hoytech/negentropy and Nostr
         * NIP-77 (subject to the id-interpretation note above). Wins us free
         * interop with strfry and any NIP-77 relay — which unblocks O54
         * (transport plugins) and O72 (Nostr fallback transport) to reuse
         * RBSR machinery without re-implementing.
         *
         * Used when peers negotiate `rbsr-v2` in HELLO `supportedFeatures`.
         * Production default until promote-to-default (see O42).
         */
        fun nip77Fingerprint(items: List<RbsrItem>): ByteArray {
            val sum = ByteArray(32)  // little-endian mod-2^256 accumulator
            for (item in items) {
                val raw = hexToBytes(item.id)
                addLittleEndianMod256(sum, raw)
            }
            return Sha256.digest(sum + varint(items.size.toLong()))
        }

        /**
         * Dispatcher used by [RbsrStorage.fingerprint] when a formula is supplied.
         * Default is [FingerprintFormula.V1_XOR] for back-compat with current
         * sim runs and tests; capability-gated v2 is the migration path.
         */
        fun fingerprint(items: List<RbsrItem>, formula: FingerprintFormula): ByteArray = when (formula) {
            FingerprintFormula.V1_XOR -> xorFingerprint(items)
            FingerprintFormula.V2_NIP77 -> nip77Fingerprint(items)
        }

        // ── helpers for the NIP-77 path ──────────────────────────────────────

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "RBSR item id must be even-length hex; got '$hex'" }
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = hexDigit(hex[i * 2])
                val lo = hexDigit(hex[i * 2 + 1])
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        private fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> error("non-hex char '$c' in RBSR id")
        }

        /** acc += raw, treating both as little-endian unsigned, mod 2^256. */
        private fun addLittleEndianMod256(acc: ByteArray, raw: ByteArray) {
            var carry = 0
            for (i in 0 until 32) {
                val a = acc[i].toInt() and 0xFF
                val r = if (i < raw.size) raw[i].toInt() and 0xFF else 0
                val s = a + r + carry
                acc[i] = (s and 0xFF).toByte()
                carry = s ushr 8
            }
            // overflow past 2^256 wraps (carry discarded — that's the "mod 2^256")
        }

        /** LEB128 (Protobuf-compatible) varint encoding of an unsigned long. */
        private fun varint(value: Long): ByteArray {
            var v = value
            val out = ArrayList<Byte>(10)
            while (true) {
                val b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v == 0L) { out.add(b.toByte()); break }
                out.add((b or 0x80).toByte())
            }
            return out.toByteArray()
        }
    }
}

/**
 * Which RBSR fingerprint formula a session is using. Chosen at HELLO time via
 * the highest-version capability tag both peers advertise in
 * `supportedFeatures` (`rbsr-v2` preferred when both have it; falls back to
 * `rbsr-v1`). Production capability list is empty until the v2 promote-to-
 * default gate passes (see O42 in CLAUDE.md).
 */
enum class FingerprintFormula {
    /** Rumor-original XOR-of-domain-tagged-SHA-256. Tag: `rbsr-v1`. */
    V1_XOR,
    /** Hoytech / Nostr NIP-77 sum-mod-2^256 then SHA-256. Tag: `rbsr-v2`. */
    V2_NIP77,
}
