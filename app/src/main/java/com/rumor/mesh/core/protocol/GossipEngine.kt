package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.data.ContactDao
import com.rumor.mesh.plugin.PluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "GossipEngine"
private const val DEFAULT_BROADCAST_TTL = 7
/** DMs get a higher initial TTL since they may need more hops to reach a specific recipient. */
private const val DEFAULT_DIRECT_TTL = 15
/**
 * Protocol-wide ceilings enforced on ingress. TTL is unsigned (not covered by the message
 * signature) so a malicious sender can claim any value. One honest hop normalizes it for
 * the rest of the network — every honest node would have to be compromised to amplify.
 */
private const val MAX_BROADCAST_TTL = DEFAULT_BROADCAST_TTL
private const val MAX_DIRECT_TTL = DEFAULT_DIRECT_TTL

/**
 * Core protocol logic. No radio code. No transport types.
 *
 * Inputs
 * ------
 * - [onExchange] — called by the transport layer after each peer exchange
 * - [injectFromPlugin] — called by [PluginRegistry] when a bridge plugin
 *   receives a message from an external network
 *
 * Outputs
 * -------
 * - [incomingMessages] — every new message, consumed by UI and [PluginRegistry]
 * - [messagesForExchange] / [knownMessageIds] — polled by the transport layer
 *   before each gossip session
 */
