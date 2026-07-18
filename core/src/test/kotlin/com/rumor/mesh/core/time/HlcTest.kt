package com.rumor.mesh.core.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HlcTest {

    @Test
    fun `tick advances with the wall clock`() {
        var now = 1_000L
        val clock = HlcClock { now }
        assertEquals(HlcTimestamp(1_000, 0), clock.tick())
        now = 2_000
        assertEquals(HlcTimestamp(2_000, 0), clock.tick())
    }

    @Test
    fun `tick on a stalled wall clock increments the counter`() {
        val clock = HlcClock { 1_000L }
        clock.tick()
        assertEquals(HlcTimestamp(1_000, 1), clock.tick())
        assertEquals(HlcTimestamp(1_000, 2), clock.tick())
    }

    @Test
    fun `update adopts a remote clock far ahead of local`() {
        val clock = HlcClock { 1_000L }
        clock.tick()
        val advanced = clock.update(HlcTimestamp(5_000_000, 3))
        assertEquals(HlcTimestamp(5_000_000, 4), advanced)
        // Local events keep sorting after the adopted remote point.
        assertTrue(clock.tick() > advanced)
    }

    @Test
    fun `a reply always sorts after its cause even with a slow replier clock`() {
        // Sender's clock is sane; replier is 64 days behind (the field case).
        val sender = HlcClock { 1_700_000_000_000 }
        val replier = HlcClock { 1_700_000_000_000 - 64L * 24 * 3600 * 1000 }
        val cause = sender.tick()
        replier.update(cause)
        val reply = replier.tick()
        assertTrue(reply > cause, "reply $reply must sort after cause $cause")
    }

    @Test
    fun `update against equal walls takes max counter plus one`() {
        val clock = HlcClock { 1_000L }
        clock.tick()                                     // (1000, 0)
        val r = clock.update(HlcTimestamp(1_000, 7))
        assertEquals(HlcTimestamp(1_000, 8), r)
    }

    @Test
    fun `comparability is lexicographic`() {
        assertTrue(HlcTimestamp(1, 5) < HlcTimestamp(2, 0))
        assertTrue(HlcTimestamp(2, 0) < HlcTimestamp(2, 1))
    }
}
