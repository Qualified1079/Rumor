package com.rumor.mesh.plugin

import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.core.logging.LogLevel
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.data.ContactDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages the lifecycle of all registered plugins and provides each one with
 * a [PluginContext] that exposes only what plugins need — no core internals.
 *
 * Adding a new plugin
 * -------------------
 * 1. Create a class that extends [BasePlugin] (or implements [RumorPlugin] directly).
 * 2. Call [register] from [com.rumor.mesh.service.MeshService] after the service starts.
 * 3. That's it. The registry handles the rest.
 */
class PluginRegistry(
    private val gossipEngine: GossipEngine,
    private val identityManager: IdentityManager,
    private val contactDao: ContactDao,
) {
    private val TAG = "PluginRegistry"
    private val plugins = CopyOnWriteArrayList<RumorPlugin>()

    /**
     * Register a plugin and call its [RumorPlugin.onAttach].
     * If a plugin with the same [RumorPlugin.pluginId] is already registered,
     * it is replaced after detaching the old one.
     */
    fun register(plugin: RumorPlugin) {
        unregister(plugin.pluginId)
        val ctx = PluginContextImpl(plugin.pluginId)
        plugin.onAttach(ctx)
        plugins.add(plugin)
        RumorLog.i(TAG, "Registered plugin: ${plugin.pluginId} (${plugin.displayName} v${plugin.version})")
    }

    /** Unregister a plugin by ID and call its [RumorPlugin.onDetach]. */
    fun unregister(pluginId: String) {
        val plugin = plugins.firstOrNull { it.pluginId == pluginId } ?: return
        plugin.onDetach()
        plugins.remove(plugin)
        RumorLog.i(TAG, "Unregistered plugin: $pluginId")
    }

    /** Unregister all plugins. Called when MeshService is stopping. */
    fun unregisterAll() {
        plugins.toList().forEach { unregister(it.pluginId) }
    }

    /** Forward an incoming mesh message to all registered plugins. */
    fun onMessageReceived(message: RumorMessage) {
        plugins.forEach { plugin ->
            try {
                plugin.onMessageReceived(message)
            } catch (e: Exception) {
                RumorLog.w(TAG, "Plugin ${plugin.pluginId} threw in onMessageReceived", e)
            }
        }
    }

    fun registeredPluginIds(): List<String> = plugins.map { it.pluginId }

    // ── PluginContext implementation ──────────────────────────────────────────

    /**
     * Private implementation of [PluginContext] created for each plugin.
     * Each plugin gets its own instance so logging is attributed correctly,
     * but all instances share the same underlying engine and DAO references.
     */
    private inner class PluginContextImpl(
        override val pluginId: String,
    ) : PluginContext {

        override val localUserId: String?
            get() = identityManager.identity.value?.userId

        override val localPublicKey: String?
            get() = identityManager.identity.value?.publicKeyBytes
                ?.let { java.util.Base64.getEncoder().encodeToString(it) }

        override fun sendMessage(message: RumorMessage, sourceDescription: String) {
            RumorLog.d(TAG, "Plugin $pluginId injecting message from $sourceDescription")
            gossipEngine.injectFromPlugin(message, pluginId)
        }

        override fun observeIncoming(): Flow<RumorMessage> =
            gossipEngine.incomingMessages

        override val contacts: Flow<List<ContactSummary>>
            get() = contactDao.observeAll().map { entities ->
                entities.map { e ->
                    ContactSummary(
                        userId = e.userId,
                        publicKey = e.publicKey,
                        displayName = e.displayName,
                        isVerified = e.isVerified,
                    )
                }
            }

        override fun log(level: LogLevel, message: String, throwable: Throwable?) {
            RumorLog.log(level, pluginId, message, throwable)
        }
    }
}
