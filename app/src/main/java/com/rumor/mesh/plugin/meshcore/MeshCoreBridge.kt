package com.rumor.mesh.plugin.meshcore

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
 * MeshCore bridge plugin — relays plaintext BROADCAST messages between the
 * Rumor mesh and a MeshCore LoRa radio over BLE.
 *
 * Why this is a plugin
 * --------------------
 * MeshCore is one of several optional external networks. It's load-bearing
 * for users who own a radio, and irrelevant to everyone else. By implementing
 * it as a plugin behind [PluginCatalog], the cost is paid only by users who
 * toggle it on: no permissions requested, no BLE scan running, no battery
 * drain on devices that don't need it.
 *
 * Compartmentalization
 * --------------------
 * This package depends only on:
 *   • Android BLE APIs (necessary for hardware)
 *   • [PluginContext] / [BasePlugin] (the documented plugin surface)
 *   • [RumorMessage] / [MessageType] (the on-wire envelope)
 *
 * It does NOT depend on Room, the gossip engine internals, transport classes,
 * or UI. A bug in this bridge cannot reach into mesh state — at worst it
 * drops a frame.
 *
 * Trust model
 * -----------
 * MeshCore traffic enters the local mesh via [PluginContext.sendMessage]
 * with [PluginContext.BRIDGE_UNSIGNED] as the signature. The gossip engine
 * accepts this from `MessageSource.LOCAL_BRIDGE` only and never re-relays it
 * (BRIDGED trust level). A compromised radio cannot inject signed messages
 * that other Rumor nodes will treat as authentic — bridged traffic stays
 * within one hop of its bridge node.
 *
 * Limits (not implemented)
 * ------------------------
 * • Device picker UI — currently auto-connects to the first scanned MeshCore.
 *   Real deployments with multiple radios need a settings screen to pick.
 * • DM bridging via X25519 ECDH — channel messages only for now.
 * • USB CDC serial transport — BLE only.
 * • Outbound delivery confirmation surfaced to the UI.
 */
