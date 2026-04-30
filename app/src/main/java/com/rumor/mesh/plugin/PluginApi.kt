package com.rumor.mesh.plugin

import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.data.ContactEntity
import com.rumor.mesh.data.RouteEntity

/**
 * Interface that all bridge plugins implement.
 * Plugins read from and inject into the gossip engine without knowing anything
 * about BLE, Wi-Fi Direct, or internal protocol state.
 */
interface RumorPlugin {
    val pluginId: String

    /**
     * Called by the gossip engine for every newly ingested message.
     * Plugin decides whether to forward it to its external network.
     */
    fun onMessageReceived(message: RumorMessage)

    /**
     * Plugin pushes a message into the local gossip engine
     * (e.g., a message received from a Meshtastic node).
     * Implementation is provided by [PluginRegistry].
     */
    var injectMessage: ((RumorMessage) -> Unit)

    /**
     * Optional: called once when the plugin is registered.
     * Use for hardware connection setup (e.g., open serial port to LoRa device).
     */
    fun onAttach() {}

    /** Called when the plugin is unregistered or the service is stopping. */
    fun onDetach() {}
}
