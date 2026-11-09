package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.RoomMembershipPolicy
import com.rumor.mesh.core.model.RoomPostingPolicy
import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.protocol.RoomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A room's lifecycle must cross a relay that has more than one peer. Field-hit
 * 2026-11-08 on a three-node `:node` line A—B—C: B's session with A drained the
 * relayed ROOM_CREATE out of B's destructive scheduler (A already had it), and
 * B's store backfill only re-offered BROADCAST/DIRECT, so C never learned the
 * room existed — while a plain broadcast crossed the same line in seconds.
 */
class RoomLifecycleRelayTest {

    private fun deliver(from: SimNode, to: SimNode, msgs: List<com.rumor.mesh.core.model.RumorMessage>) =
        to.deliverExchange(PeerExchangeResult(from.userId, msgs, emptyList(), emptyMap(), 1L))

    @Test
    fun roomCreateReachesThePeerBehindARelayWhoseSchedulerAnotherPeerDrained() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val a = SimNode(0, scope); val b = SimNode(1, scope); val c = SimNode(2, scope)
            val nonce = "relay-test"
            val create = a.gossipEngine.composeRoomCreate(
                nonce, "lab", RoomMembershipPolicy.INVITE, RoomPostingPolicy.MEMBER_ONLY, false,
            )!!
            val roomId = RoomId.derive(a.userId, nonce)

            deliver(a, b, a.takeOutbound(b.userId))
            awaitUntil(what = "B applies the CREATE") { b.roomRepo.get(roomId) != null }

            // B's session with A comes first and takes the relayed copy out of the
            // scheduler. Proving the drain happened is what makes the C assertion
            // below about backfill rather than about a copy that was still queued.
            val toA = mutableListOf<String>()
            awaitUntil(what = "B's relayed CREATE drains toward A") {
                toA += b.takeOutbound(a.userId).map { it.id }; create.id in toA
            }

            val toC = b.takeOutbound(c.userId)
            assertTrue("B must still offer the CREATE to C from its store", toC.any { it.id == create.id })
            deliver(b, c, toC)
            awaitUntil(what = "C applies the CREATE") { c.roomRepo.get(roomId) != null }
            assertNotNull(c.roomRepo.get(roomId))
        } finally {
            scope.cancel()
        }
    }
}
