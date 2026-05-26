package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.identity.LocalIdentity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Produces synthetic [RumorMessage]s from a [SimNode]'s identity according to
 * a [TrafficProfile]. Called by [SimWorld] on each tick to inject local traffic.
 */
class MessageGenerator(
    private val identity: LocalIdentity,
    private val profile: TrafficProfile,
) {
    private val seq = AtomicLong(System.currentTimeMillis())

    fun generate(rng: Random): RumorMessage? {
        if (rng.nextDouble() >= profile.msgPerSecond / profile.ticksPerSecond) return null

        val payloadBytes = profile.samplePayloadSize(rng)
        val contentType  = profile.sampleContentType(rng)
        val content      = "x".repeat(payloadBytes)   // synthetic body

        val unsigned = RumorMessage(
            id               = UUID.randomUUID().toString().replace("-", ""),
            senderId         = identity.userId,
            senderPublicKey  = identity.publicKeyBytes.toBase64(),
            sequenceNumber   = seq.getAndIncrement(),
            sentAtMs         = System.currentTimeMillis(),
            type             = MessageType.BROADCAST,
            hopsToLive              = 7,
            payload          = MessagePayload(contentType, content),
            signature        = "",
        )
        val sig = CryptoManager.sign(signableBytes(unsigned), identity.privateKeyBytes).toBase64()
        return unsigned.copy(signature = sig)
    }

    private fun signableBytes(msg: RumorMessage): ByteArray = buildString {
        append(msg.id); append(msg.senderId); append(msg.senderPublicKey)
        append(msg.sequenceNumber); append(msg.sentAtMs); append(msg.type.name)
        append(msg.hopsToLive); append(msg.payload?.content ?: "")
        append(msg.encryptedPayload ?: ""); append(msg.recipientId ?: "")
    }.toByteArray(Charsets.UTF_8)
}

/**
 * Parameters controlling what a node sends and how often.
 * All values are mutable so the dashboard can adjust them live.
 */
data class TrafficProfile(
    /** Average messages generated per second per node. */
    @Volatile var msgPerSecond:   Double = 0.5,
    /** Sim ticks per second (controls granularity of the Poisson check). */
    val ticksPerSecond:           Int    = 10,
    /** Payload size distribution bounds in bytes. */
    @Volatile var minPayloadBytes: Int = 50,
    @Volatile var maxPayloadBytes: Int = 500,
    /** Probability of a burst this tick — generates [burstMultiplier] messages at once. */
    @Volatile var burstProbability: Double = 0.01,
    @Volatile var burstMultiplier:  Int    = 5,
    /** Content-type weights. Index = ContentType ordinal. Normalized at sampling time. */
    @Volatile var contentTypeWeights: DoubleArray = doubleArrayOf(0.7, 0.1, 0.1, 0.1, 0.0),
) {
    fun samplePayloadSize(rng: Random): Int =
        rng.nextInt(minPayloadBytes, maxPayloadBytes + 1)

    fun sampleContentType(rng: Random): ContentType {
        val total = contentTypeWeights.sum().takeIf { it > 0 } ?: 1.0
        var r = rng.nextDouble() * total
        for ((i, w) in contentTypeWeights.withIndex()) {
            r -= w
            if (r <= 0) return ContentType.entries[i]
        }
        return ContentType.TEXT
    }
}
