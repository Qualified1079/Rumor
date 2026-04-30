package com.rumor.mesh.plugin.meshtastic

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.plugin.RumorPlugin

/**
 * Meshtastic bridge plugin.
 *
 * Translates between Rumor messages and the Meshtastic serial/BT API.
 * Connects to a Meshtastic device via:
 *   - Bluetooth (most common: node on roof, phone indoors)
 *   - USB serial via OTG adapter
 *
 * Protocol translation hooks are clearly marked TODO — the Meshtastic
 * protobuf schema lives at https://github.com/meshtastic/protobufs
 * and a Kotlin serial library is available.
 *
 * This stub establishes the architecture; device I/O is implemented separately.
 */
class MeshtasticBridge : RumorPlugin {

    override val pluginId = "meshtastic"
    private val TAG = "MeshtasticBridge"

    override var injectMessage: ((RumorMessage) -> Unit) = {}

    // Connection state
    private var connectionType: ConnectionType = ConnectionType.NONE
    private var isConnected = false

    enum class ConnectionType { NONE, BLUETOOTH, USB_SERIAL }

    override fun onAttach() {
        RumorLog.i(TAG, "Meshtastic bridge attached")
        // TODO: scan for paired Meshtastic devices via BluetoothAdapter
        // TODO: check for USB serial device via UsbManager
    }

    override fun onDetach() {
        disconnect()
        RumorLog.i(TAG, "Meshtastic bridge detached")
    }

    override fun onMessageReceived(message: RumorMessage) {
        if (!isConnected) return
        if (message.type != MessageType.BROADCAST) return  // only forward broadcasts over LoRa

        // TODO: convert RumorMessage → Meshtastic MeshPacket protobuf
        // TODO: write to serial/BT stream
        RumorLog.d(TAG, "Forwarding to Meshtastic: ${message.id.take(8)}…")
    }

    fun connectBluetooth(deviceAddress: String) {
        RumorLog.i(TAG, "Connecting to Meshtastic via BT: $deviceAddress")
        connectionType = ConnectionType.BLUETOOTH
        // TODO: open BT serial connection to Meshtastic device
        // TODO: start read loop — on packet received, call onMeshtasticPacketReceived()
        isConnected = true
    }

    fun connectUsb(devicePath: String) {
        RumorLog.i(TAG, "Connecting to Meshtastic via USB: $devicePath")
        connectionType = ConnectionType.USB_SERIAL
        // TODO: open USB serial connection (115200 baud, 8N1)
        // TODO: start read loop
        isConnected = true
    }

    private fun disconnect() {
        // TODO: close connection
        isConnected = false
        connectionType = ConnectionType.NONE
    }

    /**
     * Called when a packet arrives from the Meshtastic device.
     * Translates to a Rumor message and injects into the local gossip engine.
     */
    private fun onMeshtasticPacketReceived(rawBytes: ByteArray) {
        // TODO: decode Meshtastic MeshPacket protobuf from rawBytes
        // TODO: extract sender, payload, channel, hop limit
        // TODO: construct RumorMessage with:
        //   - senderId derived from Meshtastic node ID
        //   - type = BROADCAST
        //   - ttl mapped from Meshtastic hop_limit
        //   - payload from decoded message text/data
        //   - signature = placeholder (Meshtastic doesn't use Ed25519)

        val placeholderMessage = RumorMessage(
            id = java.util.UUID.randomUUID().toString().replace("-", ""),
            senderId = "meshtastic_node",  // TODO: real node ID
            senderPublicKey = "",           // TODO: derive or skip verification for bridge traffic
            sequenceNumber = System.currentTimeMillis(),
            elapsedMs = 0,
            type = MessageType.BROADCAST,
            ttl = 3,
            payload = MessagePayload(ContentType.TEXT, "TODO: decoded Meshtastic payload"),
            signature = "bridge_unsigned",
        )
        injectMessage(placeholderMessage)
    }
}
