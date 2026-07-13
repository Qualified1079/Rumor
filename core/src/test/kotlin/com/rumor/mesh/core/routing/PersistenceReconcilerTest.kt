package com.rumor.mesh.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceReconcilerTest {

    private fun plan(vararg links: Pair<String, String>) =
        BackbonePlan(links.map { Link.of(it.first, it.second) }.toSet())

    @Test
    fun `new links are added immediately`() {
        val r = PersistenceReconciler(holdRounds = 3)
        val delta = r.reconcile(plan("a" to "b", "b" to "c"))
        assertEquals(setOf(Link.of("a", "b"), Link.of("b", "c")), delta.toAdd)
        assertTrue(delta.toRemove.isEmpty())
        assertEquals(2, r.activeLinks().size)
    }

    @Test
    fun `a link is not torn down until it stays absent for holdRounds`() {
        val r = PersistenceReconciler(holdRounds = 3)
        r.reconcile(plan("a" to "b"))                 // establish
        val empty = plan()

        val d1 = r.reconcile(empty)                   // absent 1
        val d2 = r.reconcile(empty)                   // absent 2
        assertTrue("must not drop before the hold window expires", d1.toRemove.isEmpty())
        assertTrue(d2.toRemove.isEmpty())
        assertTrue("link is still held", Link.of("a", "b") in r.activeLinks())

        val d3 = r.reconcile(empty)                   // absent 3 -> fire
        assertEquals(setOf(Link.of("a", "b")), d3.toRemove)
        assertTrue(r.activeLinks().isEmpty())
    }

    @Test
    fun `a blip that resolves before the window leaves the link untouched`() {
        val r = PersistenceReconciler(holdRounds = 3)
        r.reconcile(plan("a" to "b"))
        r.reconcile(plan())                            // absent 1
        r.reconcile(plan())                            // absent 2
        val recovered = r.reconcile(plan("a" to "b"))  // reappears -> streak reset

        assertTrue("reappearing link is neither re-added nor removed", recovered.isEmpty)
        assertTrue(Link.of("a", "b") in r.activeLinks())

        // Streak must have reset: it now takes a full holdRounds again to drop.
        r.reconcile(plan())
        r.reconcile(plan())
        assertTrue("streak did not reset", Link.of("a", "b") in r.activeLinks())
    }

    @Test
    fun `reconcile is idempotent once state has settled`() {
        val r = PersistenceReconciler(holdRounds = 2)
        r.reconcile(plan("a" to "b", "c" to "d"))
        val steady = r.reconcile(plan("a" to "b", "c" to "d"))
        assertTrue(steady.isEmpty)
    }

    @Test
    fun `forget drops a link immediately`() {
        val r = PersistenceReconciler(holdRounds = 5)
        r.reconcile(plan("a" to "b"))
        assertTrue(r.forget(Link.of("a", "b")))
        assertTrue(r.activeLinks().isEmpty())
        assertFalse("forgetting an absent link reports no change", r.forget(Link.of("x", "y")))
    }
}
