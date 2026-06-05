package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.model.RumorMessage

/**
 * Result of a completed peer exchange — the only type that crosses the
 * transport → protocol boundary.
 *
 * The transport layer (e.g. WifiDirectTransport, or the simulator's
 * SimTransport) produces these; [GossipEngine] consumes them. Neither layer
 * knows about the other's internals.
 */
data class PeerExchangeResult(
    /** User ID of the peer we exchanged with. Empty for non-peer sources. */
    val peerUserId: String,
    /** Messages we received from the peer. */
    val messagesReceived: List<RumorMessage>,
    /** Message IDs the peer confirmed accepting — peer-hop delivery confirmation. */
    val ackedByPeer: List<String>,
    /**
     * Online-status snapshot shared by the peer during the exchange.
     * Key = User ID, value = wall-clock epoch ms when the peer last saw that user.
     */
    val peerOnlineUsers: Map<String, Long>,
    /** Wall-clock duration of the entire exchange. Used for topology learning. */
    val durationMs: Long,
    /** Base64-encoded Ed25519 public key of the peer. Empty when not applicable. */
    val peerPublicKey: String = "",
    /** Count of messages we sent to the peer in this exchange. */
    val messagesSent: Int = 0,
    /** Where this exchange originated. */
    val source: ExchangeSource = ExchangeSource.WIFI_DIRECT,
    /**
     * Total bytes transferred in both directions during this exchange.
     * Used for throughput-based peer ranking in [TopologyTracker].
     */
    val bytesTransferred: Long = 0,
    /**
     * Fraction of our outbound offer that the peer already knew (0.0–1.0).
     * 0.0 = peer was completely uninformed; 1.0 = full overlap, no novel data.
     * Propagated to [NeighborStore] for diversity-aware peer selection.
     */
    val peerOverlapFraction: Float = 0.5f,
    /**
     * O76 / capability cache — peer's HELLO `supportedFeatures` from this
     * exchange. Consumer caches into `Contact.lastKnownSupportedFeatures`
     * so future compose paths can gate on per-feature support without a
     * fresh handshake. Empty for non-WIFI_DIRECT sources.
     */
    val peerSupportedFeatures: List<String> = emptyList(),
)

enum class ExchangeSource {
    /** Normal Wi-Fi Direct gossip session. */
    WIFI_DIRECT,
    /** Message injected by a bridge plugin (Meshtastic, MeshCore, etc.). */
    PLUGIN,
    /** Message composed locally by the user. */
    LOCAL,
}
