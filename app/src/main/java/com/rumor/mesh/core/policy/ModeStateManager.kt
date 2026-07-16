package com.rumor.mesh.core.policy

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.mode.ModeState
import com.rumor.mesh.core.model.UserMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS_NAME = "rumor_mode_state"
private const val KEY_MODE = "mode"
private const val TAG = "ModeStateManager"

/**
 * Android-backed [ModeState] (O62). Persists the current [UserMode] in
 * SharedPreferences so it survives restarts. Replaces the old binary
 * `StaticModeManager` — components now read the mode's O62 [envelope] instead
 * of a static/not boolean.
 *
 * Today the mode is set manually (Settings toggle → [setMode]). Auto-triggers
 * — plug/unplug, screen on/off, time-of-day — drive it through
 * `ModeProfile.evaluate(DeviceState)` and are the remaining O62/O57 wiring
 * (BroadcastReceivers assembling a fresh DeviceState); [setMode] is the single
 * sink both the manual toggle and the future auto-trigger call.
 */
class ModeStateManager(context: Context) : ModeState {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(readPersisted())
    override val mode: StateFlow<UserMode> = _mode.asStateFlow()

    fun setMode(mode: UserMode) {
        if (_mode.value == mode) return
        prefs.edit { putString(KEY_MODE, mode.name) }
        _mode.value = mode
        // The G12 self-presence mode-change pulse (entry + symmetric exit) fires
        // off this transition; MeshService observes [mode] and composes it.
        RumorLog.i(TAG, "Mode → $mode")
    }

    private fun readPersisted(): UserMode =
        prefs.getString(KEY_MODE, null)?.let { runCatching { UserMode.valueOf(it) }.getOrNull() }
            ?: UserMode.MOBILE
}
