package com.rumor.mesh.core.routing

import com.rumor.mesh.core.Clock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** O124 — responder-side cooldown for solicited presence replies. */
class PresenceReplyGateTest {

    private var now = 0L
    private val gate = PresenceReplyGate(clock = Clock { now }, cooldownMs = 120_000L)

    @Test
    fun `unknown peer gets one reply`() {
        assertTrue(gate.shouldReply("a", peerWasFresh = false))
    }

    @Test
    fun `repeat probes inside cooldown are ignored regardless of count`() {
        assertTrue(gate.shouldReply("a", peerWasFresh = false))
        repeat(100) {
            now += 1_000
            assertFalse("probe spam must not force replies", gate.shouldReply("a", peerWasFresh = false))
        }
    }

    @Test
    fun `cooldown expiry re-arms the reply`() {
        assertTrue(gate.shouldReply("a", peerWasFresh = false))
        now += 120_000
        assertTrue(gate.shouldReply("a", peerWasFresh = false))
    }

    @Test
    fun `fresh peer never triggers and does not consume the cooldown`() {
        assertFalse(gate.shouldReply("a", peerWasFresh = true))
        // The fresh-path must not have stamped a reply time.
        assertTrue(gate.shouldReply("a", peerWasFresh = false))
    }

    @Test
    fun `cooldowns are per peer`() {
        assertTrue(gate.shouldReply("a", peerWasFresh = false))
        assertTrue(gate.shouldReply("b", peerWasFresh = false))
        assertFalse(gate.shouldReply("a", peerWasFresh = false))
    }
}
