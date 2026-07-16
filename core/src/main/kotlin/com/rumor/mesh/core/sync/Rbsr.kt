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
 * gated on both peers advertising the `rbsr-v1` feature in HELLO.
 *
 * Reference review (O42) done: fingerprint aligned to negentropy's additive +
 * count construction (see [Rbsr.fingerprint]); bound values survive the wire
 * exactly (kotlinx encodes `Long` as an integer literal, no `Double` round-trip,
 * and both ends are Rumor — this is deliberately not strfry-compatible); range
 * membership is a pure `(timestamp, id)` comparison applied identically on both
 * sides, so a boundary landing on an item key partitions consistently. Bound ids
 * are assumed to sort below [RbsrBound.MAX]'s `"￿"` sentinel — true for
 * Rumor message ids (hex/base64/UUID, all ASCII).
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

/**
 * Adaptive summary-method threshold (O42). Below this set size the bloom filter
 * is cheaper AND — at the tuned 0.01% false-positive rate — near-exact, so bloom
 * wins outright. Above it, re-shipping a full bloom every exchange (linear in the
 * set) is wasteful next to RBSR (scales with the *difference*), and RBSR is
 * exact. Simulator crossover with the 0.01%-FP bloom sits ~2.5k; 3k gives RBSR a
 * clear-win margin (see `RbsrBandwidthScenarioTest`).
 *
 * The RBSR win is largest exactly where the app is headed: persistent links
 * (O2/G19) between static/free anchors (O55) re-syncing a big store where the
 * difference is tiny — bloom re-sends ~N every round to deliver nothing.
 */
const val RBSR_MIN_SET_SIZE: Int = 3_000

/**
 * Symmetric, deterministic choice of summary method — BOTH peers compute the
 * same answer from the same inputs (each other's advertised set size + shared
 * capability), so they never split modes (one RBSR, one bloom → session stall).
 * Gated on the *larger* set: if either side is carrying a big store, the full-
 * bloom-every-round cost is what we're avoiding.
 */
fun shouldUseRbsr(bothSupportRbsr: Boolean, localSetSize: Int, peerSetSize: Int): Boolean =
    bothSupportRbsr && maxOf(localSetSize, peerSetSize) >= RBSR_MIN_SET_SIZE

interface RbsrStorage {
    /** Items in `[lower, upper)` in ascending order. */
    fun items(lower: RbsrBound, upper: RbsrBound): List<RbsrItem>

    /** Fingerprint of items in `[lower, upper)`. Must equal across peers iff the sets match. */
    fun fingerprint(lower: RbsrBound, upper: RbsrBound): ByteArray =
        Rbsr.fingerprint(items(lower, upper))
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
         * Range fingerprint: sum of per-item SHA-256 hashes mod 2^256, then
         * SHA-256 over `(sum || count)`. Order-independent (addition commutes)
         * and equal across peers iff the sets match.
         *
         * **Addition, not XOR**, and the count is bound in — this follows the
         * hoytech/negentropy reference rather than the naive XOR accumulator.
         * XOR is linear over GF(2)^256, so a peer that can choose message ids
         * could solve for two *different* ranges with an equal fingerprint; the
         * range would then be wrongly `Skip`ped and its messages silently fail
         * to reconcile. Forging a collision under modular addition is a 256-bit
         * subset-sum problem — infeasible. Binding the count additionally
         * distinguishes sets that happen to share a sum. Empty range → SHA-256
         * of `(0^32 || 0)`, a fixed non-zero block; the algorithm only compares
         * fingerprints for equality, so the constant is immaterial.
         */
        fun fingerprint(items: List<RbsrItem>): ByteArray {
            val sum = ByteArray(32)
            for (item in items) {
                val hash = Sha256.digest("rumor-rbsr-v1:${item.timestamp}:${item.id}".toByteArray(Charsets.UTF_8))
                var carry = 0
                for (i in 31 downTo 0) {
                    val s = (sum[i].toInt() and 0xFF) + (hash[i].toInt() and 0xFF) + carry
                    sum[i] = s.toByte()
                    carry = s ushr 8   // final carry out of byte 0 is dropped → mod 2^256
                }
            }
            val count = items.size.toLong()
            val countBytes = ByteArray(8) { i -> (count ushr (8 * (7 - i))).toByte() }
            return Sha256.digest("rumor-rbsr-fp-v1:".toByteArray(Charsets.UTF_8) + sum + countBytes)
        }
    }
}
