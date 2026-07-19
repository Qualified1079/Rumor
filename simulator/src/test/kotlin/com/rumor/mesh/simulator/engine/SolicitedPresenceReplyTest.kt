package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.UserMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * O124 — a pulse from an unknown peer solicits exactly one reply pulse; the
 * responder-side cooldown (and the peer turning fresh in the mesh view) stops
 * repeat probes from forcing more.
 */
class SolicitedPresenceReplyTest {

    @Test
    fun `unknown peer's pulse solicits exactly one reply`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)

        var replies = 0
        b.gossipEngine.presencePulse = {
            replies++
            b.gossipEngine.composeSelfPresence(UserMode.MOBILE)
        }

        // First pulse: A is unknown to B → one reply. The dedup id is recorded
        // before the handler's reply fires — await the reply itself, not the id.
        val beacon1 = a.gossipEngine.composeSelfPresence(UserMode.FREE)!!
        a.flushSchedulerToRepo()
        SimTransport(a, b).exchange(kotlin.random.Random(1))
        awaitUntil { replies == 1 }
        assertEquals("first pulse from an unknown peer must solicit one reply", 1, replies)

        // Second pulse right after: A is now fresh in B's view AND inside the
        // cooldown → no further reply. Absence needs a settle window after the
        // id lands (the would-be reply fires right after the id record).
        val beacon2 = a.gossipEngine.composeSelfPresence(UserMode.FREE)!!
        a.flushSchedulerToRepo()
        SimTransport(a, b).exchange(kotlin.random.Random(2))
        awaitUntil { beacon2.id in b.gossipEngine.knownMessageIds() }
        kotlinx.coroutines.delay(300)
        assertEquals("repeat probe must not force another reply", 1, replies)
    }

    @Test
    fun `no host wiring means no replies`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)

        val beacon = a.gossipEngine.composeSelfPresence(UserMode.FREE)!!
        a.flushSchedulerToRepo()
        SimTransport(a, b).exchange(kotlin.random.Random(1))
        awaitUntil { beacon.id in b.gossipEngine.knownMessageIds() }
        // Nothing to assert beyond absence of a crash: presencePulse defaults
        // to null and the handler must tolerate it.
    }
}