class GossipEngine(
    private val messageStore: MessageStore,
    private val duplicateFilter: DuplicateFilter,
    private val identityManager: IdentityManager,
    private val onlineStatusTracker: OnlineStatusTracker,
    private val topologyTracker: TopologyTracker,
    private val contactDao: ContactDao,
    /**
     * Consulted only on the inbox emit path, never on the relay path. Encoded
     * structurally: [BlockManager.isBlocked] is called from a single site below
     * and never from [enqueueRelay]. See architecture invariants.
     */
    private val blockManager: com.rumor.mesh.core.block.BlockManager,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sequenceCounter = AtomicLong(System.currentTimeMillis())

    private val pendingRelay = ArrayDeque<RumorMessage>(200)

    /** Every new message ingested from any source. UI and plugins subscribe here. */
    private val _incomingMessages = MutableSharedFlow<RumorMessage>(extraBufferCapacity = 256)
    val incomingMessages: SharedFlow<RumorMessage> = _incomingMessages

    /**
     * Per-message delivery confirmations from peer ACKs. Emits a message ID the moment a
     * direct peer confirms accepting it. Confirms peer-hop delivery only — not end-to-end.
     */
    private val _deliveryEvents = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val deliveryEvents: SharedFlow<String> = _deliveryEvents

    // ── Transport intake ──────────────────────────────────────────────────────

    /**
     * Called by [WifiDirectTransport] after each completed peer exchange.
     * The transport layer has no further involvement — processing is pure logic.
     */
    fun onExchange(result: PeerExchangeResult) {
        scope.launch {
            if (result.peerUserId.isNotEmpty()) {
                topologyTracker.recordSession(result.peerUserId, result.durationMs, hopCount = 1)
                onlineStatusTracker.recordDirectContact(result.peerUserId)
            }
            onlineStatusTracker.mergeRemoteStatus(result.peerOnlineUsers)

            for (id in result.ackedByPeer) _deliveryEvents.emit(id)

            val autoRelayIds = contactDao.getAutoRelayContacts().map { it.userId }.toSet()

            for (msg in result.messagesReceived) {
                val adjusted = msg.copy(receivedAtMs = System.currentTimeMillis())
                processIncoming(adjusted, autoRelayIds)
            }
        }
    }

    // ── Plugin intake ─────────────────────────────────────────────────────────

    /**
     * Called by [PluginRegistry] when a bridge plugin injects a message from
     * an external network. Messages marked [PluginContext.BRIDGE_UNSIGNED] skip
     * signature verification.
     */
    fun injectFromPlugin(message: RumorMessage, sourcePluginId: String) {
        scope.launch {
            RumorLog.d(TAG, "Injecting from plugin $sourcePluginId: ${message.id.take(8)}…")
            val adjusted = message.copy(receivedAtMs = System.currentTimeMillis())
            processIncoming(adjusted, emptySet())
        }
    }

    // ── Compose ───────────────────────────────────────────────────────────────

    /** Build and enqueue a broadcast message composed by the local user. */
    fun composeBroadcast(text: String): RumorMessage? {
        val identity = identityManager.identity.value ?: return null
        val msg = buildMessage(
            identity = identity,
            type = MessageType.BROADCAST,
            ttl = DEFAULT_BROADCAST_TTL,
            payload = MessagePayload(ContentType.TEXT, text),
        )
        enqueueRelay(msg)
        return msg
    }

    /** Build and enqueue a direct (end-to-end encrypted) message. */
    fun composeDirect(recipientId: String, recipientPublicKey: ByteArray, text: String): RumorMessage? {
        val identity = identityManager.identity.value ?: return null
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
        enqueueRelay(msg)
        return msg
    }

    /**
     * User manually relays a message. Bypasses the dedup cache (the whole point is to
     * re-introduce a known message with fresh TTL) and bumps TTL up to the protocol ceiling.
     */
    fun manualRelay(msg: RumorMessage) {
        enqueueRelay(messageStore.boostTtlForManualRelay(msg))
        scope.launch { messageStore.markRelayed(msg.id) }
    }

    // ── Transport supply ──────────────────────────────────────────────────────

    /** Returns pending messages to offer during the next gossip exchange. */
    fun messagesForExchange(): List<RumorMessage> = synchronized(pendingRelay) {
        pendingRelay.toList().also { pendingRelay.clear() }
    }

    fun knownMessageIds(): Set<String> = duplicateFilter.knownIds()

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun processIncoming(rawMsg: RumorMessage, autoRelayIds: Set<String>) {
        val msg = clampTtl(rawMsg)
        val isBridgeMessage = msg.signature == com.rumor.mesh.plugin.PluginContext.BRIDGE_UNSIGNED
        val isNew = if (isBridgeMessage) {
            duplicateFilter.recordAndCheck(msg.id)
        } else {
            messageStore.ingest(msg)
        }
        if (!isNew) return

        emitToInbox(msg)
        relay(msg, autoRelayIds)
    }

    /**
     * Inbox emission — consults [blockManager] to suppress what the user sees from
     * blocked senders. This is the ONLY call site that touches blocklist state.
     * The relay path below is structurally unable to reach it.
     */
    private suspend fun emitToInbox(msg: RumorMessage) {
        if (blockManager.isBlocked(msg.senderId)) return
        _incomingMessages.emit(msg)
    }

    /**
     * Relay path. Never consults blocklist — relay is shared mesh infrastructure;
     * blocking only suppresses what the local user sees. This is the load-bearing
     * architectural rule.
     */
    private fun relay(msg: RumorMessage, autoRelayIds: Set<String>) {
        when (msg.type) {
            MessageType.BROADCAST -> {
                val forwarded = messageStore.decrementTtl(msg) ?: return
                val boosted = if (msg.senderId in autoRelayIds) {
                    messageStore.boostTtlForManualRelay(forwarded)
                } else forwarded
                enqueueRelay(boosted)
            }
            MessageType.DIRECT -> {
                val localId = identityManager.identity.value?.userId
                if (msg.recipientId == localId) return
                val forwarded = messageStore.decrementTtl(msg) ?: return
                enqueueRelay(forwarded)
            }
            MessageType.PING -> {
                val forwarded = msg.copy(ttl = (msg.ttl - 1).coerceAtLeast(0))
                if (forwarded.ttl > 0) enqueueRelay(forwarded)
            }
            MessageType.PONG -> { /* handled by routing */ }
        }
    }

    /** Clamp TTL to the protocol ceiling. Senders can claim anything; receivers refuse to honor more. */
    private fun clampTtl(msg: RumorMessage): RumorMessage {
        val ceiling = when (msg.type) {
            MessageType.BROADCAST, MessageType.PING, MessageType.PONG -> MAX_BROADCAST_TTL
            MessageType.DIRECT -> MAX_DIRECT_TTL
        }
        return if (msg.ttl > ceiling) msg.copy(ttl = ceiling) else msg
    }

    private fun enqueueRelay(msg: RumorMessage) = synchronized(pendingRelay) {
        if (pendingRelay.size >= 200) pendingRelay.removeFirst()
        pendingRelay.addLast(msg)
    }

    private fun buildMessage(
        identity: com.rumor.mesh.core.identity.LocalIdentity,
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
}
