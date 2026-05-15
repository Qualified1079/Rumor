package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.ChunkRequest
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.TrustLevel
import com.rumor.mesh.core.policy.InboxFilter
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.scheduler.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "GossipEngine"
private const val DEFAULT_BROADCAST_TTL = 7
private const val DEFAULT_DIRECT_TTL = 15
private const val MAX_BROADCAST_TTL = DEFAULT_BROADCAST_TTL
private const val MAX_DIRECT_TTL = DEFAULT_DIRECT_TTL

/**
 * Core protocol logic. No radio code. No transport types.
 *
 * Inputs
 * ------
 * - [onExchange] — called by the transport layer after each peer exchange
 * - [onExchangeFailed] — called when a session failed (for canary metrics)
 * - [injectFromPlugin] — called by PluginRegistry when a bridge plugin
 *   receives a message from an external network
 *
 * Outputs
 * -------
 * - [incomingMessages] — every new message, consumed by UI and PluginRegistry
 * - [messagesForExchange] / [knownMessageIds] — polled by the transport layer
 *   before each gossip session
 *
 * Relay path uses [RelayBatcher]: received messages are held for a random
 * 100–500ms window before being queued. Locally-composed messages bypass the
 * batcher and go directly to the scheduler so the sender doesn't feel the delay.
 */
