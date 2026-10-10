package com.rumor.mesh.service

import com.rumor.mesh.core.logging.RumorLog
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
 *
 * O164: the unbound window is reopened on every background->foreground cycle
 * (`onStop` clears the holder, `onStart`'s rebind is async), so it is routine,
 * not just a first-launch edge. A caller that treats the no-op as success loses
 * the user's message with the UI showing no difference — so [isBound] exists for
 * callers to check BEFORE claiming success, and the stub logs every mutating
 * call it swallows. The no-op is a last resort, never a silent one.
 */
class MeshControllerHolder {
    @Volatile private var current: MeshController? = null

    fun set(controller: MeshController) { current = controller }
    fun clear() { current = null }

    /** Always non-null; returns a no-op stub when the service isn't bound yet. */
    fun controller(): MeshController = current ?: NoOp

    /**
     * Whether a real controller is plugged in. Callers that would otherwise
     * report success to the user must check this first (O164).
     */
    fun isBound(): Boolean = current != null

    private object NoOp : MeshController {
        private const val TAG = "MeshControllerHolder"
        private fun dropped(what: String) =
            RumorLog.w(TAG, "Service not bound — dropped $what (O164)")

        override fun sendBroadcast(text: String) { dropped("broadcast") }
        override fun sendDirect(recipientId: String, text: String) { dropped("DM") }
        override fun sendFile(
            recipientId: String?,
            contentType: ContentType,
            data: ByteArray,
            mimeType: String?,
            title: String?,
        ) { dropped("file send") }
        override fun manualRelay(message: RumorMessage) { dropped("manual relay") }
        override fun triggerActiveScan() { dropped("active scan") }
        override fun isServiceRunning(): Boolean = false
        override fun sentPlaintextFor(messageId: String): String? = null
        override fun availablePlugins(): List<PluginDescriptor> = emptyList()
        override fun isPluginEnabled(pluginId: String): Boolean = false
        override fun setPluginEnabled(pluginId: String, enabled: Boolean) {}
    }
}
