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

    /**
     * Returns how many messages this node should originate on a tick that
     * represents [simSecondsPerTick] of sim time. The integer part is emitted
     * deterministically; the fractional part is a coin toss. This lets the
     * caller stay correct as sim speed changes — at speedMult=10 a tick is
     * 1.0 sim-sec, so msgPerSecond=2.0 means 2 messages per tick on average.
     */
    fun messagesThisTick(rng: Random, simSecondsPerTick: Double): Int {
        val expected = profile.msgPerSecond * simSecondsPerTick
        val whole = expected.toInt()
        val frac = expected - whole
        return whole + (if (rng.nextDouble() < frac) 1 else 0)
    }

    fun generate(rng: Random): RumorMessage {
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
            hopsToLive       = profile.hopsToLive,
            payload          = MessagePayload(contentType, content),
            signature        = "",
        )
        val sig = CryptoManager.sign(sbStore.signableBytes(unsigned), identity.privateKeyBytes).toBase64()
        return unsigned.copy(signature = sig)
    }

    // O144: delegate to the REAL MessageStore.signableBytes rather than a hand-
    // copied duplicate (which silently went stale at the v1→v2 cutover and made
    // every generated message fail verification). Throwaway store; the method is
    // pure over the message.
    private val sbStore = com.rumor.mesh.core.protocol.MessageStore(
        com.rumor.mesh.core.data.memory.InMemoryMessageRepository(),
        com.rumor.mesh.core.data.memory.InMemoryContactRepository(),
        com.rumor.mesh.core.protocol.DuplicateFilter(),
    )
}

/**
 * Parameters controlling what a node sends and how often.
 * All values are mutable so the dashboard can adjust them live.
 */
data class TrafficProfile(
    /** Average messages generated per second per node. */
    @Volatile var msgPerSecond:   Double = 0.5,
    /** Payload size distribution bounds in bytes. */
    @Volatile var minPayloadBytes: Int = 50,
    @Volatile var maxPayloadBytes: Int = 500,
    @Volatile var hopsToLive:      Int = 7,
    /** Fraction (0..1) of generated traffic that's DM rather than BROADCAST. */
    @Volatile var dmFraction:      Double = 0.0,
    /**
     * Fraction (0..1) of broadcasts that should exceed the chunk threshold.
     * Large messages are split by the real Chunker on the sender and reassembled
     * by the recipient's [ChunkManager] — same code path as the app.
     */
    @Volatile var largeMessageFraction: Double = 0.0,
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
