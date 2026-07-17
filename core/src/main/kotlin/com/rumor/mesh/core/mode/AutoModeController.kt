package com.rumor.mesh.core.mode

import com.rumor.mesh.core.model.UserMode
import kotlin.math.abs

/**
 * O80/O57 — the pure decision half of the mode auto-fire orchestrator.
 *
 * The platform orchestrator assembles a fresh [DeviceState] on every signal
 * change (plug, screen, network, battery tick, 60s timer) and calls
 * [evaluate]; a non-null result is a mode transition to apply through
 * `ModeStateManager.setMode`. Manual-override-wins lives OUTSIDE this class —
 * the orchestrator simply doesn't call it while auto mode is off.
 *
 * Hysteresis: a transition caused *only* by battery movement (the profile
 * evaluates to the current mode when battery is held at its level from the
 * last applied transition) is suppressed until battery has moved at least
 * [batteryHysteresisPct] points from that anchor. Discrete signals (plug,
 * screen, network, time) always take effect immediately — flapping is a
 * battery-threshold phenomenon, not a plug-event one.
 */
class AutoModeController(
    private val profile: ModeProfile = starterProfile(),
    private val batteryHysteresisPct: Int = 5,
) {
    private var batteryAnchor: Int? = null

    fun evaluate(state: DeviceState, currentMode: UserMode): UserMode? {
        val anchor = batteryAnchor ?: state.batteryPercent.also { batteryAnchor = it }
        val target = profile.evaluate(state)
        if (target == currentMode) return null
        if (abs(state.batteryPercent - anchor) < batteryHysteresisPct &&
            profile.evaluate(state.copy(batteryPercent = anchor)) == currentMode
        ) {
            // Purely battery-driven and still inside the hysteresis gap.
            return null
        }
        batteryAnchor = state.batteryPercent
        return target
    }

    companion object {
        /**
         * The O57 defaults as a profile: plugged + screen off → FREE,
         * plugged → STATIC, otherwise MOBILE. The user-editable rule list
         * (O80 editor UI) starts from this template.
         */
        fun starterProfile(): ModeProfile {
            fun plugged(c: Charging) = Condition.ChargingIs(c)
            fun screenOff() = Condition.ScreenIs(Screen.OFF)
            return ModeProfile(
                rules = listOf(
                    Rule(Condition.And(listOf(plugged(Charging.Charging), screenOff())), UserMode.FREE, label = "Plugged in, screen off"),
                    Rule(Condition.And(listOf(plugged(Charging.PluggedNotCharging), screenOff())), UserMode.FREE, label = "Plugged in (full), screen off"),
                    Rule(plugged(Charging.Charging), UserMode.STATIC, label = "Plugged in"),
                    Rule(plugged(Charging.PluggedNotCharging), UserMode.STATIC, label = "Plugged in (full)"),
                ),
                defaultMode = UserMode.MOBILE,
            )
        }
    }
}
