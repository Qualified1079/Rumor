package com.rumor.mesh.plugin

import com.rumor.mesh.core.logging.LogLevel
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.RumorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Convenience base class for bridge plugins.
 *
 * Handles the [PluginContext] lifecycle and provides a coroutine scope
 * that is automatically cancelled on [onDetach]. Subclasses get direct
 * access to [context], [pluginScope], and the helper methods below.
 *
 * Minimal plugin skeleton:
 *
 *     class MyBridge : BasePlugin() {
 *         override val pluginId    = "my_bridge"
 *         override val displayName = "My Bridge"
 *         override val version     = "1.0.0"
 *
 *         override fun onAttach(context: PluginContext) {
 *             super.onAttach(context)
 *             // open hardware connection
 *             pluginScope.launch { listenForPackets() }
 *         }
 *
 *         override fun onMessageReceived(message: RumorMessage) {
 *             // forward outbound broadcasts to external network
 *         }
 *
 *         override fun onDetach() {
 *             // close hardware connection — pluginScope is cancelled automatically
 *             super.onDetach()
 *         }
 *     }
 */
abstract class BasePlugin : RumorPlugin {

    /** Available after [onAttach] is called. Null before attachment. */
    protected var context: PluginContext? = null
        private set

    /**
     * Coroutine scope for plugin background work.
     *
     * Returns the host-owned scope from [PluginContext.scope] once attached.
     * The host cancels that scope when the plugin is disabled — every coroutine
     * launched here dies, regardless of whether [onDetach] is well-behaved.
     *
     * Pre-attach use is unsupported (returns a one-shot fallback for safety
     * during early init only).
     */
    protected val pluginScope: CoroutineScope
        get() = context?.scope ?: fallbackScope

    private val fallbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onAttach(context: PluginContext) {
        this.context = context
        log(LogLevel.INFO, "$displayName v$version attached")
    }

    override fun onDetach() {
        log(LogLevel.INFO, "$displayName detaching")
        // Host already cancelled context.scope; cancel the fallback too in case
        // any pre-attach work was launched there.
        fallbackScope.cancel()
        context = null
    }

    // ── Convenience helpers (call from anywhere in the subclass) ──────────────

    /** Inject a message into the local mesh. Safe to call from any thread. */
    protected fun sendMessage(message: RumorMessage, sourceDescription: String = pluginId) {
        context?.sendMessage(message, sourceDescription)
            ?: log(LogLevel.WARN, "sendMessage called before onAttach — message dropped")
    }

    /** Subscribe to incoming mesh messages. Returns an empty flow if not attached. */
    protected fun observeIncoming(): Flow<RumorMessage> =
        context?.observeIncoming() ?: emptyFlow()

    /** Write a log entry attributed to this plugin. */
    protected fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        RumorLog.log(level, pluginId, message, throwable)
    }
}
