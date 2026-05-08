package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire protocol frames exchanged between two nodes during a gossip session. */
@Serializable
sealed class GossipPacket {

    /**
     * Opening handshake. Carries claimed identity plus a random nonce that the
     * peer must sign to prove ownership of [publicKey] (see [HelloProof]).
     */
    @Serializable @SerialName("hello")
    data class Hello(
        val userId: String,
        val publicKey: String,
        /** Base64 random bytes the peer signs in their [HelloProof]. */
        val nonce: String,
        val protocolVersion: Int = 1,
    ) : GossipPacket()

    /**
     * Proof of [Hello.publicKey] ownership.
     * [signature] = Ed25519 sign over the bytes returned by [helloChallengeBytes]
     * — i.e. a domain-tagged binding of the peer's nonce.
     */
    @Serializable @SerialName("hello_proof")
    data class HelloProof(
        val signature: String,
    ) : GossipPacket()

    /** Bloom filter representing this node's known message IDs (Base64-encoded). */
    @Serializable @SerialName("bloom")
    data class Bloom(
        val filter: String,
        val expectedItems: Int,
    ) : GossipPacket()

    /**
     * Raw list of known message IDs. Used in place of [Bloom] when the set is small
     * enough that exact membership beats bloom on size and gives zero false positives.
     * Sender chooses based on a size threshold; receiver handles either form.
     */
    @Serializable @SerialName("id_list")
    data class IdList(
        val ids: List<String>,
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
     * Lightweight delivery acknowledgement sent after the MESSAGE phase.
     * Lists the message IDs the receiver actually accepted and ingested in this
     * session. Only confirms peer-hop delivery (the message left this device and
     * was accepted by a direct peer), not end-to-end delivery to the final recipient.
     */
    @Serializable @SerialName("ack")
    data class Ack(
        val acceptedIds: List<String>,
    ) : GossipPacket()

    /**
     * Recently-seen user IDs shared during gossip exchange.
     * Key = User ID, Value = wall-clock epoch ms when that user was last seen.
     * Devices use their hardware RTC; small clock skew is acceptable for the
     * "online within the last N minutes" granularity this map drives.
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

/** Domain-tagged challenge bytes a peer signs to prove ownership of their public key. */
fun helloChallengeBytes(nonceBase64: String): ByteArray =
    ("rumor-hello-v1:$nonceBase64").toByteArray(Charsets.UTF_8)
