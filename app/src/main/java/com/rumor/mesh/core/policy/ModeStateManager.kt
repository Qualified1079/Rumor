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
import kotlinx.coroutines.flow.update

private const val PREFS_NAME = "rumor_mode_state"
private const val KEY_MODE = "mode"
private const val KEY_AUTO = "auto"
private const val TAG = "ModeStateManager"
private const val TRANSITION_LOG_SIZE = 20

/**
 * Android-backed [ModeState] (O62). Persists the current [UserMode] and the
 * auto/manual flag in SharedPreferences so both survive restarts. Replaces the
 * old binary `StaticModeManager` — components read the mode's O62 [envelope]
 * instead of a static/not boolean.
 *
 * [setMode] is the single sink for both the manual Settings selector and the
 * O80 `ModeOrchestrator` auto-trigger. Manual-override-wins (O57) is enforced
 * by [autoEnabled]: the orchestrator only applies transitions while it's true,
 * and picking an explicit mode in Settings turns it off.
 */
class ModeStateManager(context: Context) : ModeState {

    enum class Source { MANUAL, AUTO }

    /** One applied transition, newest-first in [transitions] (O80 diagnosis log). */
    data class ModeTransition(val mode: UserMode, val source: Source, val atMs: Long)

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(readPersisted())
    override val mode: StateFlow<UserMode> = _mode.asStateFlow()

    private val _autoEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO, false))
    val autoEnabled: StateFlow<Boolean> = _autoEnabled.asStateFlow()

    private val _transitions = MutableStateFlow<List<ModeTransition>>(emptyList())
    val transitions: StateFlow<List<ModeTransition>> = _transitions.asStateFlow()

    fun setMode(mode: UserMode, source: Source = Source.MANUAL) {
        if (_mode.value == mode) return
        prefs.edit { putString(KEY_MODE, mode.name) }
        _mode.value = mode
        _transitions.update {
            (listOf(ModeTransition(mode, source, System.currentTimeMillis())) + it)
                .take(TRANSITION_LOG_SIZE)
        }
        // The G12 self-presence mode-change pulse (entry + symmetric exit) fires
        // off this transition; MeshService observes [mode] and composes it.
        RumorLog.i(TAG, "Mode → $mode ($source)")
    }

    fun setAutoEnabled(enabled: Boolean) {
        if (_autoEnabled.value == enabled) return
        prefs.edit { putBoolean(KEY_AUTO, enabled) }
        _autoEnabled.value = enabled
        RumorLog.i(TAG, "Auto mode ${if (enabled) "on" else "off"}")
    }

    private fun readPersisted(): UserMode =
        prefs.getString(KEY_MODE, null)?.let { runCatching { UserMode.valueOf(it) }.getOrNull() }
            ?: UserMode.MOBILE
}
