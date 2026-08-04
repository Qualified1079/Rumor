package com.rumor.mesh.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * O121(d): [SortedListRbsrStorage.items] was replaced from an O(n) per-query
 * `filter` to an O(log n) binary-search slice. This pins the new implementation
 * against the ORIGINAL filter semantics (copied verbatim below as the oracle)
 * across randomized item sets and bounds — including duplicate timestamps, the
 * MIN/MAX sentinels, and out-of-range bounds — so the optimisation can't silently
 * change which items a range returns (an RBSR fingerprint mismatch would stall a
 * whole sync session).
 */
class SortedListRbsrStorageTest {

    /** The pre-optimisation implementation, verbatim, used as the correctness oracle. */
    private fun bruteForce(sorted: List<RbsrItem>, lower: RbsrBound, upper: RbsrBound): List<RbsrItem> =
        sorted.filter { item ->
            val lowOk = lower.timestamp < item.timestamp ||
                (lower.timestamp == item.timestamp && lower.id <= item.id) ||
                lower == RbsrBound.MIN
            val highOk = item.timestamp < upper.timestamp ||
                (item.timestamp == upper.timestamp && item.id < upper.id) ||
                upper == RbsrBound.MAX
            lowOk && highOk
        }

    @Test
    fun `binary-search items matches the brute-force filter across random bounds`() {
        val rng = Random(0xB1_5EED)
        repeat(400) {
            // Small timestamp/id domains → frequent ties and adjacency, the cases
            // where an off-by-one in the bound comparison would show up.
            val n = rng.nextInt(0, 40)
            val items = (0 until n).map { RbsrItem(rng.nextLong(0, 6), "id${rng.nextInt(0, 6)}") }
            val store = SortedListRbsrStorage(items)
            val sorted = items.sorted()

            // Candidate bounds: the sentinels, exact item positions, and gaps.
            val candidates = buildList {
                add(RbsrBound.MIN); add(RbsrBound.MAX)
                repeat(6) { add(RbsrBound(rng.nextLong(-1, 7), "id${rng.nextInt(-1, 7)}")) }
                sorted.forEach { add(RbsrBound(it.timestamp, it.id)) }
            }
            for (lo in candidates) for (hi in candidates) {
                assertEquals(
                    bruteForce(sorted, lo, hi),
                    store.items(lo, hi),
                    "range [$lo, $hi) over $sorted",
                )
            }
        }
    }

    @Test
    fun `full range returns all items in ascending order`() {
        val items = listOf(RbsrItem(5, "b"), RbsrItem(1, "a"), RbsrItem(5, "a"), RbsrItem(3, "z"))
        val all = SortedListRbsrStorage(items).items(RbsrBound.MIN, RbsrBound.MAX)
        assertEquals(items.sorted(), all)
    }

    @Test
    fun `empty store yields empty ranges`() {
        val store = SortedListRbsrStorage(emptyList())
        assertEquals(emptyList(), store.items(RbsrBound.MIN, RbsrBound.MAX))
    }
}
