package com.rumor.mesh.core.policy

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.rumor.mesh.core.logging.RumorLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS_NAME = "rumor_static_mode"
private const val KEY_ENABLED = "enabled"
private const val TAG = "StaticModeManager"

/**
 * Android-backed [StaticMode]. Persists the toggle in SharedPreferences so it
 * survives restarts. The user flips it from Settings when the device is plugged
 * in or otherwise meant to run as an always-on node.
 */
class StaticModeManager(context: Context) : StaticMode {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        if (_enabled.value == enabled) return
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
        _enabled.value = enabled
        RumorLog.i(TAG, "Static mode ${if (enabled) "enabled" else "disabled"}")
    }
}
