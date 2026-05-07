package com.rumor.mesh.plugin.meshcore

import com.rumor.mesh.core.logging.LogLevel
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.plugin.BasePlugin
import com.rumor.mesh.plugin.PluginContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * MeshCore bridge plugin.
 *
 * Same architecture as [MeshtasticBridge] — different wire protocol.
 * MeshCore uses source-based hybrid routing (closer to distance-vector)
 * rather than Meshtastic's managed flood.
 *
 * Protocol reference
 * ------------------
 * MeshCore: https://github.com/ripplebiz/MeshCore
 * MeshCore runs on the same hardware as Meshtastic (T-Beam, T-Echo, Heltec,
 * RAK WisBlock, etc.) so existing LoRa hardware works with either protocol.
 * Communities choose; Rumor supports both without forcing a decision.
 *
 * Implementing the TODOs
 * ----------------------
 * 1. Review the MeshCore serial protocol documentation (link above).
 * 2. Implement [connect] to open the serial stream and start [readLoop].
 * 3. Implement [decodeMeshCorePacket] for the MeshCore packet format.
 * 4. Implement [encodeMeshCorePacket] for outbound messages.
 *
 * The plugin lifecycle, logging, and message routing are identical to
 * [MeshtasticBridge] — only the packet encoding/decoding differs.
 */
class MeshCoreBridge : BasePlugin() {

    override val pluginId    = "meshcore"
    override val displayName = "MeshCore Bridge"
    override val version     = "0.1.0"

    private var outputStream: java.io.OutputStream? = null
    private var isConnected = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onAttach(ctx: PluginContext) {
        super.onAttach(ctx)
        pluginScope.launch {
            observeIncoming()
                .filter { it.type == MessageType.BROADCAST }
                .collect { msg -> forwardToMeshCore(msg) }
        }
    }

    override fun onDetach() {
        closeConnection()
        super.onDetach()
    }

    // ── Hardware connection ───────────────────────────────────────────────────

    /** Connect to a MeshCore device via Bluetooth or USB serial. */
    fun connect(deviceAddress: String) {
        log(LogLevel.INFO, "Connecting to MeshCore device: $deviceAddress")
        // TODO: open connection (BT or USB serial — same approach as MeshtasticBridge)
        // TODO: pluginScope.launch { readLoop(inputStream) }
        isConnected = true
    }

    private fun closeConnection() {
        runCatching { outputStream?.close() }
        outputStream = null
        isConnected = false
    }

    // ── Inbound: MeshCore → Rumor mesh ────────────────────────────────────────

    private suspend fun readLoop(inputStream: java.io.InputStream) {
        log(LogLevel.INFO, "Read loop started")
        try {
            // TODO: read MeshCore frames (consult MeshCore serial protocol docs)
            // TODO: for each frame: call onPacketReceived(bytes)
        } catch (e: Exception) {
            log(LogLevel.WARN, "Read loop ended: ${e.message}", e)
        }
    }

    private fun onPacketReceived(rawBytes: ByteArray) {
        // TODO: decode MeshCore packet format
        // TODO: extract sender, payload, hop count

        val message = RumorMessage(
            id               = java.util.UUID.randomUUID().toString().replace("-", ""),
            senderId         = "meshcore_PLACEHOLDER",
            senderPublicKey  = "",
            sequenceNumber   = System.currentTimeMillis(),
            elapsedMs        = 0,
            type             = MessageType.BROADCAST,
            ttl              = 3,
            payload          = MessagePayload(ContentType.TEXT, "TODO: decoded payload"),
            signature        = PluginContext.BRIDGE_UNSIGNED,
        )
        sendMessage(message, sourceDescription = "meshcore node")
    }

    // ── Outbound: Rumor mesh → MeshCore ──────────────────────────────────────

    private fun forwardToMeshCore(message: RumorMessage) {
        if (!isConnected) return
        log(LogLevel.DEBUG, "Forwarding to MeshCore: ${message.id.take(8)}…")
        // TODO: encode into MeshCore packet format and write to outputStream
    }
}
