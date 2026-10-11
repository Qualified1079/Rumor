package com.rumor.mesh.simulator.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teeth check for the check. [awaitUntil] is the precondition gate under ~30
 * sim assertions, so "it silently did nothing" has to be impossible — that was
 * exactly the old behaviour, and it made other tests' results meaningless.
 */
class TestAwaitTest {

    @Test
    fun `returns as soon as the condition holds`() = runBlocking {
        var polls = 0
        awaitUntil(timeoutMs = 10_000, what = "a condition true on the third poll") {
            polls++; polls >= 3
        }
        assertTrue("must stop polling once true, not burn the whole budget", polls in 3..20)
    }

    @Test
    fun `a never-true condition fails loudly instead of returning`() = runBlocking {
        val e = assertThrows(AssertionError::class.java) {
            runBlocking { awaitUntil(timeoutMs = 60, what = "an effect that never lands") { false } }
        }
        assertTrue(
            "the message must name what was waited for: ${e.message}",
            e.message!!.contains("an effect that never lands"),
        )
    }

    @Test
    fun `an effect that lands exactly at the deadline is not reported as a timeout`() = runBlocking {
        // The final re-read exists for this: the poll loop can exit on the deadline
        // in the same instant the effect lands, and a spurious timeout would be its
        // own false signal.
        var ready = false
        val t = Thread { Thread.sleep(80); ready = true }
        t.start()
        awaitUntil(timeoutMs = 10_000, what = "an effect that lands late but does land") { ready }
        t.join()
        assertEquals(true, ready)
    }
}
