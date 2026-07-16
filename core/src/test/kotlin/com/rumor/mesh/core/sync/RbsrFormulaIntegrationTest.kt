package com.rumor.mesh.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O42 — Verify that [SortedListRbsrStorage] respects the configured
 * [FingerprintFormula] end-to-end (storage → Rbsr instance →
 * range-fingerprint output).
 */
class RbsrFormulaIntegrationTest {

    private val items = listOf(
        RbsrItem(100L, "0123456789abcdef0123456789abcdef"),
        RbsrItem(200L, "fedcba9876543210fedcba9876543210"),
        RbsrItem(150L, "00112233445566778899aabbccddeeff"),
    )

    @Test fun `default storage uses v1 XOR fingerprint`() {
        val storage = SortedListRbsrStorage(items)
        val fp = storage.fingerprint(RbsrBound.MIN, RbsrBound.MAX)
        val direct = Rbsr.xorFingerprint(items)
        assertTrue(fp.contentEquals(direct))
    }

    @Test fun `v2 storage uses NIP-77 formula`() {
        val storage = SortedListRbsrStorage(items, FingerprintFormula.V2_NIP77)
        val fp = storage.fingerprint(RbsrBound.MIN, RbsrBound.MAX)
        val direct = Rbsr.nip77Fingerprint(items)
        assertTrue(fp.contentEquals(direct))
    }

    @Test fun `v1 and v2 fingerprints differ on the same input`() {
        val v1 = SortedListRbsrStorage(items, FingerprintFormula.V1_XOR)
            .fingerprint(RbsrBound.MIN, RbsrBound.MAX)
        val v2 = SortedListRbsrStorage(items, FingerprintFormula.V2_NIP77)
            .fingerprint(RbsrBound.MIN, RbsrBound.MAX)
        assertFalse(v1.contentEquals(v2),
            "v1 and v2 are deliberately incompatible — peers running different formulas must NEVER " +
                "match by accident, or they'd treat genuinely-divergent sets as in-sync."
        )
    }

    @Test fun `two storages with the same formula and same items produce identical fingerprints`() {
        val a = SortedListRbsrStorage(items, FingerprintFormula.V2_NIP77)
        val b = SortedListRbsrStorage(items.reversed(), FingerprintFormula.V2_NIP77)
        // Order-independence — the formulas are over commutative accumulators.
        assertTrue(
            a.fingerprint(RbsrBound.MIN, RbsrBound.MAX)
                .contentEquals(b.fingerprint(RbsrBound.MIN, RbsrBound.MAX)),
            "fingerprint must be order-independent (same items, same formula → same bytes)"
        )
    }

    @Test fun `Rbsr instance threading uses storage's formula`() {
        // Smoke test the dispatch: an Rbsr instance built over a v2 storage
        // emits v2 fingerprints. If a future refactor accidentally hard-codes
        // xorFingerprint at the Rbsr layer instead of going through storage,
        // this fails. Use a 100-item set so Rbsr.initiate() emits a
        // Fingerprint frame rather than an IdList (threshold = 16).
        val many = (0 until 100).map {
            RbsrItem(it.toLong(), it.toString(16).padStart(32, '0'))
        }
        val storage = SortedListRbsrStorage(many, FingerprintFormula.V2_NIP77)
        val rbsr = Rbsr(storage)
        val frames = rbsr.initiate()
        val rootFp = (frames.first() as RbsrFrame.Fingerprint).fp
        assertTrue(
            Rbsr.nip77Fingerprint(many).contentEquals(rootFp),
            "Rbsr.initiate() must emit the storage's selected formula"
        )
    }
}
