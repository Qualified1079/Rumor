package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire protocol frames exchanged between two nodes during a gossip session. */
@Serializable
sealed class GossipPacket {

    /** Opening handshake — establishes identity for this session. */
    @Serializable @SerialName("hello")
    data class Hello(
        val userId: String,
        val publicKey: String,
        val protocolVersion: Int = 1,
    ) : GossipPacket()

    /** Bloom filter representing this node's known message IDs (Base64-encoded). */
    @Serializable @SerialName("bloom")
    data class Bloom(
        val filter: String,
        val expectedItems: Int,
    ) : GossipPacket()

    /** Request specific messages by ID. */
    @Serializable @SerialName("request")
    data class Request(
        val messageIds: List<String>,
    ) : GossipPacket()

    /** A single message being transferred. */
    @Serializable @SerialName("message")
    data class Message(
        val message: RumorMessage,
    ) : GossipPacket()

    /**
     * Recently-seen user IDs shared during gossip exchange.
     * Key = User ID, Value = elapsed ms since last seen (not an epoch — stays clock-agnostic).
     */
    @Serializable @SerialName("online_status")
    data class OnlineStatus(
        val recentUsers: Map<String, Long>,
    ) : GossipPacket()

    /** Graceful disconnect signal. */
    @Serializable @SerialName("bye")
    data class Bye(
        val reason: String = "",
    ) : GossipPacket()
}
