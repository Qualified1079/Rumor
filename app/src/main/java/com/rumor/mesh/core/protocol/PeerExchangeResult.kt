package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.model.RumorMessage

/**
 * Result of a completed peer exchange — the only type that crosses the
 * transport → protocol boundary.
 *
 * [WifiDirectTransport] produces these. [GossipEngine] consumes them.
 * Neither layer knows about the other's internals.
 *
 * Bridge plugins also create these when injecting messages from external
 * networks (LoRa, etc.) — use [source] = [ExchangeSource.PLUGIN].
 */
data class PeerExchangeResult(
    /** User ID of the peer we exchanged with (empty string for plugin-injected messages). */
    val peerUserId: String,
    /** Base64-encoded Ed25519 public key of the peer. */
    val peerPublicKey: String,
    /** Messages we received from the peer. */
    val messagesReceived: List<RumorMessage>,
    /** Count of messages we sent to the peer. */
    val messagesSent: Int,
    /**
     * Online-status snapshot shared by the peer during the exchange.
     * Key = User ID, Value = wall-clock epoch ms when that user was last seen by the peer.
     */
    val peerOnlineUsers: Map<String, Long>,
    /** Message IDs the peer confirmed accepting in this session — peer-hop delivery confirmation. */
    val ackedByPeer: List<String> = emptyList(),
    /** Wall-clock duration of the entire exchange. Used for topology learning. */
    val durationMs: Long,
    /** Where this exchange originated. */
    val source: ExchangeSource = ExchangeSource.WIFI_DIRECT,
)

enum class ExchangeSource {
    /** Normal Wi-Fi Direct gossip session. */
    WIFI_DIRECT,
    /** Message injected by a bridge plugin (Meshtastic, MeshCore, etc.). */
    PLUGIN,
    /** Message composed locally by the user. */
    LOCAL,
}
