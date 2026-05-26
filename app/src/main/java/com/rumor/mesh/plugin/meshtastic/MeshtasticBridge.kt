package com.rumor.mesh.plugin.meshtastic

import android.content.Context
import com.rumor.mesh.core.logging.LogLevel
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.plugin.BasePlugin
import com.rumor.mesh.plugin.PluginContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Meshtastic bridge plugin — relays BROADCAST text between the Rumor mesh and
 * a Meshtastic LoRa radio over BLE.
 *
 * Same compartmentalization and trust rules as [com.rumor.mesh.plugin.meshcore.MeshCoreBridge]:
 * package is self-contained, talks to core only via [PluginContext], all
 * inbound traffic uses [PluginContext.BRIDGE_UNSIGNED] so the gossip engine
 * keeps it at BRIDGED trust and never re-relays.
 *
 * What's bridged
 * --------------
 * Only `TEXT_MESSAGE_APP` (port 1) packets are touched. Everything else the
 * Meshtastic network carries — telemetry, position, routing — is silently
 * dropped because there is no meaningful Rumor equivalent. Direct messages
 * are not bridged: Meshtastic DMs use a separate PKC mode (X25519) that we
 * would need a per-contact key store for; out of scope here.
 *
 * Encryption
 * ----------
 * The radio decrypts channel traffic for the companion app and exposes the
 * plaintext in `MeshPacket.decoded`. If `decoded` is missing — meaning the
 * radio has the encrypted payload but no key — we drop the frame. We do NOT
 * try to decrypt it ourselves; doing so would require synchronising channel
 * PSKs across two software stacks for no benefit, and the failure mode (loud
 * garbage in the UI) would be worse than silent drop.
 */
class MeshtasticBridge(
    private val androidContext: Context,
) : BasePlugin() {

    override val pluginId    = "bridge.meshtastic"
    override val displayName = "Meshtastic Bridge"
    override val version     = "0.1.0"

    private var ble: MeshtasticBleClient? = null
    @Volatile private var ready = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onAttach(ctx: PluginContext) {
        super.onAttach(ctx)
        val client = MeshtasticBleClient(androidContext)
        ble = client

        // Mesh → LoRa
        pluginScope.launch {
            observeIncoming()
                .filter { it.type == MessageType.BROADCAST }
                .filter { it.signature != PluginContext.BRIDGE_UNSIGNED }
                .collect { msg -> forwardToRadio(msg) }
        }

        // LoRa → mesh
        pluginScope.launch {
            client.inboundFrames.collect { frame -> onFromRadioFrame(frame) }
        }

        client.startScan { device ->
            log(LogLevel.INFO, "Found Meshtastic radio: ${device.address}")
            client.stopScan()
            client.connect(device) {
                ready = true
                log(LogLevel.INFO, "Meshtastic link ready")
            }
        }
    }

    override fun onDetach() {
        ready = false
        ble?.stopScan()
        ble?.disconnect()
        ble = null
        super.onDetach()
    }

    // ── Inbound (radio → mesh) ────────────────────────────────────────────────

    private fun onFromRadioFrame(frame: ByteArray) {
        // FromRadio carries many oneof variants; decodeFromRadioPacket returns
        // non-null only for the `packet` variant. Everything else gets silently
        // dropped at this layer — that includes config replies, log messages,
        // and node-info entries that the radio streams during initial handshake.
        val packet = runCatching { MeshtasticMessages.decodeFromRadioPacket(frame) }
            .getOrElse {
                log(LogLevel.WARN, "Malformed FromRadio frame (${frame.size} bytes): ${it.message}")
                return
            } ?: return

        val data = packet.decoded
        if (data == null) {
            // Encrypted payload on a channel whose key the radio doesn't have.
            // Bridging garbled bytes serves no one — log once and drop.
            log(LogLevel.DEBUG, "Dropping encrypted packet id=${packet.id} (no channel key on radio)")
            return
        }
        if (data.portnum != MeshtasticMessages.PORT_TEXT_MESSAGE_APP) {
            // Telemetry, position, routing, etc. — uninteresting for a text bridge.
            return
        }
        if (packet.to != MeshtasticMessages.MeshPacket.BROADCAST_NODE) {
            // DM to a specific node. Without bridging the per-node PKC keys
            // we cannot prove it was intended for us, so drop.
            return
        }

        val text = String(data.payload, Charsets.UTF_8)
        // Synthetic Rumor identity for the LoRa sender. See nameHash in
        // MeshCoreBridge for the same rationale; here we hash the 32-bit
        // node number, which is the only stable identifier we have on the
        // Meshtastic side without doing a node-info lookup.
        val syntheticUserId = "meshtastic:" + (packet.from.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')

        val rumor = RumorMessage(
            id              = UUID.randomUUID().toString().replace("-", ""),
            senderId        = syntheticUserId,
            senderPublicKey = "",
            sequenceNumber  = (packet.id.toLong() and 0xFFFFFFFFL),
            sentAtMs        = System.currentTimeMillis(),
            type            = MessageType.BROADCAST,
            hopsToLive             = 1,  // bridged traffic never relays; see MeshCoreBridge note
            payload         = MessagePayload(ContentType.TEXT, text),
            signature       = PluginContext.BRIDGE_UNSIGNED,
        )
        sendMessage(rumor, sourceDescription = "meshtastic ch${packet.channel}")
    }

    // ── Outbound (mesh → radio) ───────────────────────────────────────────────

    private fun forwardToRadio(message: RumorMessage) {
        if (!ready) return
        val client = ble ?: return
        val text = message.payload?.content ?: return

        val data = MeshtasticMessages.Data(
            portnum = MeshtasticMessages.PORT_TEXT_MESSAGE_APP,
            payload = text.toByteArray(Charsets.UTF_8),
        )
        val packet = MeshtasticMessages.MeshPacket(
            to       = MeshtasticMessages.MeshPacket.BROADCAST_NODE,
            channel  = 0,  // primary channel; multi-channel selection is a future config
            decoded  = data,
            // Random packet ID. The radio uses this for AES-CTR nonce on its
            // side, plus for deduplication across hops — collisions are
            // statistically harmless at 32 bits over the time window the
            // network remembers.
            id       = java.security.SecureRandom().nextInt(),
        )
        val frame = MeshtasticMessages.encodeToRadioPacket(packet)
        val ok = client.writeFrame(frame)
        if (!ok) log(LogLevel.WARN, "writeFrame failed for ${message.id.take(8)}…")
    }
}
