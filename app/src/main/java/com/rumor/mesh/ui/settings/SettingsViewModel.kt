package com.rumor.mesh.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.policy.ModeStateManager
import com.rumor.mesh.core.transport.DeviceQuirks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
    val userId: String? = null,
    val deviceId: String? = null,
    val scanIntervalSec: Int = 5,
    val sleepIntervalSec: Int = 30,
    val debugLogging: Boolean = false,
    val modeAuto: Boolean = false,
    val currentMode: UserMode = UserMode.MOBILE,
    val modeTransitions: List<ModeStateManager.ModeTransition> = emptyList(),
    val showBatteryOptimisationWarning: Boolean = false,
)
class SettingsViewModel(
    private val identityManager: IdentityManager,
    private val modeStateManager: ModeStateManager,
    private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState(debugLogging = RumorLog.debugMode))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            identityManager.identity.collect { identity ->
                _state.update { it.copy(
                    userId = identity?.userId,
                    deviceId = identity?.deviceId,
                ) }
            }
        }
        viewModelScope.launch {
            modeStateManager.mode.collect { mode ->
                _state.update { it.copy(currentMode = mode) }
            }
        }
        viewModelScope.launch {
            modeStateManager.autoEnabled.collect { auto ->
                _state.update { it.copy(modeAuto = auto) }
            }
        }
        viewModelScope.launch {
            modeStateManager.transitions.collect { log ->
                _state.update { it.copy(modeTransitions = log) }
            }
        }
    }

    // O57 manual-override-wins: picking an explicit mode turns auto off; the
    // orchestrator only applies transitions while auto is on.
    fun setModeAuto() = modeStateManager.setAutoEnabled(true)

    fun setModeManual(mode: UserMode) {
        modeStateManager.setAutoEnabled(false)
        modeStateManager.setMode(mode)
    }

    fun setScanInterval(seconds: Int) {
        _state.update { it.copy(scanIntervalSec = seconds) }
    }

    fun setDebugLogging(enabled: Boolean) {
        RumorLog.debugMode = enabled
        _state.update { it.copy(debugLogging = enabled) }
    }

    fun onViewLogsTapped() {
        // Navigation handled by MainActivity
    }

    fun onOpenBatterySettings() {
        val intent = DeviceQuirks.batteryOptimisationIntent(context) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
