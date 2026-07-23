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
import kotlinx.coroutines.flow.filter
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

    /**
     * O123: each plugin is held with an [PluginHolder.alive] flag cleared at the
     * very start of [unregister], BEFORE teardown. The dispatch loop checks it
     * per plugin, so a message in flight when a plugin is being torn down stops
     * invoking that plugin instead of calling it on a cancelled scope / post-
     * onDetach state. The per-plugin try/catch still backstops the nanosecond
     * check→call window, but the flag closes the large window the raw
     * CopyOnWriteArrayList snapshot left open.
     */
    private class PluginHolder(val plugin: RumorPlugin) {
        @Volatile var alive = true
    }

    private val plugins = CopyOnWriteArrayList<PluginHolder>()
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
        val ctx = PluginContextImpl(
            pluginId = plugin.pluginId,
            scope = scope,
            gossipEngine = gossipEngine,
            identityManager = identityManager,
            contactDao = contactDao,
            dmEnvelopeRegistry = dmEnvelopeRegistry,
        )
        pluginContexts[plugin.pluginId] = ctx
        plugin.onAttach(ctx)
        plugins.add(PluginHolder(plugin))
        RumorLog.i(TAG, "Registered plugin: ${plugin.pluginId} (${plugin.displayName} v${plugin.version})")
    }

    /**
     * Unregister a plugin by ID. Cancels the host-owned scope first — killing every
     * coroutine the plugin started — then unregisters any DmEnvelopes the plugin
     * registered (atomically, before onDetach), then calls [RumorPlugin.onDetach].
     * This order guarantees toggleability even for plugins whose own cleanup is incorrect.
     */
    fun unregister(pluginId: String) {
        val holder = plugins.firstOrNull { it.plugin.pluginId == pluginId } ?: return
        // O123: mark dead FIRST so any in-flight dispatch loop skips this plugin
        // before its scope is cancelled and onDetach runs.
        holder.alive = false
        pluginScopes.remove(pluginId)?.cancel()
        pluginContexts.remove(pluginId)?.unregisterAllEnvelopes()
        runCatching { holder.plugin.onDetach() }
            .onFailure { RumorLog.w(TAG, "Plugin $pluginId threw in onDetach", it) }
        plugins.remove(holder)
        RumorLog.i(TAG, "Unregistered plugin: $pluginId")
    }

    /** Unregister all plugins. Called when MeshService is stopping. */
    fun unregisterAll() {
        plugins.toList().forEach { unregister(it.plugin.pluginId) }
    }

    /** Forward an incoming mesh message to all registered plugins. */
    fun onMessageReceived(message: RumorMessage) {
        plugins.forEach { holder ->
            // O123: don't invoke a plugin that unregister() has begun tearing down.
            if (!holder.alive) return@forEach
            try {
                holder.plugin.onMessageReceived(message)
            } catch (e: Exception) {
                RumorLog.w(TAG, "Plugin ${holder.plugin.pluginId} threw in onMessageReceived", e)
            }
        }
    }

    fun registeredPluginIds(): List<String> = plugins.filter { it.alive }.map { it.plugin.pluginId }

}

// ── PluginContext implementation ──────────────────────────────────────────

/**
 * Implementation of [PluginContext] created for each plugin.
 *
 * Non-inner (no captured enclosing `PluginRegistry` reference) on purpose: a
 * plugin downcasting its [PluginContext] to this class would otherwise reach
 * `PluginRegistry` via Kotlin's synthetic `this$0` and could call
 * `register`/`unregister` on peer plugins. Exposing only the explicit
 * collaborators below limits the blast radius to capabilities the plugin
 * already has via normal interface methods.
 *
 * File-private (`internal` visibility is enough within the module; we restrict
 * by keeping the class outside any public surface).
 */
private class PluginContextImpl(
    override val pluginId: String,
    override val scope: CoroutineScope,
    private val gossipEngine: com.rumor.mesh.core.protocol.GossipEngine,
    private val identityManager: IdentityManager,
    private val contactDao: ContactDao,
    private val dmEnvelopeRegistry: DmEnvelopeRegistry,
) : PluginContext {

    private val TAG = "PluginRegistry"
    private val registeredPrefixes = CopyOnWriteArrayList<String>()

    override val localUserId: String?
        get() = identityManager.identity.value?.userId

    override val localPublicKey: String?
        get() = identityManager.identity.value?.publicKeyBytes
            ?.let { java.util.Base64.getEncoder().encodeToString(it) }

    override fun signWithLocalKey(bytes: ByteArray): ByteArray {
        val identity = identityManager.identity.value
            ?: throw IllegalStateException("Cannot sign: local identity is locked")
        return com.rumor.mesh.core.crypto.CryptoManager.sign(bytes, identity.privateKeyBytes)
    }

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

    /**
     * H1 fix: filter the engine-wide outbound flow down to events whose recipient
     * matches one of THIS plugin's registered prefixes. Without this filter, every
     * plugin (including ones that registered no envelope) would see every other
     * bridge plugin's outbound ciphertexts and recipient userIds — defeating the
     * Architecture B promise that only the target bridge sees the bytes.
     */
    override fun observeOutboundBridgedDm(): Flow<BridgedDmOutbound> =
        gossipEngine.outboundBridgedDm.filter { event ->
            registeredPrefixes.any { event.recipientUserId.startsWith(it) }
        }

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
