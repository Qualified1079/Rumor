package com.rumor.mesh.plugin

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.rumor.mesh.core.logging.RumorLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS_NAME = "rumor_plugins"
private const val TAG = "PluginCatalog"

/**
 * Description of a plugin that can be toggled on at runtime.
 *
 * [factory] returns a fresh instance on each enable. Plugins must not retain
 * state across enable/disable cycles — when disabled, the registry calls
 * [RumorPlugin.onDetach] and discards the instance.
 *
 * [category] is a free-form grouping label for the UI (e.g. "Bridges",
 * "Logging", "Automation"). The catalog itself does nothing with it.
 */
data class PluginDescriptor(
    val pluginId: String,
    val displayName: String,
    val description: String,
    val category: String = "General",
    val factory: () -> RumorPlugin,
)

/**
 * Registry of available plugins and their enabled state.
 *
 * Bridges (Meshtastic, MeshCore, etc.) are listed here as descriptors. Nothing
 * loads them automatically — the user toggles them on from Settings, this
 * catalog is consulted, and [PluginRegistry] does the actual register/unregister.
 *
 * State is persisted in [SharedPreferences] keyed by pluginId, so toggles
 * survive app restarts.
 */
class PluginCatalog(
    context: Context,
    private val pluginRegistry: PluginRegistry,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val descriptors = mutableMapOf<String, PluginDescriptor>()

    private val _enabled = MutableStateFlow<Set<String>>(emptySet())
    val enabledIds: StateFlow<Set<String>> = _enabled.asStateFlow()

    /** Register an available plugin. Idempotent. Does not enable it. */
    fun declare(descriptor: PluginDescriptor) {
        descriptors[descriptor.pluginId] = descriptor
        if (prefs.getBoolean(descriptor.pluginId, false)) {
            // Was enabled in a prior session — re-attach now that it's known.
            enable(descriptor.pluginId)
        }
    }

    fun available(): List<PluginDescriptor> = descriptors.values.toList()

    fun isEnabled(pluginId: String): Boolean = pluginId in _enabled.value

    fun setEnabled(pluginId: String, enabled: Boolean) {
        if (enabled) enable(pluginId) else disable(pluginId)
    }

    private fun enable(pluginId: String) {
        val descriptor = descriptors[pluginId] ?: run {
            RumorLog.w(TAG, "enable: unknown plugin $pluginId")
            return
        }
        if (pluginId in _enabled.value) return
        runCatching { pluginRegistry.register(descriptor.factory()) }
            .onFailure {
                RumorLog.w(TAG, "Failed to enable $pluginId", it)
                return
            }
        _enabled.value = _enabled.value + pluginId
        prefs.edit { putBoolean(pluginId, true) }
    }

    private fun disable(pluginId: String) {
        if (pluginId !in _enabled.value) return
        pluginRegistry.unregister(pluginId)
        _enabled.value = _enabled.value - pluginId
        prefs.edit { putBoolean(pluginId, false) }
    }
}
