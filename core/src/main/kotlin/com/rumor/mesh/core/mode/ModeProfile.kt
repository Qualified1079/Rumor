package com.rumor.mesh.core.mode

import com.rumor.mesh.core.model.UserMode

/**
 * O80 / O57 — user-customisable mode-transition rules.
 *
 * The user defines an ordered list of [Rule]s; the first one whose
 * [Rule.condition] matches the current [DeviceState] wins, and the device
 * runs in [Rule.targetMode] until the next state change re-evaluates.
 *
 * Pure data model + matcher — no platform wiring. The Android orchestrator
 * (BatteryManager listeners, screen on/off receivers, plug events, time-of-
 * day check) is responsible for assembling a fresh [DeviceState] when any
 * underlying signal changes and calling [ModeProfile.evaluate]. iOS / desktop
 * orchestrators do the same with their own platform APIs.
 *
 * Empty rule list = "stay in [defaultMode] until manually changed." No
 * shipped default profiles (per CLAUDE.md O80); the user composes whatever
 * fits their device.
 */
data class ModeProfile(
    val rules: List<Rule>,
    val defaultMode: UserMode = UserMode.MOBILE,
) {
    /**
     * Returns the target mode for [state], or [defaultMode] if no rule
     * matches. Stateless and pure — caller decides whether the result
     * differs from the current mode and whether to fire G12 mode-change
     * pulses.
     */
    fun evaluate(state: DeviceState): UserMode {
        for (rule in rules) if (rule.condition.matches(state)) return rule.targetMode
        return defaultMode
    }
}

/**
 * One mode-transition rule. Conditions compose via [Condition.And]; first
 * matching rule wins (so rule ORDER is meaningful and `rules.reorder` is a
 * UX action). Sub-overrides ([scanIntervalMs] etc.) refine ModeProfile per-
 * mode defaults when the rule is active — caller applies them on top of the
 * mode's standard envelope.
 */
data class Rule(
    val condition: Condition,
    val targetMode: UserMode,
    /** Optional per-rule scan-interval override; null = use mode's default. */
    val scanIntervalMs: Long? = null,
    /** Optional per-rule gossip-cadence override; null = use mode's default. */
    val gossipCadenceMs: Long? = null,
    /** Optional per-rule max gossip-session length; null = use mode's default. */
    val maxSessionMs: Long? = null,
    /** User-readable label for the UI list editor. Not load-bearing. */
    val label: String? = null,
)

/**
 * Sealed conditions composable into rules. AND-only by design (no OR / NOT)
 * — keeps the rule editor simple ("all of these must hold") and lets the user
 * express OR as multiple separate rules. NOT is rarely needed; if it is,
 * the inversion lives in the specific Condition (e.g. [Charging.Discharging]
 * is the NOT of [Charging.Charging]).
 *
 * Hysteresis (e.g. 5% gap on battery thresholds) is the caller's job, not
 * the condition's. The condition only answers "does this match RIGHT NOW?"
 */
sealed class Condition {
    abstract fun matches(state: DeviceState): Boolean

    /** Always true; used as a catch-all final rule. */
    data object Always : Condition() {
        override fun matches(state: DeviceState) = true
    }

    /** AND-combine; matches iff every inner condition matches. */
    data class And(val parts: List<Condition>) : Condition() {
        override fun matches(state: DeviceState) = parts.all { it.matches(state) }
    }

    /** Battery percentage strictly greater than [threshold] (0..100). */
    data class BatteryAbove(val threshold: Int) : Condition() {
        override fun matches(state: DeviceState) = state.batteryPercent > threshold
    }

    /** Battery percentage strictly less than [threshold] (0..100). */
    data class BatteryBelow(val threshold: Int) : Condition() {
        override fun matches(state: DeviceState) = state.batteryPercent < threshold
    }

    data class ChargingIs(val expected: Charging) : Condition() {
        override fun matches(state: DeviceState) = state.charging == expected
    }

    data class ScreenIs(val expected: Screen) : Condition() {
        override fun matches(state: DeviceState) = state.screen == expected
    }

    data class NetworkIs(val expected: Network) : Condition() {
        override fun matches(state: DeviceState) = state.network == expected
    }

    /** Local time-of-day in [from, to). Inclusive start, exclusive end. */
    data class TimeOfDay(val from: LocalTime, val to: LocalTime) : Condition() {
        override fun matches(state: DeviceState): Boolean {
            val now = state.timeOfDay
            return if (from <= to) now >= from && now < to
            // Wrap-around: 22:00 → 06:00 means "22:00 or later" OR "before 06:00"
            else now >= from || now < to
        }
    }

    /** Match if current weekday is in [days]. */
    data class WeekdayIn(val days: Set<Weekday>) : Condition() {
        override fun matches(state: DeviceState) = state.weekday in days
    }
}

/** Snapshot of every observable signal a [Condition] might consult. */
data class DeviceState(
    /** 0..100 inclusive. */
    val batteryPercent: Int,
    val charging: Charging,
    val screen: Screen,
    val network: Network,
    val timeOfDay: LocalTime,
    val weekday: Weekday,
)

enum class Charging {
    /** External power connected and battery is gaining or holding charge. */
    Charging,
    /** On battery; voltage falling or steady at sub-100%. */
    Discharging,
    /** Plugged in but battery is already at 100% (Android reports this state). */
    PluggedNotCharging,
}

enum class Screen { ON, OFF }

enum class Network {
    /** Connected to a Wi-Fi access point. */
    WIFI,
    /** Cellular data only (or attached to a non-Wi-Fi mobile network). */
    CELLULAR,
    /** Neither — airplane mode, no signal, etc. */
    OFFLINE,
}

enum class Weekday { MON, TUE, WED, THU, FRI, SAT, SUN }

/**
 * Local time of day, 24-hour, minute precision. Total ordering by
 * `hour * 60 + minute` makes range checks one comparison each.
 *
 * Not using `kotlinx-datetime.LocalTime` because (a) it's overkill for our
 * minute-precision usage and (b) it'd add a dependency for a 6-line type.
 */
data class LocalTime(val hour: Int, val minute: Int) : Comparable<LocalTime> {
    init {
        require(hour in 0..23) { "hour 0..23, got $hour" }
        require(minute in 0..59) { "minute 0..59, got $minute" }
    }
    private val asMinutes get() = hour * 60 + minute
    override fun compareTo(other: LocalTime) = asMinutes.compareTo(other.asMinutes)
    override fun toString(): String {
        val h = if (hour < 10) "0$hour" else "$hour"
        val m = if (minute < 10) "0$minute" else "$minute"
        return "$h:$m"
    }
}
