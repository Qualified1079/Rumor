package com.rumor.mesh.core.sync

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Demonstration that Rumor's RBSR XOR fingerprint formula is NOT byte-compatible
 * with the hoytech/Nostr-NIP-77 reference, despite both being commutative and
 * associative over a set.
 *
 * Rumor:    fingerprint(items) = XOR_i SHA-256("rumor-rbsr-v1:" || ts_i || ":" || id_i)
 * NIP-77:   fingerprint(items) = SHA-256( (Σ_i id_i mod 2^256) || varint(count) )
 *
 * Both produce 32 bytes. Both are independent of item order. Neither is
 * structurally wrong. But the *bytes they produce on the same input set
 * never match*, so a Rumor node and a strfry / Nostr relay running NIP-77
 * cannot interop without a wire-format change.
 *
 * This test pins the divergence so a future audit pass can confirm it
 * empirically. If we ever migrate to the NIP-77 formula (the path to free
 * interop with the Nostr ecosystem), the test must be updated.
 *
 * See `docs/wire-format.md` §2.9 and `docs/RESEARCH_NOTES.md` §1 for the
 * decision context. See O66 in CLAUDE.md for the perf characterisation
 * that informs the promote-to-default gate.
 */
class RbsrFormulaComparisonTest {

    /** Rumor's actual production formula — what's in `Rbsr.kt`. */
    private fun rumorFingerprint(items: List<Pair<Long, String>>): ByteArray {
        val acc = ByteArray(32)
        val md = MessageDigest.getInstance("SHA-256")
        for ((ts, id) in items) {
            md.reset()
            val h = md.digest("rumor-rbsr-v1:$ts:$id".toByteArray(Charsets.UTF_8))
            for (i in acc.indices) acc[i] = (acc[i].toInt() xor h[i].toInt()).toByte()
        }
        return acc
    }

    /**
     * NIP-77 reference formula. Interprets the 32-byte ID as little-endian
     * unsigned int, sums mod 2^256, concatenates with varint(count), hashes.
     *
     * Implementation note: we treat the 32 hex chars of Rumor's id as the
     * canonical 16-byte id (padding to 32 with zeros) — Rumor uses 128-bit
     * random ids, the NIP-77 spec uses 256-bit event-hash ids. For the
     * "do the formulas agree" test we only need consistent interpretation;
     * the padding is documented and reproducible.
     */
    private fun nip77Fingerprint(items: List<Pair<Long, String>>): ByteArray {
        val sum = ByteArray(32) // mod-2^256 accumulator, little-endian
        for ((_, id) in items) {
            val padded = ByteArray(32)
            val raw = id.hexToByteArray()
            // little-endian copy (Rumor id is 16 bytes hex-encoded = 16 raw bytes)
            for (i in raw.indices) padded[i] = raw[i]
            // sum += padded
            var carry = 0
            for (i in 0 until 32) {
                val s = (sum[i].toInt() and 0xFF) + (padded[i].toInt() and 0xFF) + carry
                sum[i] = (s and 0xFF).toByte()
                carry = s ushr 8
            }
        }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sum)
        md.update(varint(items.size.toLong()))
        return md.digest()
    }

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = mutableListOf<Byte>()
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) { out.add(b.toByte()); break }
            out.add((b or 0x80).toByte())
        }
        return out.toByteArray()
    }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) {
            ((Character.digit(this[it * 2], 16) shl 4) +
                Character.digit(this[it * 2 + 1], 16)).toByte()
        }
    }

    private val sample = listOf(
        1700000000000L to "a1b2c3d4e5f60718293a4b5c6d7e8f90",
        1700000001000L to "0011223344556677889900aabbccddee",
        1700000002000L to "ffeeddccbbaa99887766554433221100",
    )

    @Test
    fun `rumor formula is order-independent`() {
        val a = rumorFingerprint(sample)
        val b = rumorFingerprint(sample.reversed())
        assertTrue(a.contentEquals(b), "Rumor XOR formula must be commutative — the whole RBSR algorithm depends on it")
    }

    @Test
    fun `nip77 formula is order-independent`() {
        val a = nip77Fingerprint(sample)
        val b = nip77Fingerprint(sample.reversed())
        assertTrue(a.contentEquals(b), "NIP-77 sum-then-hash formula must be commutative")
    }

    @Test
    fun `rumor and nip77 produce different fingerprints on the same input`() {
        val rumor = rumorFingerprint(sample)
        val nip77 = nip77Fingerprint(sample)
        assertFalse(
            rumor.contentEquals(nip77),
            "If these ever match, either (a) the formulas converged by miracle, (b) one of " +
                "the implementations is broken, or (c) we silently migrated to NIP-77. Audit before " +
                "deleting this assertion. See docs/wire-format.md §2.9."
        )
    }

    @Test
    fun `empty set fingerprints — rumor is zeros, nip77 hashes the empty sum + zero varint`() {
        val rumor = rumorFingerprint(emptyList())
        // Rumor: XOR over empty is the 32-byte zero block.
        assertTrue(rumor.all { it == 0.toByte() }, "Rumor empty-set fingerprint is 32 zero bytes")
        val nip77 = nip77Fingerprint(emptyList())
        // NIP-77: sum is 32 zero bytes, varint(0) = single byte 0x00. SHA-256 of that is NOT zero.
        assertFalse(nip77.all { it == 0.toByte() }, "NIP-77 empty-set fingerprint is SHA-256(zeros || 0x00), not zeros")
        assertFalse(rumor.contentEquals(nip77), "Even the empty-set fingerprints differ between the two formulas")
    }

    // ── production formula parity check: this test's local nip77Fingerprint
    // must match Rbsr.nip77Fingerprint exactly, or we have a regression. ──

    @Test
    fun `production nip77 formula matches the reference impl in this test`() {
        val items = sample.map { (ts, id) -> com.rumor.mesh.core.sync.RbsrItem(ts, id) }
        val production = com.rumor.mesh.core.sync.Rbsr.nip77Fingerprint(items)
        val reference  = nip77Fingerprint(sample)
        assertTrue(
            production.contentEquals(reference),
            "Production Rbsr.nip77Fingerprint must match this test's reference impl byte-for-byte. " +
                "Divergence means the production formula drifted from NIP-77 — fix Rbsr.kt, not this test."
        )
    }

    @Test
    fun `production v1 still differs from v2 by formula dispatch`() {
        val items = sample.map { (ts, id) -> com.rumor.mesh.core.sync.RbsrItem(ts, id) }
        val v1 = com.rumor.mesh.core.sync.Rbsr.fingerprint(items, com.rumor.mesh.core.sync.FingerprintFormula.V1_XOR)
        val v2 = com.rumor.mesh.core.sync.Rbsr.fingerprint(items, com.rumor.mesh.core.sync.FingerprintFormula.V2_NIP77)
        assertFalse(v1.contentEquals(v2), "v1 and v2 must produce different fingerprints by construction")
    }

    @Test
    fun `production v2 is commutative — required for any RBSR fingerprint`() {
        val forward = sample.map { (ts, id) -> com.rumor.mesh.core.sync.RbsrItem(ts, id) }
        val reverse = forward.reversed()
        val a = com.rumor.mesh.core.sync.Rbsr.nip77Fingerprint(forward)
        val b = com.rumor.mesh.core.sync.Rbsr.nip77Fingerprint(reverse)
        assertTrue(a.contentEquals(b), "v2 NIP-77 formula must be commutative; without this RBSR breaks")
    }
}
