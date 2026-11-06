package com.rumor.mesh.core.mode

import com.rumor.mesh.core.model.UserMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O205 — the liveness window must track each mode's *inter-contact* interval, not a
 * single fixed 30 s. These are the derived numbers; they are behaviour, so pin them.
 *
 * The ordering assertion is the load-bearing one: MOBILE (duty-cycled, meets peers on
 * the 30 s discovery beat) needs the LONGEST grace or a quiet-but-alive link gets
 * declared dead and false-flooded — the exact battery burn O55 budgets against. FREE
 * (continuous discovery, 5 s gossip) can afford the tightest window, which is what
 * makes its routing most reactive to a real dropout.
 */
class ModeEnvelopeLivenessWindowTest {

    private fun window(mode: UserMode) = ModeEnvelope.forMode(mode).livenessWindowMs

    @Test
    fun `derived window is three inter-contact intervals per mode`() {
        // MOBILE: max(gossip 15s, discovery 30s) = 30s × 3
        assertEquals(90_000L, window(UserMode.MOBILE))
        // STATIC: max(gossip 10s, discovery 15s) = 15s × 3
        assertEquals(45_000L, window(UserMode.STATIC))
        // FREE: discovery is continuous (0), so the gossip beat governs: 5s × 3
        assertEquals(15_000L, window(UserMode.FREE))
    }

    @Test
    fun `a slower duty cycle gets a longer grace`() {
        assertTrue(
            window(UserMode.MOBILE) > window(UserMode.STATIC),
            "MOBILE must not be stricter than STATIC — it meets peers less often",
        )
        assertTrue(
            window(UserMode.STATIC) > window(UserMode.FREE),
            "STATIC must not be stricter than FREE",
        )
    }

    @Test
    fun `continuous discovery does not collapse the window to zero`() {
        // wifiDiscoveryCadenceMs = 0 means "continuous", NOT "contacts are instant".
        // A min() or a sum here would read 0; the max() falls through to the gossip beat.
        val free = ModeEnvelope.forMode(UserMode.FREE)
        assertEquals(0L, free.wifiDiscoveryCadenceMs)
        assertEquals(ModeEnvelope.LIVENESS_WINDOW_ROUNDS * free.gossipRoundIntervalMs, free.livenessWindowMs)
    }
}
