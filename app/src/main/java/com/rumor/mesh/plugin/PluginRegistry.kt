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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
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
    private val dmEnvelopeRegistry: DmEnvelopeRegistry,
) {
    private val TAG = "PluginRegistry"
    private val plugins = CopyOnWriteArrayList<RumorPlugin>()
    /** Per-plugin host-owned scopes. Cancelled on unregister regardless of plugin behaviour. */
    private val pluginScopes = ConcurrentHashMap<String, CoroutineScope>()
    /** Tracks the PluginContextImpl per plugin so we can unregister envelopes on teardown. */
    private val pluginContexts = ConcurrentHashMap<String, PluginContextImpl>()

    /**
     * Register a plugin and call its [RumorPlugin.onAttach].
     * If a plugin with the same [RumorPlugin.pluginId] is already registered,
     * it is replaced after detaching the old one.
     */
    fun register(plugin: RumorPlugin) {
        unregister(plugin.pluginId)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        pluginScopes[plugin.pluginId] = scope
        val ctx = PluginContextImpl(plugin.pluginId, scope)
        pluginContexts[plugin.pluginId] = ctx
        plugin.onAttach(ctx)
        plugins.add(plugin)
        RumorLog.i(TAG, "Registered plugin: ${plugin.pluginId} (${plugin.displayName} v${plugin.version})")
    }

    /**
     * Unregister a plugin by ID. Cancels the host-owned scope first — killing every
     * coroutine the plugin started — then unregisters any DmEnvelopes the plugin
     * registered (atomically, before onDetach), then calls [RumorPlugin.onDetach].
     * This order guarantees toggleability even for plugins whose own cleanup is incorrect.
     */
    fun unregister(pluginId: String) {
        val plugin = plugins.firstOrNull { it.pluginId == pluginId } ?: return
        pluginScopes.remove(pluginId)?.cancel()
        pluginContexts.remove(pluginId)?.unregisterAllEnvelopes()
        runCatching { plugin.onDetach() }
            .onFailure { RumorLog.w(TAG, "Plugin $pluginId threw in onDetach", it) }
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
    inner class PluginContextImpl(
        override val pluginId: String,
        override val scope: CoroutineScope,
    ) : PluginContext {

        private val registeredPrefixes = CopyOnWriteArrayList<String>()

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

        override fun registerDmEnvelope(envelope: DmEnvelope) {
            dmEnvelopeRegistry.register(envelope)
            registeredPrefixes.add(envelope.recipientPrefix)
        }

        override fun injectBridgedDm(
            recipientUserId: String,
            senderUserId: String,
            senderPubKey: ByteArray,
            ciphertext: ByteArray,
            envelopeId: String,
        ) {
            gossipEngine.injectBridgedDm(recipientUserId, senderUserId, senderPubKey, ciphertext, envelopeId, pluginId)
        }

        override fun observeOutboundBridgedDm(): Flow<BridgedDmOutbound> =
            gossipEngine.outboundBridgedDm

        /** Called by [PluginRegistry.unregister] before [RumorPlugin.onDetach]. */
        fun unregisterAllEnvelopes() {
            registeredPrefixes.forEach { dmEnvelopeRegistry.unregister(it) }
            registeredPrefixes.clear()
        }

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