class GossipEngine(
    private val messageStore: MessageStore,
    private val duplicateFilter: DuplicateFilter,
    private val identityProvider: IdentityProvider,
    private val onlineStatusTracker: OnlineStatusTracker,
    private val topologyTracker: TopologyTracker,
    private val contactRepo: ContactRepository,
    /**
     * Consulted only on the inbox emit path, never on the relay path. Encoded
     * structurally: [BlockManager.isBlocked] is called from a single site below
     * and never from the relay path. See architecture invariants.
     */
    private val blockManager: BlockManager,
    private val scheduler: Scheduler,
    /**
     * User-configurable inbox filter. Consulted only on the inbox emit path,
     * never on the relay path — same architectural rule as [blockManager].
     */
    private val inboxFilter: InboxFilter,
    val canaryMetrics: CanaryMetrics = CanaryMetrics(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    private val sequenceCounter = AtomicLong(System.currentTimeMillis())

    private val _incomingMessages = MutableSharedFlow<RumorMessage>(extraBufferCapacity = 256)
    val incomingMessages: SharedFlow<RumorMessage> = _incomingMessages

    private val _deliveryEvents = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val deliveryEvents: SharedFlow<String> = _deliveryEvents

    /** Relayed messages are buffered here before being committed to the scheduler. */
    private val relayBatcher = RelayBatcher(scope) { batch ->
        batch.forEach { scheduler.enqueue(it) }
        canaryMetrics.publish(scheduler.queueDepth)
    }

    // ── Transport intake ──────────────────────────────────────────────────────

    fun onExchange(result: PeerExchangeResult) {
        scope.launch {
            if (result.peerUserId.isNotEmpty()) {
                topologyTracker.recordSession(
                    peerId           = result.peerUserId,
                    latencyMs        = result.durationMs,
                    hopCount         = 1,
                    bytesTransferred = result.bytesTransferred,
                    overlapFraction  = result.peerOverlapFraction,
                )
                onlineStatusTracker.recordDirectContact(result.peerUserId)
            }
            onlineStatusTracker.mergeRemoteStatus(result.peerOnlineUsers)

            canaryMetrics.recordExchange(success = true, rttMs = result.durationMs)

            for (id in result.ackedByPeer) _deliveryEvents.emit(id)

            val autoRelayIds = contactRepo.getAutoRelayContacts().map { it.userId }.toSet()

            for (msg in result.messagesReceived) {
                val adjusted = msg.copy(receivedAtMs = System.currentTimeMillis())
                processIncoming(adjusted, MessageSource.PEER, autoRelayIds)
            }
        }
    }

    fun onExchangeFailed(peerId: String) {
        scope.launch {
            canaryMetrics.recordExchange(success = false, rttMs = 0)
            RumorLog.d(TAG, "Exchange failed with ${peerId.take(8)}…")
        }
    }

    // ── Plugin intake ─────────────────────────────────────────────────────────

    fun injectFromPlugin(message: RumorMessage, sourcePluginId: String) {
        scope.launch {
            RumorLog.d(TAG, "Injecting from plugin $sourcePluginId: ${message.id.take(8)}…")
            val adjusted = message.copy(receivedAtMs = System.currentTimeMillis())
            processIncoming(adjusted, MessageSource.LOCAL_BRIDGE, emptySet())
        }
    }

    // ── Compose ───────────────────────────────────────────────────────────────

    fun composeBroadcast(text: String): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val msg = buildMessage(
            identity = identity,
            type = MessageType.BROADCAST,
            ttl = DEFAULT_BROADCAST_TTL,
            payload = MessagePayload(ContentType.TEXT, text),
        )
        enqueueImmediate(msg)
        return msg
    }

    fun composeDirect(recipientId: String, recipientPublicKey: ByteArray, text: String): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val ephemeral = CryptoManager.generateX25519KeyPair()
        val sharedKey = CryptoManager.x25519Agreement(ephemeral.privateKeyBytes, recipientPublicKey)
        val ct = CryptoManager.aesGcmEncrypt(text.toByteArray(Charsets.UTF_8), sharedKey)
        val encryptedPayload = ephemeral.publicKeyBytes.toBase64() + "." + ct.toBase64()
        val msg = buildMessage(
            identity = identity,
            type = MessageType.DIRECT,
            ttl = DEFAULT_DIRECT_TTL,
            encryptedPayload = encryptedPayload,
            recipientId = recipientId,
        )
        enqueueImmediate(msg)
        return msg
    }

    fun manualRelay(msg: RumorMessage) {
        enqueueImmediate(messageStore.boostTtlForManualRelay(msg))
        scope.launch { messageStore.markRelayed(msg.id) }
    }

    fun composeChunkRequest(
        transferId: String,
        missingIndices: List<Int>,
        originalSenderId: String,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val body = Json.encodeToString(ChunkRequest(transferId, missingIndices))
        val msg = buildMessage(
            identity = identity,
            type = MessageType.CHUNK_REQUEST,
            ttl = DEFAULT_DIRECT_TTL,
            payload = MessagePayload(ContentType.CONTROL, body),
            recipientId = originalSenderId,
        )
        enqueueImmediate(msg)
        return msg
    }

    fun composeOutbound(
        type: MessageType,
        payload: MessagePayload,
        recipientId: String? = null,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val ttl = if (type == MessageType.BROADCAST || recipientId == null) {
            DEFAULT_BROADCAST_TTL
        } else {
            DEFAULT_DIRECT_TTL
        }
        val msg = buildMessage(
            identity = identity,
            type = type,
            ttl = ttl,
            payload = payload,
            recipientId = recipientId,
        )
        enqueueImmediate(msg)
        return msg
    }

    /**
     * Request a mutual priority (persistent) link with [recipientId].
     * The recipient receives the request in their inbox and can accept via
     * [acceptPriorityLink].
     */
    fun composePriorityLinkRequest(recipientId: String): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val msg = buildMessage(
            identity = identity,
            type = MessageType.PRIORITY_LINK_REQUEST,
            ttl = DEFAULT_DIRECT_TTL,
            payload = MessagePayload(ContentType.CONTROL, ""),
            recipientId = recipientId,
        )
        enqueueImmediate(msg)
        return msg
    }

    /**
     * Accept an incoming [PRIORITY_LINK_REQUEST]. Marks the sender as a
     * priority peer locally and sends [PRIORITY_LINK_ACCEPT] back to them so
     * they can do the same on their side.
     */
    fun acceptPriorityLink(request: RumorMessage) {
        if (request.type != MessageType.PRIORITY_LINK_REQUEST) return
        scope.launch {
            contactRepo.setPriorityPeer(request.senderId, true)
            RumorLog.i(TAG, "Accepted priority link from ${request.senderId.take(8)}…")
        }
        val identity = identityProvider.identity.value ?: return
        val reply = buildMessage(
            identity = identity,
            type = MessageType.PRIORITY_LINK_ACCEPT,
            ttl = DEFAULT_DIRECT_TTL,
            payload = MessagePayload(ContentType.CONTROL, ""),
            recipientId = request.senderId,
        )
        enqueueImmediate(reply)
    }

    // ── Transport supply ──────────────────────────────────────────────────────

    fun messagesForExchange(): List<RumorMessage> = scheduler.take(200)
    fun knownMessageIds(): Set<String> = duplicateFilter.knownIds()
    val queueDepth: Int get() = scheduler.queueDepth

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun processIncoming(
        rawMsg: RumorMessage,
        source: MessageSource,
        autoRelayIds: Set<String>,
    ) {
        val clamped = clampTtl(rawMsg)
        // Per-transport trust gate. The BRIDGE_UNSIGNED sentinel is honored only
        // for messages handed in by a local bridge plugin — a network peer cannot
        // forge it to skip Ed25519 verification.
        val bridged = source == MessageSource.LOCAL_BRIDGE && clamped.signature == BRIDGE_UNSIGNED
        val sigFailuresBefore = messageStore.sigFailureCount
        val isNew = if (bridged) {
            duplicateFilter.recordAndCheck(clamped.id)
        } else {
            messageStore.ingest(clamped)
        }
        if (!bridged && !isNew && messageStore.sigFailureCount > sigFailuresBefore) {
            canaryMetrics.recordSigFailure()
        }
        canaryMetrics.recordIncoming(isDuplicate = !isNew)
        if (!isNew) return

        val msg = clamped.copy(
            trustLevel = if (bridged) TrustLevel.BRIDGED else TrustLevel.VERIFIED,
        )

        // Auto-accept incoming priority link acceptance: mark the sender as priority peer.
        if (msg.type == MessageType.PRIORITY_LINK_ACCEPT &&
            msg.recipientId == identityProvider.identity.value?.userId) {
            scope.launch { contactRepo.setPriorityPeer(msg.senderId, true) }
        }

        emitToInbox(msg)
        relay(msg, autoRelayIds)
    }

    /**
     * Inbox emission. The ONLY call site that touches blocklist state.
     * The relay path below is structurally unable to reach it.
     */
    private suspend fun emitToInbox(msg: RumorMessage) {
        if (blockManager.isBlocked(msg.senderId)) return
        if (!inboxFilter.allowsInbox(msg)) return
        _incomingMessages.emit(msg)
    }

    /**
     * Relay path. Never consults blocklist — relay is shared mesh infrastructure;
     * blocking only suppresses what the local user sees.
     *
     * Relayed messages are handed to [RelayBatcher] (100–500ms random window)
     * rather than the scheduler directly, for timing correlation resistance.
     */
    private fun relay(msg: RumorMessage, autoRelayIds: Set<String>) {
        // Unsigned bridge traffic is never re-relayed onto the signed mesh: peers
        // reject unverifiable messages anyway, and re-signing here would launder a
        // foreign message into a vouch this node never made.
        if (msg.trustLevel == TrustLevel.BRIDGED) return
        when (msg.type) {
            MessageType.BROADCAST -> {
                val forwarded = messageStore.decrementTtl(msg) ?: return
                val boosted = if (msg.senderId in autoRelayIds) {
                    messageStore.boostTtlForManualRelay(forwarded)
                } else forwarded
                enqueueRelayed(boosted)
            }
            MessageType.DIRECT -> {
                val localId = identityProvider.identity.value?.userId
                if (msg.recipientId == localId) return
                val forwarded = messageStore.decrementTtl(msg) ?: return
                enqueueRelayed(forwarded)
            }
            MessageType.PING -> {
                val forwarded = msg.copy(ttl = (msg.ttl - 1).coerceAtLeast(0))
                if (forwarded.ttl > 0) enqueueRelayed(forwarded)
            }
            MessageType.PONG -> { /* handled by routing */ }
            MessageType.TRANSFER_METADATA, MessageType.CHUNK -> {
                val localId = identityProvider.identity.value?.userId
                if (msg.recipientId == null || msg.recipientId == localId) return
                val forwarded = messageStore.decrementTtl(msg) ?: return
                enqueueRelayed(forwarded)
            }
            MessageType.CHUNK_REQUEST -> {
                val localId = identityProvider.identity.value?.userId
                if (msg.recipientId == localId) return
                val forwarded = messageStore.decrementTtl(msg) ?: return
                enqueueRelayed(forwarded)
            }
            MessageType.BLOCKLIST_PUBLISH, MessageType.BLOCKLIST_DIFF -> {
                val forwarded = messageStore.decrementTtl(msg) ?: return
                enqueueRelayed(forwarded)
            }
            MessageType.PRIORITY_LINK_REQUEST, MessageType.PRIORITY_LINK_ACCEPT -> {
                val localId = identityProvider.identity.value?.userId
                if (msg.recipientId == localId) return
                val forwarded = messageStore.decrementTtl(msg) ?: return
                enqueueRelayed(forwarded)
            }
        }
    }

    private fun clampTtl(msg: RumorMessage): RumorMessage {
        val ceiling = when (msg.type) {
            MessageType.BROADCAST, MessageType.PING, MessageType.PONG,
            MessageType.BLOCKLIST_PUBLISH, MessageType.BLOCKLIST_DIFF -> MAX_BROADCAST_TTL
            MessageType.DIRECT, MessageType.TRANSFER_METADATA,
            MessageType.CHUNK, MessageType.CHUNK_REQUEST,
            MessageType.PRIORITY_LINK_REQUEST,
            MessageType.PRIORITY_LINK_ACCEPT -> MAX_DIRECT_TTL
        }
        return if (msg.ttl > ceiling) msg.copy(ttl = ceiling) else msg
    }

    /** Locally-composed messages bypass the batcher — no jitter for the sender. */
    private fun enqueueImmediate(msg: RumorMessage) {
        scheduler.enqueue(msg)
        canaryMetrics.publish(scheduler.queueDepth)
    }

    /** Relayed messages go through the batcher for timing correlation resistance. */
    private fun enqueueRelayed(msg: RumorMessage) {
        relayBatcher.add(msg)
        canaryMetrics.recordRelay()
    }

    private fun buildMessage(
        identity: LocalIdentity,
        type: MessageType,
        ttl: Int,
        payload: MessagePayload? = null,
        encryptedPayload: String? = null,
        recipientId: String? = null,
    ): RumorMessage {
        val unsigned = RumorMessage(
            id = UUID.randomUUID().toString().replace("-", ""),
            senderId = identity.userId,
            senderPublicKey = identity.publicKeyBytes.toBase64(),
            sequenceNumber = sequenceCounter.getAndIncrement(),
            sentAtMs = System.currentTimeMillis(),
            type = type,
            ttl = ttl,
            payload = payload,
            encryptedPayload = encryptedPayload,
            recipientId = recipientId,
            signature = "",
        )
        val sig = CryptoManager.sign(messageStore.signableBytes(unsigned), identity.privateKeyBytes).toBase64()
        return unsigned.copy(signature = sig)
    }

    companion object {
        /**
         * Sentinel placed in [RumorMessage.signature] by bridge plugins for
         * messages carried in from a non-Rumor network. Honored only when the
         * message arrives via [injectFromPlugin] ([MessageSource.LOCAL_BRIDGE]);
         * over a peer transport it is treated as an invalid signature and the
         * message is dropped.
         */
        const val BRIDGE_UNSIGNED = "bridge_unsigned"
    }
}

/** Ingress transport a message arrived on — determines its trust gate. */
private enum class MessageSource {
    /** Received from a network peer over a Rumor gossip exchange. */
    PEER,
    /** Handed in by a local bridge plugin from an external network. */
    LOCAL_BRIDGE,
}
