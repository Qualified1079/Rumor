package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.platform.AtomicCounter
import com.rumor.mesh.core.platform.Uuid
import com.rumor.mesh.core.protocol.GossipEngine.Companion.DEFAULT_BROADCAST_HOPS
import com.rumor.mesh.core.protocol.GossipEngine.Companion.DEFAULT_DIRECT_HOPS
import com.rumor.mesh.core.scheduler.Scheduler
import com.rumor.mesh.core.wire.withHlc
import com.rumor.mesh.core.wire.withMentions
import com.rumor.mesh.core.wire.withReplyTo
import com.rumor.mesh.core.wire.withRoomPostingCert
import com.rumor.mesh.core.wire.withRoomRoutingTag

internal class OutboundComposer(
    private val messageStore: MessageStore,
    private val identityProvider: IdentityProvider,
    private val scheduler: Scheduler,
    private val canaryMetrics: CanaryMetrics,
    private val clock: com.rumor.mesh.core.Clock,
    private val hlc: com.rumor.mesh.core.time.HlcClock,
) {
    private val sequenceCounter = AtomicCounter(clock.now())

    fun composeOutbound(
        type: MessageType,
        payload: MessagePayload,
        recipientId: String? = null,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val hopsToLive = if (type == MessageType.BROADCAST || recipientId == null) {
            DEFAULT_BROADCAST_HOPS
        } else {
            DEFAULT_DIRECT_HOPS
        }
        val msg = buildMessage(
            identity = identity,
            type = type,
            hopsToLive = hopsToLive,
            payload = payload,
            recipientId = recipientId,
        )
        enqueueImmediate(msg)
        return msg
    }

    /** Locally-composed messages bypass the batcher — no jitter for the sender. */
    fun enqueueImmediate(msg: RumorMessage) {
        scheduler.enqueue(msg)
        canaryMetrics.publish(scheduler.queueDepth)
    }

    fun buildMessage(
        identity: LocalIdentity,
        type: MessageType,
        hopsToLive: Int,
        payload: MessagePayload? = null,
        encryptedPayload: String? = null,
        recipientId: String? = null,
        roomRoutingTag: String? = null,
        roomPostingCert: com.rumor.mesh.core.model.RoomPostingCert? = null,
    ): RumorMessage {
        val base = RumorMessage(
            id = Uuid.randomHex32(),
            senderId = identity.userId,
            senderPublicKey = identity.publicKeyBytes.toBase64(),
            sequenceNumber = sequenceCounter.getAndIncrement(),
            sentAtMs = clock.now(),
            type = type,
            hopsToLive = hopsToLive,
            payload = payload,
            encryptedPayload = encryptedPayload,
            recipientId = recipientId,
            signature = "",
        )
        // O157: the room routing tag must be present BEFORE signing so
        // signableBytes covers it (it rides _ext.rt, which is otherwise
        // stamped post-sign and thus forgeable). Everything else in _ext
        // (HLC, thread/mention) stays unsigned and is applied after.
        // O89 rides here for the same reason as O157's tag: both are covered by
        // the rumor-msg-v3 transcript, so both must be present before signing.
        val tagged = if (roomRoutingTag != null) base.withRoomRoutingTag(roomRoutingTag) else base
        val unsigned = if (roomPostingCert != null) tagged.withRoomPostingCert(roomPostingCert) else tagged
        val sig = CryptoManager.sign(messageStore.signableBytes(unsigned), identity.privateKeyBytes).toBase64()
        // O95: HLC stamp rides _ext (unsigned, like every _ext field) so it can
        // be applied after signing without invalidating the sig.
        return unsigned.copy(signature = sig).withHlc(hlc.tick())
    }
}

internal fun RumorMessage.applyThreadAndMentionExt(
    replyTo: String?,
    mentions: List<String>,
): RumorMessage {
    var result = this
    if (replyTo != null) result = result.withReplyTo(replyTo)
    if (mentions.isNotEmpty()) result = result.withMentions(mentions)
    return result
}
