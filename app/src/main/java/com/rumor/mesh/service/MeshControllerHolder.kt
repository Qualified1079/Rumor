package com.rumor.mesh.service

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.plugin.PluginDescriptor

/**
 * Holds the live [MeshController] handed out by [MeshService]'s binder.
 *
 * The UI binds to the service asynchronously; ViewModels need a stable handle they
 * can call into immediately. The holder returns a no-op controller until the
 * service binder is plugged in, then forwards to it. Set/cleared from the
 * activity's [android.content.ServiceConnection] callbacks.
 */
class MeshControllerHolder {
    @Volatile private var current: MeshController? = null

    fun set(controller: MeshController) { current = controller }
    fun clear() { current = null }

    /** Always non-null; returns a no-op stub when the service isn't bound yet. */
    fun controller(): MeshController = current ?: NoOp

    private object NoOp : MeshController {
        override fun sendBroadcast(text: String) {}
        override fun sendDirect(recipientId: String, text: String) {}
        override fun sendFile(
            recipientId: String?,
            contentType: ContentType,
            data: ByteArray,
            mimeType: String?,
            title: String?,
        ) {}
        override fun manualRelay(message: RumorMessage) {}
        override fun triggerActiveScan() {}
        override fun isServiceRunning(): Boolean = false
        override fun sentPlaintextFor(messageId: String): String? = null
        override fun availablePlugins(): List<PluginDescriptor> = emptyList()
        override fun isPluginEnabled(pluginId: String): Boolean = false
        override fun setPluginEnabled(pluginId: String, enabled: Boolean) {}
    }
}