class MeshCoreBridge(
    private val androidContext: Context,
) : BasePlugin() {

    override val pluginId    = "bridge.meshcore"
    override val displayName = "MeshCore Bridge"
    override val version     = "0.1.0"

    private var ble: MeshCoreBleClient? = null
    @Volatile private var ready = false

    // Channel index for outbound text — MeshCore primary channel is 0. Surface
    // this in settings later if multi-channel bridging becomes a requirement.
    private val outboundChannelIdx = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onAttach(ctx: PluginContext) {
        super.onAttach(ctx)
        val client = MeshCoreBleClient(androidContext)
        ble = client

        // Outbound: every local mesh BROADCAST gets forwarded to LoRa.
        // We deliberately skip DIRECT / control types — bridging those would
        // either leak DM traffic or pollute the LoRa channel with metadata.
        pluginScope.launch {
            observeIncoming()
                .filter { it.type == MessageType.BROADCAST }
                .filter { it.signature != PluginContext.BRIDGE_UNSIGNED }  // avoid bridge → bridge loops
                .collect { msg -> forwardToRadio(msg) }
        }

        // Inbound: drain BLE frames as they arrive and translate to RumorMessage.
        pluginScope.launch {
            client.inboundFrames.collect { frame -> onFrame(frame) }
        }

        // Auto-discover. A proper UI would let the user pick from multiple
        // results; here we wire the first match and stop scanning to save power.
        client.startScan { device ->
            log(LogLevel.INFO, "Found MeshCore radio: ${device.address}")
            client.stopScan()
            client.connect(device) {
                ready = true
                log(LogLevel.INFO, "MeshCore link ready")
            }
        }
    }

    override fun onDetach() {
        ready = false
        ble?.stopScan()
        ble?.disconnect()
        ble = null
        super.onDetach()
        // Note: pluginScope is cancelled by the host AFTER onDetach returns,
        // so the in-flight collectors above stop cleanly without seeing torn
        // state. Order is: host cancels scope → collectors complete → host
        // discards plugin instance.
    }

    // ── Inbound (radio → mesh) ────────────────────────────────────────────────

    private fun onFrame(frame: ByteArray) {
        if (frame.isEmpty()) return
        when (frame[0]) {
            MeshCoreFrames.RESP_CODE_CHANNEL_MSG_RECV,
            MeshCoreFrames.PUSH_CODE_RAW_DATA,                       // some firmware versions push channel msgs here
            -> handleChannelMessage(frame)

            MeshCoreFrames.PUSH_CODE_MESSAGES_WAITING -> {
                // Radio is telling us it has queued messages we haven't drained.
                // Ask for the next one; the response will land back here as a
                // RESP_CODE_CONTACT_MSG_RECV or RESP_CODE_CHANNEL_MSG_RECV.
                ble?.writeFrame(byteArrayOf(MeshCoreFrames.CMD_SYNC_NEXT_MESSAGE))
            }
            // Other opcodes (advertisements, path updates, login pushes) are
            // either out of scope for a text bridge or already handled by the
            // radio firmware itself. Silently ignored — logging every unknown
            // frame would flood the log under normal operation.
        }
    }

    private fun handleChannelMessage(frame: ByteArray) {
        val msg = MeshCoreFrames.decodeChannelMessage(frame) ?: run {
            log(LogLevel.WARN, "Could not decode channel message (${frame.size} bytes)")
            return
        }
        // Synthesize a stable sender userId from the LoRa display name. Real
        // Ed25519 identity is impossible to bridge — MeshCore uses its own
        // X25519 keys — so we prefix with a sentinel to make it obvious in
        // the UI that the sender is bridged, not native Rumor.
        val syntheticUserId = "meshcore:" + nameHash(msg.senderName)

        val rumor = RumorMessage(
            id              = UUID.randomUUID().toString().replace("-", ""),
            senderId        = syntheticUserId,
            senderPublicKey = "",                          // no Ed25519 — engine sees BRIDGE_UNSIGNED below
            sequenceNumber  = msg.timestampSec,            // radio timestamp = monotonic-ish per sender
            sentAtMs        = msg.timestampSec * 1000L,
            type            = MessageType.BROADCAST,
            // ttl=1: bridged traffic must not propagate further into the mesh.
            // The engine independently enforces BRIDGED trust → no re-relay,
            // but ttl=1 makes it impossible to misread the intent.
            ttl             = 1,
            payload         = MessagePayload(ContentType.TEXT, "[${msg.senderName}] ${msg.text}"),
            signature       = PluginContext.BRIDGE_UNSIGNED,
        )
        sendMessage(rumor, sourceDescription = "meshcore ch${msg.channelIdx}")
    }

    // ── Outbound (mesh → radio) ───────────────────────────────────────────────

    private fun forwardToRadio(message: RumorMessage) {
        if (!ready) return
        val client = ble ?: return
        val text = message.payload?.content ?: return
        val frame = MeshCoreFrames.encodeSendChannelMessage(
            channelIdx = outboundChannelIdx,
            text       = text,
            nowSec     = System.currentTimeMillis() / 1000L,
        )
        val ok = client.writeFrame(frame)
        if (!ok) log(LogLevel.WARN, "writeFrame failed for ${message.id.take(8)}…")
    }

    /**
     * Stable short hash of a sender display name for use as a synthetic userId.
     * Doesn't need to be cryptographic — collisions just mean two MeshCore
     * users with similar names share a Rumor sender ID. The user-visible text
     * is unaffected because we prepend the actual name to every payload.
     */
    private fun nameHash(name: String): String {
        var h = 0x811C9DC5L                                // FNV-1a 32-bit offset basis
        for (b in name.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h = (h * 0x01000193L) and 0xFFFFFFFFL
        }
        return h.toString(16).padStart(8, '0')
    }
}
