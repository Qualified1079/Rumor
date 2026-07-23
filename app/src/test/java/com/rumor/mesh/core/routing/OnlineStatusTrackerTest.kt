package com.rumor.mesh.core.routing

import com.rumor.mesh.core.model.OnlineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineStatusTrackerTest {

    private val MINUTE_MS = 60_000L
    private val HOUR_MS   = 60 * MINUTE_MS

    @Test
    fun `unknown user is AWAY`() {
        val tracker = OnlineStatusTracker()
        assertEquals(OnlineStatus.AWAY, tracker.statusFor("no-such-user"))
    }

    @Test
    fun `direct contact just recorded is ONLINE`() {
        val tracker = OnlineStatusTracker()
        tracker.recordDirectContact("alice")
        assertEquals(OnlineStatus.ONLINE, tracker.statusFor("alice"))
    }

    @Test
    fun `merge with recent timestamp shows ONLINE`() {
        val tracker = OnlineStatusTracker()
        val twoMinutesAgo = System.currentTimeMillis() - 2 * MINUTE_MS
        tracker.mergeRemoteStatus(mapOf("alice" to twoMinutesAgo))
        assertEquals(OnlineStatus.ONLINE, tracker.statusFor("alice"))
    }

    @Test
    fun `merge with 10-minute-old timestamp shows RECENTLY`() {
        val tracker = OnlineStatusTracker()
        val tenMinutesAgo = System.currentTimeMillis() - 10 * MINUTE_MS
        tracker.mergeRemoteStatus(mapOf("alice" to tenMinutesAgo))
        assertEquals(OnlineStatus.RECENTLY, tracker.statusFor("alice"))
    }

    @Test
    fun `merge with 2-hour-old timestamp shows AWAY`() {
        val tracker = OnlineStatusTracker()
        val twoHoursAgo = System.currentTimeMillis() - 2 * HOUR_MS
        tracker.mergeRemoteStatus(mapOf("alice" to twoHoursAgo))
        assertEquals(OnlineStatus.AWAY, tracker.statusFor("alice"))
    }

    @Test
    fun `merge takes the fresher of two timestamps`() {
        val tracker = OnlineStatusTracker()
        val older = System.currentTimeMillis() - 10 * MINUTE_MS   // RECENTLY
        val newer = System.currentTimeMillis() - 1 * MINUTE_MS    // ONLINE

        tracker.mergeRemoteStatus(mapOf("alice" to older))
        assertEquals(OnlineStatus.RECENTLY, tracker.statusFor("alice"))

        // Fresher update should upgrade to ONLINE.
        tracker.mergeRemoteStatus(mapOf("alice" to newer))
        assertEquals(OnlineStatus.ONLINE, tracker.statusFor("alice"))
    }

    @Test
    fun `merge keeps fresher timestamp when stale update arrives later`() {
        val tracker = OnlineStatusTracker()
        val fresh = System.currentTimeMillis() - 1 * MINUTE_MS    // ONLINE
        val stale = System.currentTimeMillis() - 10 * MINUTE_MS   // RECENTLY

        tracker.mergeRemoteStatus(mapOf("alice" to fresh))
        tracker.mergeRemoteStatus(mapOf("alice" to stale))  // stale — should be ignored

        assertEquals(OnlineStatus.ONLINE, tracker.statusFor("alice"))
    }

    @Test
    fun `direct contact overrides stale merged status`() {
        val tracker = OnlineStatusTracker()
        val tenMinutesAgo = System.currentTimeMillis() - 10 * MINUTE_MS
        tracker.mergeRemoteStatus(mapOf("alice" to tenMinutesAgo))
        assertEquals(OnlineStatus.RECENTLY, tracker.statusFor("alice"))

        tracker.recordDirectContact("alice")
        assertEquals(OnlineStatus.ONLINE, tracker.statusFor("alice"))
    }

    @Test
    fun `currentSnapshot excludes entries older than 30 minutes`() {
        val tracker = OnlineStatusTracker()
        val fresh = System.currentTimeMillis() - 5 * MINUTE_MS    // within 30 min
        val stale = System.currentTimeMillis() - 45 * MINUTE_MS   // outside 30 min

        tracker.mergeRemoteStatus(mapOf("alice" to fresh, "bob" to stale))

        val snapshot = tracker.currentSnapshot()
        assertTrue("alice should be in snapshot", "alice" in snapshot)
        assertFalse("bob should be excluded (stale)", "bob" in snapshot)
    }

    @Test
    fun `currentSnapshot includes just-recorded direct contact`() {
        val tracker = OnlineStatusTracker()
        tracker.recordDirectContact("carol")
        val snapshot = tracker.currentSnapshot()
        assertTrue("carol" in snapshot)
    }

    @Test
    fun `statuses flow emits updated map after recordDirectContact`() {
        val tracker = OnlineStatusTracker()
        tracker.recordDirectContact("dave")
        val presence = tracker.statuses.value["dave"]
        assertNotNull(presence)
        assertEquals(OnlineStatus.ONLINE, presence!!.status)
        assertEquals("dave", presence.userId)
    }

    @Test
    fun `merge clamps far-future timestamp to skew budget`() {
        val tracker = OnlineStatusTracker()
        val farFuture = System.currentTimeMillis() + 365 * 24 * HOUR_MS
        tracker.mergeRemoteStatus(mapOf("mallory" to farFuture))

        val stored = tracker.currentSnapshot()["mallory"]
        assertNotNull(stored)
        // 2-min skew budget + generous test slack; the poison must not survive as-is.
        assertTrue(
            "stored ts must be clamped near now, was ${stored!! - System.currentTimeMillis()}ms ahead",
            stored <= System.currentTimeMillis() + 3 * MINUTE_MS
        )
    }

    @Test
    fun `clamped future entry still counts as ONLINE now`() {
        val tracker = OnlineStatusTracker()
        val slightlyAhead = System.currentTimeMillis() + 30_000 // honest clock skew
        tracker.mergeRemoteStatus(mapOf("alice" to slightlyAhead))
        assertEquals(OnlineStatus.ONLINE, tracker.statusFor("alice"))
    }

    @Test
    fun `pruneStale removes entries past retention and keeps fresh ones`() {
        val tracker = OnlineStatusTracker()
        val ancient = System.currentTimeMillis() - 25 * HOUR_MS
        tracker.mergeRemoteStatus(mapOf("old" to ancient))
        tracker.recordDirectContact("fresh")

        tracker.pruneStale()

        val snapshotAll = tracker.statuses.value
        assertNull(snapshotAll["old"])
        assertNotNull(snapshotAll["fresh"])
    }

    @Test
    fun `multiple users tracked independently`() {
        val tracker = OnlineStatusTracker()
        tracker.recordDirectContact("alice")
        val tenMinutesAgo = System.currentTimeMillis() - 10 * MINUTE_MS
        tracker.mergeRemoteStatus(mapOf("bob" to tenMinutesAgo))

        assertEquals(OnlineStatus.ONLINE,   tracker.statusFor("alice"))
        assertEquals(OnlineStatus.RECENTLY, tracker.statusFor("bob"))
        assertEquals(OnlineStatus.AWAY,     tracker.statusFor("carol"))
    }
}
