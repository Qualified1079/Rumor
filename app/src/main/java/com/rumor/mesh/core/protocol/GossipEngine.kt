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
import com.rumor.mesh.core.transport.wifidirect.GossipSession
import com.rumor.mesh.data.ContactDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GossipEngine"
private const val DEFAULT_BROADCAST_TTL = 7

/**
 * Core protocol logic. No radio code — operates entirely on [GossipSession.SessionResult]s
 * delivered by the transport layer.
 */
@Singleton
class GossipEngine @Inject constructor(
    private val messageStore: MessageStore,
    private val duplicateFilter: DuplicateFilter,
    private val identityManager: IdentityManager,
    private val onlineStatusTracker: OnlineStatusTracker,
    private val topologyTracker: TopologyTracker,
    private val contactDao: ContactDao,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sequenceCounter = AtomicLong(System.currentTimeMillis())

    /** Messages ready to be relayed out on the next gossip exchange. */
    private val pendingRelay = ArrayDeque<RumorMessage>(200)

    /** Emits every newly ingested message (for UI updates and plugin callbacks). */
    private val _incomingMessages = MutableSharedFlow<RumorMessage>(extraBufferCapacity = 256)
    val incomingMessages: SharedFlow<RumorMessage> = _incomingMessages

    // ── Session intake ────────────────────────────────────────────────────────

    /** Called by MeshService when a [GossipSession] completes. */
    fun onSessionResult(result: GossipSession.SessionResult) {
        scope.launch {
            // Update topology and online status
            topologyTracker.recordSession(result.peerUserId, result.durationMs, hopCount = 1)
            onlineStatusTracker.recordDirectContact(result.peerUserId)
            onlineStatusTracker.mergeRemoteStatus(result.peerOnlineUsers)

            // Auto-relay check: if peer's user is in our auto-relay contacts, extend their reach
            val autoRelayIds = contactDao.getAutoRelayContacts().map { it.userId }.toSet()

            for (msg in result.messagesReceived) {
                val adjusted = msg.copy(
                    elapsedMs = msg.elapsedMs + result.durationMs,
                    receivedAtMs = System.currentTimeMillis(),
                )
                val isNew = messageStore.ingest(adjusted)
                if (!isNew) continue

                _incomingMessages.emit(adjusted)

                when (adjusted.type) {
                    MessageType.BROADCAST -> {
                        val forwarded = messageStore.decrementTtl(adjusted)
                        if (forwarded != null) {
                            val shouldAutoRelay = adjusted.senderId in autoRelayIds
                            if (shouldAutoRelay) {
                                enqueueRelay(messageStore.resetTtl(forwarded))
                            } else {
                                enqueueRelay(forwarded)
                            }
                        }
                    }
                    MessageType.DIRECT -> {
                        // DMs have no TTL — keep relaying until delivered
                        val localUserId = identityManager.identity.value?.userId
                        if (adjusted.recipientId != localUserId) {
                            enqueueRelay(adjusted)
                        }
                    }
                    MessageType.PING -> handlePing(adjusted)
                    MessageType.PONG -> { /* handled by routing */ }
                }
            }
        }
    }

    // ── Compose and send ──────────────────────────────────────────────────────

    fun composeBroadcast(text: String): RumorMessage? {
        val identity = identityManager.identity.value ?: return null
        val msg = RumorMessage(
            id = UUID.randomUUID().toString().replace("-", ""),
            senderId = identity.userId,
            senderPublicKey = identity.publicKeyBytes.toBase64(),
            sequenceNumber = sequenceCounter.getAndIncrement(),
            elapsedMs = 0,
            type = MessageType.BROADCAST,
            ttl = DEFAULT_BROADCAST_TTL,
            payload = MessagePayload(ContentType.TEXT, text),
            signature = "",  // filled below
        ).let { it.copy(signature = sign(it, identity.privateKeyBytes)) }

        enqueueRelay(msg)
        return msg
    }

    fun composeDirect(recipientId: String, recipientPublicKey: ByteArray, text: String): RumorMessage? {
        val identity = identityManager.identity.value ?: return null

        // AES key from X25519 DH
        val ephemeralPair = CryptoManager.generateX25519KeyPair()
        val sharedKey = CryptoManager.x25519Agreement(ephemeralPair.privateKeyBytes, recipientPublicKey)
        val ct = CryptoManager.aesGcmEncrypt(text.toByteArray(Charsets.UTF_8), sharedKey)

        // Payload = ephemeral public key + ciphertext (recipient needs ephemeral pub to recompute DH)
        val encryptedPayload = (ephemeralPair.publicKeyBytes.toBase64() + "." + ct.toBase64())

        val msg = RumorMessage(
            id = UUID.randomUUID().toString().replace("-", ""),
            senderId = identity.userId,
            senderPublicKey = identity.publicKeyBytes.toBase64(),
            sequenceNumber = sequenceCounter.getAndIncrement(),
            elapsedMs = 0,
            type = MessageType.DIRECT,
            ttl = 0,  // DMs have no TTL
            encryptedPayload = encryptedPayload,
            recipientId = recipientId,
            signature = "",
        ).let { it.copy(signature = sign(it, identity.privateKeyBytes)) }

        enqueueRelay(msg)
        return msg
    }

    /** User manually relays a message — reset TTL so it spreads further. */
    fun manualRelay(msg: RumorMessage) {
        enqueueRelay(messageStore.resetTtl(msg))
        scope.launch { messageStore.markRelayed(msg.id) }
    }

    // ── Message supply for transport ──────────────────────────────────────────

    /** Returns messages to offer during the next gossip exchange. */
    fun messagesForExchange(): List<RumorMessage> {
        return synchronized(pendingRelay) {
            pendingRelay.toList().also { pendingRelay.clear() }
        }
    }

    fun knownMessageIds(): Set<String> = duplicateFilter.knownIds()

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun enqueueRelay(msg: RumorMessage) {
        synchronized(pendingRelay) {
            if (pendingRelay.size >= 200) pendingRelay.removeFirst()
            pendingRelay.addLast(msg)
        }
    }

    private fun handlePing(ping: RumorMessage) {
        // PONG routing handled by routing engine; gossip engine just relays
        enqueueRelay(ping.copy(ttl = ping.ttl - 1).takeIf { it.ttl > 0 } ?: return)
    }

    private fun sign(msg: RumorMessage, privateKeyBytes: ByteArray): String {
        val bytes = messageStore.signableBytes(msg)
        return CryptoManager.sign(bytes, privateKeyBytes).toBase64()
    }
}
