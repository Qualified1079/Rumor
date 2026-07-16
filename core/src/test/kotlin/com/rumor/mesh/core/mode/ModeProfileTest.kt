package com.rumor.mesh.core.mode

import com.rumor.mesh.core.model.UserMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModeProfileTest {

    private val midDayMon = DeviceState(
        batteryPercent = 50,
        charging = Charging.Discharging,
        screen = Screen.ON,
        network = Network.WIFI,
        timeOfDay = LocalTime(12, 0),
        weekday = Weekday.MON,
    )

    @Test
    fun `empty profile returns defaultMode`() {
        val profile = ModeProfile(rules = emptyList(), defaultMode = UserMode.STATIC)
        assertEquals(UserMode.STATIC, profile.evaluate(midDayMon))
    }

    @Test
    fun `first matching rule wins`() {
        val profile = ModeProfile(
            rules = listOf(
                Rule(Condition.BatteryAbove(40), UserMode.FREE, label = "high battery"),
                Rule(Condition.Always, UserMode.MOBILE, label = "fallback"),
            ),
        )
        assertEquals(UserMode.FREE, profile.evaluate(midDayMon))
    }

    @Test
    fun `fallback rule wins when nothing earlier matches`() {
        val profile = ModeProfile(
            rules = listOf(
                Rule(Condition.BatteryAbove(99), UserMode.FREE),
                Rule(Condition.Always, UserMode.MOBILE),
            ),
        )
        assertEquals(UserMode.MOBILE, profile.evaluate(midDayMon))
    }

    @Test
    fun `AND-combined conditions all must match`() {
        val condition = Condition.And(listOf(
            Condition.ChargingIs(Charging.Charging),
            Condition.ScreenIs(Screen.OFF),
        ))
        // midDayMon: Discharging + Screen.ON → fails
        assertFalse(condition.matches(midDayMon))
        val pluggedSleeping = midDayMon.copy(charging = Charging.Charging, screen = Screen.OFF)
        assertTrue(condition.matches(pluggedSleeping))
    }

    @Test
    fun `time of day wraps midnight`() {
        val overnight = Condition.TimeOfDay(LocalTime(22, 0), LocalTime(6, 0))
        assertTrue(overnight.matches(midDayMon.copy(timeOfDay = LocalTime(23, 30))))
        assertTrue(overnight.matches(midDayMon.copy(timeOfDay = LocalTime(2, 0))))
        assertFalse(overnight.matches(midDayMon.copy(timeOfDay = LocalTime(12, 0))))
        assertFalse(overnight.matches(midDayMon.copy(timeOfDay = LocalTime(6, 0))))  // exclusive end
        assertTrue(overnight.matches(midDayMon.copy(timeOfDay = LocalTime(22, 0))))  // inclusive start
    }

    @Test
    fun `time of day same-day range`() {
        val workHours = Condition.TimeOfDay(LocalTime(9, 0), LocalTime(17, 0))
        assertTrue(workHours.matches(midDayMon.copy(timeOfDay = LocalTime(12, 0))))
        assertFalse(workHours.matches(midDayMon.copy(timeOfDay = LocalTime(8, 59))))
        assertFalse(workHours.matches(midDayMon.copy(timeOfDay = LocalTime(17, 0))))  // exclusive end
    }

    @Test
    fun `weekday membership`() {
        val weekend = Condition.WeekdayIn(setOf(Weekday.SAT, Weekday.SUN))
        assertFalse(weekend.matches(midDayMon))
        assertTrue(weekend.matches(midDayMon.copy(weekday = Weekday.SAT)))
        assertTrue(weekend.matches(midDayMon.copy(weekday = Weekday.SUN)))
    }

    @Test
    fun `realistic profile - free when plugged-in late-night`() {
        // Example user rule: "if charging AND time 22:00-06:00, Free mode"
        val profile = ModeProfile(
            rules = listOf(
                Rule(
                    condition = Condition.And(listOf(
                        Condition.ChargingIs(Charging.Charging),
                        Condition.TimeOfDay(LocalTime(22, 0), LocalTime(6, 0)),
                    )),
                    targetMode = UserMode.FREE,
                    label = "overnight on charger",
                ),
                Rule(
                    condition = Condition.BatteryBelow(30),
                    targetMode = UserMode.MOBILE,
                    label = "save battery below 30%",
                ),
                Rule(Condition.Always, UserMode.STATIC),
            ),
            defaultMode = UserMode.MOBILE,
        )

        // Daytime, discharging, mid battery → fallthrough to STATIC
        assertEquals(UserMode.STATIC, profile.evaluate(midDayMon))

        // Plugged in at 2am → Free
        val pluggedLate = midDayMon.copy(
            charging = Charging.Charging,
            timeOfDay = LocalTime(2, 0),
        )
        assertEquals(UserMode.FREE, profile.evaluate(pluggedLate))

        // Low battery, daytime → Mobile (second rule wins)
        val lowBattery = midDayMon.copy(batteryPercent = 15)
        assertEquals(UserMode.MOBILE, profile.evaluate(lowBattery))
    }

    @Test
    fun `LocalTime input validation`() {
        try {
            LocalTime(24, 0); error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
        try {
            LocalTime(0, 60); error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
        try {
            LocalTime(-1, 0); error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
    }
}
