package com.rumor.mesh.plugin.meshcore

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.plugin.RumorPlugin

/**
 * MeshCore bridge plugin.
 *
 * Same architecture as [MeshtasticBridge] — different wire protocol.
 * MeshCore uses source-based hybrid routing rather than Meshtastic's managed flood.
 * Runs on the same hardware (T-Beam, T-Echo, Heltec, etc.).
 *
 * Protocol documentation: https://github.com/ripplebiz/MeshCore
 *
 * This stub establishes the architecture; protocol translation is implemented separately.
 */
class MeshCoreBridge : RumorPlugin {

    override val pluginId = "meshcore"
    private val TAG = "MeshCoreBridge"

    override var injectMessage: ((RumorMessage) -> Unit) = {}

    private var isConnected = false

    override fun onAttach() {
        RumorLog.i(TAG, "MeshCore bridge attached")
        // TODO: scan for MeshCore devices via BT or USB
    }

    override fun onDetach() {
        isConnected = false
        RumorLog.i(TAG, "MeshCore bridge detached")
    }

    override fun onMessageReceived(message: RumorMessage) {
        if (!isConnected) return
        if (message.type != MessageType.BROADCAST) return

        // TODO: convert RumorMessage → MeshCore packet format
        RumorLog.d(TAG, "Forwarding to MeshCore: ${message.id.take(8)}…")
    }

    fun connect(deviceAddress: String) {
        RumorLog.i(TAG, "Connecting to MeshCore device: $deviceAddress")
        // TODO: open connection
        isConnected = true
    }

    private fun onMeshCorePacketReceived(rawBytes: ByteArray) {
        // TODO: decode MeshCore packet
        // TODO: extract source, payload, hop count
        val placeholderMessage = RumorMessage(
            id = java.util.UUID.randomUUID().toString().replace("-", ""),
            senderId = "meshcore_node",
            senderPublicKey = "",
            sequenceNumber = System.currentTimeMillis(),
            elapsedMs = 0,
            type = MessageType.BROADCAST,
            ttl = 3,
            payload = MessagePayload(ContentType.TEXT, "TODO: decoded MeshCore payload"),
            signature = "bridge_unsigned",
        )
        injectMessage(placeholderMessage)
    }
}
