package com.rumor.mesh.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.policy.StaticModeManager
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
    val staticMode: Boolean = false,
    val showBatteryOptimisationWarning: Boolean = false,
)
class SettingsViewModel(
    private val identityManager: IdentityManager,
    private val staticModeManager: StaticModeManager,
    private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
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
            staticModeManager.enabled.collect { on ->
                _state.update { it.copy(staticMode = on) }
            }
        }
    }

    fun setStaticMode(enabled: Boolean) {
        staticModeManager.setEnabled(enabled)
    }

    fun setScanInterval(seconds: Int) {
        _state.update { it.copy(scanIntervalSec = seconds) }
    }

    fun setDebugLogging(enabled: Boolean) {
        RumorLog.debugMode = enabled
        _state.update { it.copy(debugLogging = enabled) }
    }

    fun onChangePassphraseTapped() {
        // Navigation handled by MainActivity
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
