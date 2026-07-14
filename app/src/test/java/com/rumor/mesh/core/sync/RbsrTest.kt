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
        assertTrue(Rbsr.fingerprint(a).contentEquals(Rbsr.fingerprint(b)))
    }

    @Test
    fun `fingerprint differs on single-item change`() {
        val a = items(1L to "x", 2L to "y")
        val b = items(1L to "x", 2L to "z")
        assertTrue(!Rbsr.fingerprint(a).contentEquals(Rbsr.fingerprint(b)))
    }

    @Test
    fun `empty range fingerprint is deterministic and distinct from non-empty`() {
        assertTrue(Rbsr.fingerprint(emptyList()).contentEquals(Rbsr.fingerprint(emptyList())))
        assertTrue(!Rbsr.fingerprint(emptyList()).contentEquals(Rbsr.fingerprint(items(1L to "a"))))
    }

    @Test
    fun `count is bound into the fingerprint`() {
        // Two sets whose per-item hashes sum to the same value would still
        // differ because the count is hashed in. We can't cheaply construct a
        // sum-collision, but binding the count guards against the whole class:
        // a fingerprint over N items is never equal to one over M != N items
        // sharing a prefix.
        val small = items(1L to "a")
        val large = items(1L to "a", 2L to "b", 3L to "c")
        assertTrue(!Rbsr.fingerprint(small).contentEquals(Rbsr.fingerprint(large)))
    }
}
