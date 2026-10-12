package com.rumor.mesh.core.model

import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.wire.WireJson
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Presence must not depend on anyone's wall clock. Field 2026-10-11: one phone
 * ran ~3 months behind, another ~7 minutes ahead, and a peer that had left read
 * ONLINE indefinitely. The receive-side cases decode raw peer JSON — a hostile
 * or old client never calls our `withAges` sender.
 */
class OnlineStatusSkewTest {

    private val minute = 60_000L
    private val localNow = 1_800_000_000_000L

    private fun fromPeer(json: String) =
        WireJson.decodeFromString(GossipPacket.serializer(), json) as GossipPacket.OnlineStatus

    @Test
    fun `ages win over the sender's skewed timestamps`() {
        // Sender's clock is months off; its timestamp is garbage, its age is not.
        val p = fromPeer(
            """{"type":"online_status","recentUsers":{"moto":12345},"_ext":{"ages":{"moto":${10 * minute}}}}""",
        )
        assertEquals(localNow - 10 * minute, p.recentUsersOnLocalClock(localNow)["moto"])
    }

    @Test
    fun `a negative age can't place a sighting in our future`() {
        val p = fromPeer("""{"type":"online_status","recentUsers":{},"_ext":{"ages":{"moto":-999999}}}""")
        assertEquals(localNow, p.recentUsersOnLocalClock(localNow)["moto"])
    }

    @Test
    fun `a sender without ages is taken verbatim`() {
        val p = fromPeer("""{"type":"online_status","recentUsers":{"moto":12345}}""")
        assertEquals(12345L, p.recentUsersOnLocalClock(localNow)["moto"])
    }

    @Test
    fun `our ages survive the wire under the reserved key`() {
        val json = WireJson.encodeToString(
            GossipPacket.serializer(),
            GossipPacket.OnlineStatus.withAges(mapOf("moto" to localNow - minute), localNow),
        )
        assertEquals(true, "\"ages\":{\"moto\":$minute}" in json, json)
        assertEquals(localNow - minute, fromPeer(json).recentUsersOnLocalClock(localNow)["moto"])
    }

    @Test
    fun `a wall clock jumping backwards does not change a sighting's age or status`() {
        var wall = localNow
        var mono = 5_000_000L
        val tracker = OnlineStatusTracker(wallClock = { wall }, monotonicMs = { mono })
        tracker.recordDirectContact("moto")

        mono += 2 * minute
        wall -= 90L * 24 * 60 * minute   // user sets the clock back ~3 months

        assertEquals(OnlineStatus.RECENTLY, tracker.statusFor("moto"))
        // What we tell peers is still "seen 2 minutes ago", whatever our wall says.
        val sent = GossipPacket.OnlineStatus.withAges(tracker.currentSnapshot(), wall)
        assertEquals(localNow - 2 * minute, sent.recentUsersOnLocalClock(localNow)["moto"])
    }
}
