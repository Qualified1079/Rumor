package com.rumor.mesh.plugin.meshtastic

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
 * Meshtastic bridge plugin.
 *
 * Translates between Rumor messages and the Meshtastic device API.
 * Connects to a Meshtastic node via Bluetooth serial (most common — node on
 * roof, phone indoors) or USB serial via OTG adapter.
 *
 * Protocol reference
 * ------------------
 * Meshtastic protobufs: https://github.com/meshtastic/protobufs
 * Serial framing: https://meshtastic.org/docs/development/device/serial-api
 *
 * Implementing the TODOs
 * ----------------------
 * 1. Add a Meshtastic protobuf library dependency (e.g., via Maven or local AAR).
 * 2. Implement [openBluetoothConnection] / [openUsbConnection] to open the
 *    serial stream and start [readLoop].
 * 3. Implement [decodeMeshtasticPacket] to parse the raw bytes into a
 *    [MeshPacket] protobuf and extract the fields used in [onPacketReceived].
 * 4. Implement [encodeMeshtasticPacket] to serialise a [RumorMessage] into
 *    the Meshtastic wire format and write it to [outputStream].
 *
 * Everything else — lifecycle, logging, message routing — is handled by
 * [BasePlugin] and [PluginContext]. You only need to touch hardware I/O.
 */
class MeshtasticBridge : BasePlugin() {

    override val pluginId    = "meshtastic"
    override val displayName = "Meshtastic Bridge"
    override val version     = "0.1.0"

    private var connectionType = ConnectionType.NONE

    // Replace with a real stream once hardware I/O is implemented
    private var outputStream: java.io.OutputStream? = null

    enum class ConnectionType { NONE, BLUETOOTH, USB_SERIAL }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onAttach(ctx: PluginContext) {
        super.onAttach(ctx)
        // Subscribe to incoming mesh broadcasts and forward them over LoRa
        pluginScope.launch {
            observeIncoming()
                .filter { it.type == MessageType.BROADCAST }
                .collect { msg -> forwardToMeshtastic(msg) }
        }
    }

    override fun onDetach() {
        closeConnection()
        super.onDetach()
    }

    // ── Hardware connection ───────────────────────────────────────────────────

    /**
     * Connect to a Meshtastic device over Bluetooth serial.
     * Call this from MeshService (or a connection manager) when a paired device is found.
     */
    fun connectBluetooth(deviceAddress: String) {
        log(LogLevel.INFO, "Connecting via Bluetooth: $deviceAddress")
        connectionType = ConnectionType.BLUETOOTH
        // TODO: open BT RFCOMM socket to deviceAddress
        // TODO: outputStream = socket.outputStream
        // TODO: pluginScope.launch { readLoop(socket.inputStream) }
    }

    /**
     * Connect to a Meshtastic device over USB serial (OTG adapter or USB-C dongle).
     * [devicePath] is typically "/dev/ttyUSB0" or obtained from UsbManager.
     */
    fun connectUsb(devicePath: String) {
        log(LogLevel.INFO, "Connecting via USB serial: $devicePath")
        connectionType = ConnectionType.USB_SERIAL
        // TODO: open USB serial connection (115200 baud, 8N1, no flow control)
        // TODO: outputStream = usbStream
        // TODO: pluginScope.launch { readLoop(usbStream) }
    }

    private fun closeConnection() {
        runCatching { outputStream?.close() }
        outputStream = null
        connectionType = ConnectionType.NONE
        log(LogLevel.INFO, "Connection closed")
    }

    // ── Inbound: Meshtastic → Rumor mesh ─────────────────────────────────────

    /**
     * Read loop running on [pluginScope].
     * Call this after opening a hardware stream.
     */
    private suspend fun readLoop(inputStream: java.io.InputStream) {
        log(LogLevel.INFO, "Read loop started")
        try {
            // TODO: read Meshtastic framing (varint-length-prefixed protobufs)
            // TODO: for each frame: call onPacketReceived(bytes)
        } catch (e: Exception) {
            log(LogLevel.WARN, "Read loop ended: ${e.message}", e)
        }
    }

    /**
     * Called for each complete Meshtastic packet received from the device.
     * Decodes the packet and injects the message into the local mesh.
     */
    private fun onPacketReceived(rawBytes: ByteArray) {
        // TODO: decode rawBytes into a Meshtastic MeshPacket protobuf
        // TODO: extract:
        //   from      → String (Meshtastic node ID, e.g. "!a1b2c3d4")
        //   text      → String (decoded payload)
        //   hopLimit  → Int (becomes RumorMessage.ttl)
        //   channel   → Int (use for routing decisions if needed)

        val message = RumorMessage(
            id               = java.util.UUID.randomUUID().toString().replace("-", ""),
            senderId         = "meshtastic_PLACEHOLDER",  // TODO: real node ID
            senderPublicKey  = "",                        // Meshtastic nodes don't use Ed25519
            sequenceNumber   = System.currentTimeMillis(),
            sentAtMs         = System.currentTimeMillis(),
            type             = MessageType.BROADCAST,
            ttl              = 3,                         // TODO: map from Meshtastic hop_limit
            payload          = MessagePayload(ContentType.TEXT, "TODO: decoded payload"),
            signature        = PluginContext.BRIDGE_UNSIGNED,
        )
        sendMessage(message, sourceDescription = "meshtastic node")
    }

    // ── Outbound: Rumor mesh → Meshtastic ────────────────────────────────────

    private fun forwardToMeshtastic(message: RumorMessage) {
        val stream = outputStream ?: return
        log(LogLevel.DEBUG, "Forwarding to Meshtastic: ${message.id.take(8)}…")
        // TODO: encode message into a Meshtastic MeshPacket protobuf
        // TODO: write length-prefixed bytes to stream
    }
}
