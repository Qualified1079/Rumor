package com.rumor.mesh.core.mode

import com.rumor.mesh.core.model.UserMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoModeControllerTest {

    private fun state(
        battery: Int = 50,
        charging: Charging = Charging.Discharging,
        screen: Screen = Screen.ON,
    ) = DeviceState(
        batteryPercent = battery,
        charging = charging,
        screen = screen,
        network = Network.OFFLINE,
        timeOfDay = LocalTime(12, 0),
        weekday = Weekday.MON,
    )

    @Test
    fun `starter profile - unplugged is MOBILE, no change when already there`() {
        val c = AutoModeController()
        assertNull(c.evaluate(state(), UserMode.MOBILE))
    }

    @Test
    fun `starter profile - plug fires STATIC immediately`() {
        val c = AutoModeController()
        assertEquals(UserMode.STATIC, c.evaluate(state(charging = Charging.Charging), UserMode.MOBILE))
    }

    @Test
    fun `starter profile - plugged and screen off fires FREE, screen on demotes to STATIC`() {
        val c = AutoModeController()
        assertEquals(UserMode.FREE, c.evaluate(state(charging = Charging.Charging, screen = Screen.OFF), UserMode.MOBILE))
        assertEquals(UserMode.STATIC, c.evaluate(state(charging = Charging.Charging, screen = Screen.ON), UserMode.FREE))
    }

    @Test
    fun `starter profile - unplug demotes to MOBILE even within the battery gap`() {
        val c = AutoModeController()
        assertEquals(UserMode.STATIC, c.evaluate(state(battery = 50, charging = Charging.Charging), UserMode.MOBILE))
        assertEquals(UserMode.MOBILE, c.evaluate(state(battery = 51, charging = Charging.Discharging), UserMode.STATIC))
    }

    @Test
    fun `plugged-not-charging counts as plugged`() {
        val c = AutoModeController()
        assertEquals(UserMode.STATIC, c.evaluate(state(charging = Charging.PluggedNotCharging), UserMode.MOBILE))
    }

    private val batteryProfile = ModeProfile(
        rules = listOf(Rule(Condition.BatteryAbove(40), UserMode.STATIC, label = "high battery")),
        defaultMode = UserMode.MOBILE,
    )

    @Test
    fun `battery-driven change suppressed inside the hysteresis gap`() {
        val c = AutoModeController(batteryProfile)
        // Anchor set at first evaluation (battery 42, STATIC already current).
        assertNull(c.evaluate(state(battery = 42), UserMode.STATIC))
        // Dips just below the threshold, but only 3 points from the anchor.
        assertNull(c.evaluate(state(battery = 39), UserMode.STATIC))
        // 5 points from the anchor — transition applies.
        assertEquals(UserMode.MOBILE, c.evaluate(state(battery = 37), UserMode.STATIC))
    }

    @Test
    fun `hysteresis gap applies symmetrically after a transition`() {
        val c = AutoModeController(batteryProfile)
        assertNull(c.evaluate(state(battery = 42), UserMode.STATIC))
        assertEquals(UserMode.MOBILE, c.evaluate(state(battery = 37), UserMode.STATIC))
        // Charging blip back over the threshold: 4 points from the new anchor.
        assertNull(c.evaluate(state(battery = 41), UserMode.MOBILE))
        // 5 points out — promotes again.
        assertEquals(UserMode.STATIC, c.evaluate(state(battery = 42), UserMode.MOBILE))
    }
}
