package com.rumor.mesh.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFilterTest {

    @Test
    fun `new id returns true`() {
        val f = DuplicateFilter()
        assertTrue(f.recordAndCheck("a"))
    }

    @Test
    fun `same id seen twice returns false on second call`() {
        val f = DuplicateFilter()
        assertTrue(f.recordAndCheck("a"))
        assertFalse(f.recordAndCheck("a"))
    }

    @Test
    fun `distinct ids are all new`() {
        val f = DuplicateFilter()
        val ids = (1..100).map { "id-$it" }
        ids.forEach { assertTrue(f.recordAndCheck(it)) }
        assertEquals(100, f.size())
    }

    @Test
    fun `knownIds contains recorded ids`() {
        val f = DuplicateFilter()
        f.recordAndCheck("x")
        f.recordAndCheck("y")
        assertTrue("x" in f.knownIds())
        assertTrue("y" in f.knownIds())
    }

    @Test
    fun `knownIds does not contain unseen id`() {
        val f = DuplicateFilter()
        f.recordAndCheck("seen")
        assertFalse("unseen" in f.knownIds())
    }

    @Test
    fun `concurrent access does not throw`() {
        val f = DuplicateFilter()
        val threads = (1..10).map { t ->
            Thread {
                repeat(200) { i -> f.recordAndCheck("t$t-i$i") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // Just verify it completed without exception and has entries.
        assertTrue(f.size() > 0)
    }
}
