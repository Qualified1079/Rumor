package com.rumor.mesh.plugin

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.GossipEngine
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginRegistry @Inject constructor(
    private val gossipEngine: GossipEngine,
) {
    private val TAG = "PluginRegistry"
    private val plugins = CopyOnWriteArrayList<RumorPlugin>()

    fun register(plugin: RumorPlugin) {
        plugin.injectMessage = { msg ->
            RumorLog.d(TAG, "Plugin ${plugin.pluginId} injecting message ${msg.id.take(8)}…")
            gossipEngine.onSessionResult(
                // Wrap in a minimal session result so the gossip engine can process it
                com.rumor.mesh.core.transport.wifidirect.GossipSession.SessionResult(
                    peerUserId = msg.senderId,
                    peerPublicKey = msg.senderPublicKey,
                    messagesReceived = listOf(msg),
                    messagesSent = 0,
                    peerOnlineUsers = emptyMap(),
                    durationMs = 0,
                )
            )
        }
        plugin.onAttach()
        plugins.add(plugin)
        RumorLog.i(TAG, "Plugin registered: ${plugin.pluginId}")
    }

    fun unregister(pluginId: String) {
        val plugin = plugins.firstOrNull { it.pluginId == pluginId } ?: return
        plugin.onDetach()
        plugins.remove(plugin)
        RumorLog.i(TAG, "Plugin unregistered: $pluginId")
    }

    fun onMessageReceived(message: RumorMessage) {
        for (plugin in plugins) {
            try {
                plugin.onMessageReceived(message)
            } catch (e: Exception) {
                RumorLog.w(TAG, "Plugin ${plugin.pluginId} threw on onMessageReceived", e)
            }
        }
    }
}
