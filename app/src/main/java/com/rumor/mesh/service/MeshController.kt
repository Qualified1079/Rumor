package com.rumor.mesh.service

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.plugin.PluginDescriptor

/**
 * Interface the UI uses to interact with the mesh.
 * Hides all service, transport, and protocol internals from UI code.
 */
interface MeshController {
    fun sendBroadcast(text: String)
    fun sendDirect(recipientId: String, text: String)
    /**
     * Send a chunked file/media payload. [recipientId] null = broadcast.
     * Splits via Chunker, signs metadata + each chunk, enqueues for gossip.
     */
    fun sendFile(
        recipientId: String?,
        contentType: ContentType,
        data: ByteArray,
        mimeType: String? = null,
        title: String? = null,
    )
    fun manualRelay(message: RumorMessage)
    fun triggerActiveScan()
    fun isServiceRunning(): Boolean

    // ── Plugins (toggle bridges on/off at runtime) ────────────────────────────
    fun availablePlugins(): List<PluginDescriptor>
    fun isPluginEnabled(pluginId: String): Boolean
    fun setPluginEnabled(pluginId: String, enabled: Boolean)
}
