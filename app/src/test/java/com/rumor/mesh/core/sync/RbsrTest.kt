package com.rumor.mesh.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RbsrTest {

    private fun items(vararg pairs: Pair<Long, String>): List<RbsrItem> =
        pairs.map { RbsrItem(it.first, it.second) }

    @Test
    fun `identical sets reconcile in one round with no diff`() {
        val shared = items(1L to "a", 2L to "b", 3L to "c")
        val alice = Rbsr(SortedListRbsrStorage(shared))
        val bob = Rbsr(SortedListRbsrStorage(shared))

        val r = bob.respond(alice.initiate())
        assertTrue("both sides agree", r.peerHas.isEmpty() && r.peerNeeds.isEmpty())
    }

    @Test
    fun `tiny set initiates with IdList directly`() {
        val alice = Rbsr(SortedListRbsrStorage(items(1L to "a", 2L to "b")))
        val first = alice.initiate()
        assertEquals(1, first.size)
        assertTrue(first[0] is RbsrFrame.IdList)
    }

    @Test
    fun `one side has extra item — reconciles in one round under threshold`() {
        val aliceItems = items(1L to "a", 2L to "b", 3L to "c")
        val bobItems = items(1L to "a", 2L to "b", 3L to "c", 4L to "d")

        val alice = Rbsr(SortedListRbsrStorage(aliceItems))
        val bob = Rbsr(SortedListRbsrStorage(bobItems))

        val r = bob.respond(alice.initiate())
        assertEquals(listOf("d"), r.peerNeeds)  // bob has "d", alice needs it
        assertTrue("alice's IdList covers everything", r.peerHas.isEmpty())
    }

    @Test
    fun `large differing sets converge via bisection`() {
        val sharedSize = 200
        val shared = (0 until sharedSize).map { RbsrItem(it.toLong(), "m$it") }
        val alice = Rbsr(
            SortedListRbsrStorage(shared + RbsrItem(500L, "alice-only")),
            bisectionFactor = 4,
            idListThreshold = 8,
        )
        val bob = Rbsr(
            SortedListRbsrStorage(shared + RbsrItem(600L, "bob-only")),
            bisectionFactor = 4,
            idListThreshold = 8,
        )

        // Drive the protocol to fixpoint.
        var frames = alice.initiate()
        val aliceHas = mutableListOf<String>()
        val aliceNeeds = mutableListOf<String>()
        repeat(20) {  // bound rounds
            val bobR = bob.respond(frames)
            aliceNeeds += bobR.peerNeeds  // items bob has that alice doesn't
            aliceHas += bobR.peerHas      // items alice has that bob doesn't (bob's view, reversed for alice)
            if (bobR.outgoing.isEmpty()) return@repeat
            val aliceR = alice.respond(bobR.outgoing)
            if (aliceR.outgoing.isEmpty()) {
                frames = emptyList()
                return@repeat
            }
            frames = aliceR.outgoing
        }

        assertTrue("alice should learn about bob-only", aliceNeeds.contains("bob-only"))
        assertTrue("alice should know bob is missing alice-only", aliceHas.contains("alice-only"))
    }

    @Test
    fun `fingerprint is order-independent`() {
        val a = items(1L to "x", 2L to "y", 3L to "z")
        val b = items(3L to "z", 1L to "x", 2L to "y")
        assertTrue(Rbsr.xorFingerprint(a).contentEquals(Rbsr.xorFingerprint(b)))
    }

    @Test
    fun `fingerprint differs on single-item change`() {
        val a = items(1L to "x", 2L to "y")
        val b = items(1L to "x", 2L to "z")
        assertTrue(!Rbsr.xorFingerprint(a).contentEquals(Rbsr.xorFingerprint(b)))
    }

    @Test
    fun `empty range fingerprint is zero block`() {
        val zero = ByteArray(32)
        assertTrue(Rbsr.xorFingerprint(emptyList()).contentEquals(zero))
    }
}
