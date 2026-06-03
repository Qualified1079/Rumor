package com.rumor.mesh.plugin

import com.rumor.mesh.core.model.RumorMessage

/**
 * Contract every Rumor bridge plugin must fulfil.
 *
 * Lifecycle
 * ---------
 * 1. [PluginRegistry.register] calls [onAttach] with a [PluginContext].
 *    The plugin stores the context and uses it to send/receive messages.
 * 2. While attached, the plugin may call [PluginContext.sendMessage] at any
 *    time to inject a message from its external network into the local mesh.
 * 3. The plugin's [onMessageReceived] is called for every new message that
 *    arrives on the local mesh, so the plugin can decide whether to forward
 *    it outward (e.g., over LoRa).
 * 4. [onDetach] is called when the registry is stopping or the plugin is
 *    unregistered. Release hardware connections here.
 *
 * Implementing a plugin
 * ---------------------
 * Extend [BasePlugin] to skip the boilerplate and only override what you need:
 *
 *     class MyBridge : BasePlugin() {
 *         override val pluginId    = "my_bridge"
 *         override val displayName = "My Bridge"
 *         override val version     = "1.0.0"
 *
 *         override fun onAttach(context: PluginContext) {
 *             super.onAttach(context)
 *             // connect to hardware
 *         }
 *
 *         override fun onMessageReceived(message: RumorMessage) {
 *             // forward to external network
 *         }
 *     }
 *
 * To register: call [PluginRegistry.register(MyBridge())] from MeshService.
 */
interface RumorPlugin {
    /**
     * Stable, machine-readable identifier. Used as a key in the registry.
     * Use lowercase with underscores: "meshtastic", "meshcore", "my_bridge".
     */
    val pluginId: String

    /** Human-readable name shown in the settings UI. */
    val displayName: String

    /** Semantic version string for display and debugging. */
    val version: String

    /**
     * Called once when the plugin is registered.
     * Store [context] — you will need it to send messages and observe traffic.
     */
    fun onAttach(context: PluginContext)

    /**
     * Called for every new message that arrives on the local mesh.
     * The plugin decides whether to forward it to its external network.
     * Default implementation does nothing.
     */
    fun onMessageReceived(message: RumorMessage) {}

    /**
     * Called when the plugin is unregistered or the service is stopping.
     * Release any hardware connections (serial port, BT socket, etc.) here.
     */
    fun onDetach()
}
