package com.rumor.mesh.core.mode

import com.rumor.mesh.core.model.UserMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModeEnvelopeTest {

    private val mobile = ModeEnvelope.forMode(UserMode.MOBILE)
    private val static = ModeEnvelope.forMode(UserMode.STATIC)
    private val free = ModeEnvelope.forMode(UserMode.FREE)

    @Test
    fun `mobile reproduces the pre-O62 defaults`() {
        // These pin the old StaticMode-off behaviour: no boosts, LOW_POWER scan,
        // mode-change-only presence. A change here is a behaviour change.
        assertEquals(1, mobile.schedulerBoost)
        assertEquals(1, mobile.storageCacheBoost)
        assertEquals(1, mobile.routingWeightMultiplier)
        assertEquals(ScanPower.LOW_POWER, mobile.scanPower)
        assertEquals(null, mobile.presenceBeaconIntervalMs)
    }

    @Test
    fun `static reproduces the pre-O62 STATIC_BOOST constants`() {
        assertEquals(3, static.schedulerBoost)       // old Scheduler.STATIC_BOOST
        assertEquals(4, static.storageCacheBoost)     // old MessageStore.STATIC_CACHE_BOOST
        assertEquals(ScanPower.BALANCED, static.scanPower)
    }

    @Test
    fun `effort is monotonic non-decreasing MOBILE le STATIC le FREE`() {
        val modes = listOf(mobile, static, free)
        for (dim in listOf<(ModeEnvelope) -> Int>(
            { it.schedulerBoost }, { it.storageCacheBoost }, { it.routingWeightMultiplier },
        )) {
            assertTrue(dim(mobile) <= dim(static), "MOBILE ≤ STATIC violated")
            assertTrue(dim(static) <= dim(free), "STATIC ≤ FREE violated")
        }
        // Scan power ordinal ascends too.
        assertTrue(mobile.scanPower.ordinal <= static.scanPower.ordinal)
        assertTrue(static.scanPower.ordinal <= free.scanPower.ordinal)
        // Discovery cadence tightens (smaller = more frequent); FREE is continuous (0).
        assertTrue(mobile.wifiDiscoveryCadenceMs >= static.wifiDiscoveryCadenceMs)
        assertEquals(0L, free.wifiDiscoveryCadenceMs)
        // Gossip rounds get more frequent.
        assertTrue(mobile.gossipRoundIntervalMs >= static.gossipRoundIntervalMs)
        assertTrue(static.gossipRoundIntervalMs >= free.gossipRoundIntervalMs)
    }

    @Test
    fun `only mobile suppresses periodic presence beacons`() {
        assertEquals(null, mobile.presenceBeaconIntervalMs)
        assertTrue((static.presenceBeaconIntervalMs ?: 0) > 0)
        assertTrue((free.presenceBeaconIntervalMs ?: 0) > 0)
        // FREE beacons at least as often as STATIC.
        assertTrue(free.presenceBeaconIntervalMs!! <= static.presenceBeaconIntervalMs!!)
    }

    @Test
    fun `envelope on ModeState reflects the current mode`() {
        assertEquals(free, FixedModeState(UserMode.FREE).envelope)
        assertEquals(mobile, FixedModeState().envelope)  // MOBILE default
    }
}
