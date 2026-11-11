package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.RoomMembershipPolicy
import com.rumor.mesh.core.model.RoomPostingPolicy
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.RoomId
import com.rumor.mesh.core.protocol.RoomRoutingTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a node composes must reach every one of its peers, not only the first to
 * open a session. Field-hit 2026-11-10 on a three-node `:node` line A—B—C: B's
 * room post reached C and never reached A, because room composes went to the
 * destructive scheduler only and never into B's own store, so there was nothing
 * for the store backfill to offer the second peer. Sibling of
 * [RoomLifecycleRelayTest], which covers the relayed copy.
 */
class OwnRoomMessagesReachEveryPeerTest {

    private fun twoPeerOffer(compose: suspend (SimNode) -> RumorMessage): Pair<Boolean, Boolean> = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val a = SimNode(0, scope); val b = SimNode(1, scope); val c = SimNode(2, scope)
            val msg = compose(b)
            val toFirst = mutableListOf<String>()
            awaitUntil(what = "the composed message drains toward the first peer") {
                toFirst += b.takeOutbound(a.userId).map { it.id }; msg.id in toFirst
            }
            // Settle: the own-store write is launched, not awaited, by compose.
            var offeredToSecond = false
            repeat(20) {
                if (!offeredToSecond) {
                    offeredToSecond = b.takeOutbound(c.userId).any { it.id == msg.id }
                    if (!offeredToSecond) kotlinx.coroutines.delay(50)
                }
            }
            true to offeredToSecond
        } finally {
            scope.cancel()
        }
    }

    private suspend fun createRoom(n: SimNode, nonce: String): String {
        n.gossipEngine.composeRoomCreate(nonce, "lab", RoomMembershipPolicy.INVITE, RoomPostingPolicy.MEMBER_ONLY, false)!!
        return RoomId.derive(n.userId, nonce)
    }

    @Test
    fun aBroadcastIsOfferedToTheSecondPeer() {
        // Discrimination control: the harness can see a second offer when one exists.
        val (_, second) = twoPeerOffer { it.gossipEngine.composeBroadcast("hello")!! }
        assertTrue("control failed: a broadcast was not offered to the second peer", second)
    }

    @Test
    fun aRoomPostIsOfferedToTheSecondPeer() {
        val (_, second) = twoPeerOffer { n ->
            val roomId = createRoom(n, "post-test")
            n.gossipEngine.composeRoomMessage(RoomRoutingTag.openRoomTag(roomId), "to everyone")!!
        }
        assertTrue("own room post was offered to the first peer only", second)
    }

    @Test
    fun aRoomCreateIsOfferedToTheSecondPeer() {
        val (_, second) = twoPeerOffer { n ->
            n.gossipEngine.composeRoomCreate("create-test", "lab", RoomMembershipPolicy.INVITE, RoomPostingPolicy.MEMBER_ONLY, false)!!
        }
        assertTrue("own ROOM_CREATE was offered to the first peer only", second)
    }

    @Test
    fun aRoomInviteIsOfferedToTheSecondPeer() {
        val (_, second) = twoPeerOffer { n ->
            val roomId = createRoom(n, "invite-test")
            n.gossipEngine.composeRoomInvite(roomId, "f".repeat(64), "", System.currentTimeMillis() + 60_000)!!
        }
        assertTrue("own ROOM_INVITE was offered to the first peer only", second)
    }
}
