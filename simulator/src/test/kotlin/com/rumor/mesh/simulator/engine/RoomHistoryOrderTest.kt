package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.RoomActionKind
import com.rumor.mesh.core.model.RoomMembershipPolicy
import com.rumor.mesh.core.model.RoomPostingPolicy
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.protocol.RoomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A node that learns a room late must end with the same roster as one that
 * watched it happen. Field-hit 2026-11-10 on a restarted `:node`: the store
 * backfill offers freshest-first, so the invite and the ban arrived before the
 * ROOM_CREATE, were dropped as "from a non-moderator" and were dedup-marked, so
 * the node kept an empty roster and no bans for good (O213).
 */
class RoomHistoryOrderTest {

    private val member = "a".repeat(64)
    private val banned = "b".repeat(64)

    private class Fixture(val scope: CoroutineScope) {
        val mod = SimNode(0, scope)
        val late = SimNode(1, scope)
        lateinit var roomId: String
        lateinit var history: List<RumorMessage>
    }

    private fun withRoom(block: suspend Fixture.() -> Unit) = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val f = Fixture(scope)
            val e = f.mod.gossipEngine
            val create = e.composeRoomCreate("order", "lab", RoomMembershipPolicy.INVITE, RoomPostingPolicy.MEMBER_ONLY, false)!!
            f.roomId = RoomId.derive(f.mod.userId, "order")
            val far = System.currentTimeMillis() + 60_000
            delay(5)
            val inviteMember = e.composeRoomInvite(f.roomId, member, "", far)!!
            delay(5)
            val inviteBanned = e.composeRoomInvite(f.roomId, banned, "", far)!!
            delay(5)
            val ban = e.composeRoomAction(f.roomId, RoomActionKind.BAN_USER, banned)!!
            f.history = listOf(create, inviteMember, inviteBanned, ban)
            f.block()
        } finally {
            scope.cancel()
        }
    }

    private fun Fixture.deliver(msgs: List<RumorMessage>) =
        late.deliverExchange(PeerExchangeResult(mod.userId, msgs, emptyList(), emptyMap(), 1L))

    private suspend fun Fixture.assertRosterMatchesTheModerators() {
        awaitUntil(what = "the late node learns the member") { late.roomRepo.isMember(roomId, member) }
        awaitUntil(what = "the late node learns the ban") { late.roomRepo.isBanned(roomId, banned) }
        assertFalse("a banned user must not be left as a member", late.roomRepo.isMember(roomId, banned))
    }

    @Test
    fun historyInTheOrderItHappenedBuildsTheRoster() = withRoom {
        // Baseline: the harness and the assertions work when nothing is out of order.
        deliver(history)
        assertRosterMatchesTheModerators()
    }

    @Test
    fun historyDeliveredNewestFirstInOneBatchBuildsTheSameRoster() = withRoom {
        deliver(history.reversed())
        assertRosterMatchesTheModerators()
    }

    @Test
    fun opsThatArriveAnExchangeBeforeTheirCreateAreAppliedWhenItArrives() = withRoom {
        deliver(history.drop(1).reversed())
        delay(200)
        assertFalse("nothing can apply before the room exists", late.roomRepo.isMember(roomId, member))
        deliver(listOf(history.first()))
        assertRosterMatchesTheModerators()
    }

    @Test
    fun aModeratorCannotInviteABannedUserIntoItsOwnRoster() = withRoom {
        // Receivers refuse this invite; composing it used to add the user to the
        // moderator's roster alone (field-hit 2026-11-10: mod saw a member nobody else did).
        val far = System.currentTimeMillis() + 60_000
        assertTrue("control: an ordinary invite composes", mod.gossipEngine.composeRoomInvite(roomId, "c".repeat(64), "", far) != null)
        assertTrue("invite of a banned user must be refused", mod.gossipEngine.composeRoomInvite(roomId, banned, "", far) == null)
        assertFalse("the banned user entered the moderator's own roster", mod.roomRepo.isMember(roomId, banned))
        mod.gossipEngine.composeRoomAction(roomId, RoomActionKind.UNBAN_USER, banned)!!
        assertTrue("after an unban the invite composes", mod.gossipEngine.composeRoomInvite(roomId, banned, "", far) != null)
    }

    @Test
    fun anOpHeldForItsCreateStillNeedsAModeratorSignature() = withRoom {
        // Bypasses compose: the forger's own roster says it is a moderator, so its
        // engine signs the ban. Every other node must still refuse it once the real
        // CREATE shows who the moderators are.
        val forger = SimNode(2, scope)
        forger.roomRepo.addModerator(roomId, forger.userId)
        val forgedBan = forger.gossipEngine.composeRoomAction(roomId, RoomActionKind.BAN_USER, member)!!

        late.deliverExchange(PeerExchangeResult(forger.userId, listOf(forgedBan), emptyList(), emptyMap(), 1L))
        deliver(history.reversed())
        assertRosterMatchesTheModerators()
        delay(300)
        assertFalse("a ban signed by a non-moderator was applied", late.roomRepo.isBanned(roomId, member))
        assertTrue("the forged ban cost a real member their membership", late.roomRepo.isMember(roomId, member))
    }
}
