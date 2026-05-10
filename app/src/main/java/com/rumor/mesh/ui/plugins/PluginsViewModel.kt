package com.rumor.mesh.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.plugin.PluginCatalog
import com.rumor.mesh.plugin.PluginDescriptor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One row per declared plugin, grouped by [PluginDescriptor.category] in the UI.
 */
data class PluginRow(
    val descriptor: PluginDescriptor,
    val enabled: Boolean,
)

/**
 * Backed directly by [PluginCatalog] — the catalog is the source of truth for
 * declared plugins and their toggle state. The UI never touches [PluginRegistry].
 */
class PluginsViewModel(
    private val catalog: PluginCatalog,
) : ViewModel() {

    val rows: StateFlow<List<PluginRow>> =
        catalog.enabledIds
            .map { enabled ->
                catalog.available()
                    .map { PluginRow(it, it.pluginId in enabled) }
                    .sortedWith(compareBy({ it.descriptor.category }, { it.descriptor.displayName }))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(pluginId: String, enabled: Boolean) {
        catalog.setEnabled(pluginId, enabled)
    }
}
