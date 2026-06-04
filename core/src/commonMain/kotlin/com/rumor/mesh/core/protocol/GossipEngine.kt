package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.IdentityRotationPayload
import com.rumor.mesh.core.model.BridgeVouchedPayload
import com.rumor.mesh.core.model.SelfPresencePayload
import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.model.identityRotationSignableBytes
import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.ChunkRequest
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.MAX_TOTAL_HOPS
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.TrustLevel
import com.rumor.mesh.core.model.floodedHops
import com.rumor.mesh.core.model.routedHops
import com.rumor.mesh.core.model.withTtlSplit
import com.rumor.mesh.core.policy.InboxFilter
import com.rumor.mesh.core.routing.BreadcrumbCache
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.scheduler.Scheduler
import com.rumor.mesh.plugin.BridgedDmOutbound
import com.rumor.mesh.plugin.DmEnvelopeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import com.rumor.mesh.core.platform.Uuid
import com.rumor.mesh.core.platform.ConcurrentMap
import com.rumor.mesh.core.platform.AtomicCounter
import com.rumor.mesh.core.wire.WireJson

private const val TAG = "GossipEngine"
private const val DEFAULT_BROADCAST_HOPS = 7
private const val DEFAULT_DIRECT_HOPS = 15
private const val MAX_BROADCAST_HOPS = DEFAULT_BROADCAST_HOPS
private const val MAX_DIRECT_HOPS = DEFAULT_DIRECT_HOPS

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
    /**
     * Optional Tier-1 routing substrate (O29). When non-null, records a
     * breadcrumb per inbound signed message so future DMs to that sender
     * can prefer the peer that just delivered. The routing-decision side
     * (handing DMs to candidatePeers vs flooding) lands in a follow-up
     * commit. Null disables recording — preserves baseline flood behaviour.
     */
    private val breadcrumbs: BreadcrumbCache? = null,
    val canaryMetrics: CanaryMetrics = CanaryMetrics(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val dmEnvelopeRegistry: DmEnvelopeRegistry = DmEnvelopeRegistry(),
    /**
     * Relay-batcher window. Defaults to 100-500ms wall-clock for timing-correlation
     * resistance. The simulator overrides this to a near-zero window because its
     * sim-time runs ~100× wall-time at speedMult=10 and a 100ms wall delay turns
     * into 10 sim-seconds — long enough to break 2-hop propagation in scenarios.
     */
    private val relayBatchMinWindowMs: Long = RelayBatcher.MIN_WINDOW_MS,
    private val relayBatchSpreadMs: Long = RelayBatcher.SPREAD_MS,
    /** O12: injectable clock for deterministic replay. Defaults to wall-clock. */
    private val clock: com.rumor.mesh.core.Clock = com.rumor.mesh.core.SystemClock,
) {
    private val sequenceCounter = AtomicCounter(clock.now())

    /**
     * Plaintext for outbound DMs keyed by message ID. The ephemeral X25519 private
     * key is discarded after encryption so the sender cannot re-derive the text.
     * Bounded to 500 entries; oldest are evicted by insertion order.
     */
    private val sentDmPlaintext = com.rumor.mesh.core.platform.BoundedFifoMap<String, String>(500)

    fun sentPlaintextFor(messageId: String): String? = sentDmPlaintext[messageId]

    private val _incomingMessages = MutableSharedFlow<RumorMessage>(extraBufferCapacity = 256)
    val incomingMessages: SharedFlow<RumorMessage> = _incomingMessages

    private val _deliveryEvents = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val deliveryEvents: SharedFlow<String> = _deliveryEvents

    /** Emits outbound DMs composed with a registered [DmEnvelopeRegistry] envelope. */
    private val _outboundBridgedDm = MutableSharedFlow<BridgedDmOutbound>(extraBufferCapacity = 64)
    val outboundBridgedDm: SharedFlow<BridgedDmOutbound> = _outboundBridgedDm

    /**
     * TOFU-pinned sender pubkey per bridged-DM senderUserId. First call to
     * [injectBridgedDm] for a given senderUserId records the pubkey; subsequent
     * calls with a different pubkey are dropped as a key-swap attempt by the
     * bridge plugin. See CLAUDE.md A4 — proper handling needs a re-verification
     * UI flow; until then we fail closed.
     */
    private val bridgedSenderPins = ConcurrentMap<String, ByteArray>()

    /** Relayed messages are buffered here before being committed to the scheduler. */
    private val relayBatcher = RelayBatcher(
        scope = scope,
        minWindowMs = relayBatchMinWindowMs,
        spreadMs = relayBatchSpreadMs,
    ) { batch ->
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
                val adjusted = msg.copy(receivedAtMs = clock.now())
                processIncoming(adjusted, MessageSource.PEER, autoRelayIds, fromPeerId = result.peerUserId)
            }
        }
    }

    fun onExchangeFailed(peerId: String) {
        scope.launch {
            canaryMetrics.recordExchange(success = false, rttMs = 0)
            // O3: penalise unreliable peers in the route ranking. Empty peerId
            // means the failure happened before HELLO completed — no route
            // record to update.
            if (peerId.isNotEmpty()) topologyTracker.recordSessionFailed(peerId)
            RumorLog.d(TAG, "Exchange failed with ${peerId.take(8)}…")
        }
    }

    // ── Plugin intake ─────────────────────────────────────────────────────────

    fun injectFromPlugin(message: RumorMessage, sourcePluginId: String) {
        scope.launch {
            RumorLog.d(TAG, "Injecting from plugin $sourcePluginId: ${message.id.take(8)}…")
            val adjusted = message.copy(receivedAtMs = clock.now())
            processIncoming(adjusted, MessageSource.LOCAL_BRIDGE, emptySet())
        }
    }

    /**
     * Deliver an encrypted DM received from a bridge's external network to the local
     * recipient's inbox.
     *
     * Security constraints enforced here (O5a):
     * 1. Envelope is looked up by [senderUserId] prefix from the local registry — never
     *    from the wire-asserted [envelopeId]. If the derived id doesn't match [envelopeId],
     *    the message is dropped (downgrade attack prevention).
     * 2. [BRIDGE_UNSIGNED] sentinel + [MessageSource.LOCAL_BRIDGE] → [TrustLevel.BRIDGED].
     *    The BRIDGED trust level prevents re-relay onto the signed Rumor mesh.
     * 3. The ciphertext is stored as-is; the recipient's UI decrypts at read time using
     *    the registered envelope — this node never holds the plaintext.
     */
    fun injectBridgedDm(
        recipientUserId: String,
        senderUserId: String,
        senderPubKey: ByteArray,
        ciphertext: ByteArray,
        envelopeId: String,
        sourcePluginId: String,
    ) {
        // L2: a plugin may not fabricate DMs addressed to anyone but the local user.
        // Without this check a malicious plugin could plant entries in the local inbox
        // attributed to arbitrary third-party recipients ("X sent Y this message").
        val localId = identityProvider.identity.value?.userId
        if (localId == null || recipientUserId != localId) {
            RumorLog.w(TAG, "injectBridgedDm: recipientUserId is not the local user " +
                "(got='$recipientUserId' local='${localId ?: "(locked)"}'); dropping")
            return
        }
        val envelope = dmEnvelopeRegistry.forRecipient(senderUserId)
        if (envelope == null) {
            RumorLog.w(TAG, "injectBridgedDm: no envelope registered for sender prefix of '$senderUserId'; dropping")
            return
        }
        if (envelope.envelopeId != envelopeId) {
            RumorLog.w(TAG, "injectBridgedDm: envelopeId mismatch " +
                "(derived='${envelope.envelopeId}' asserted='$envelopeId'); dropping")
            return
        }
        // H2 (TOFU): pin the senderUserId → senderPubKey mapping on first sight. A
        // bridge plugin substituting a pubkey on subsequent messages from the same
        // remote user would enable a key-swap attack against any envelope whose
        // decrypt() uses senderPubKey as ECDH input.
        val existingPin = bridgedSenderPins.putIfAbsent(senderUserId, senderPubKey)
        if (existingPin != null && !existingPin.contentEquals(senderPubKey)) {
            RumorLog.w(TAG, "injectBridgedDm: senderPubKey TOFU mismatch for '$senderUserId' " +
                "(plugin=$sourcePluginId); dropping — bridged contact needs re-verification")
            return
        }
        val encPayload = "${envelope.envelopeId}:${ciphertext.toBase64()}"
        val msg = RumorMessage(
            id               = Uuid.randomHex32(),
            senderId         = senderUserId,
            senderPublicKey  = senderPubKey.toBase64(),
            sequenceNumber   = clock.now(),
            sentAtMs         = clock.now(),
            type             = MessageType.DIRECT,
            hopsToLive              = 1,  // BRIDGED trust prevents relay; hopsToLive=1 is belt-and-suspenders
            encryptedPayload = encPayload,
            recipientId      = recipientUserId,
            signature        = BRIDGE_UNSIGNED,  // → LOCAL_BRIDGE source → TrustLevel.BRIDGED
        )
        scope.launch {
            RumorLog.d(TAG, "Injecting bridged DM from $sourcePluginId: ${msg.id.take(8)}…")
            processIncoming(msg, MessageSource.LOCAL_BRIDGE, emptySet())
        }
    }

    // ── Compose ───────────────────────────────────────────────────────────────

    fun composeBroadcast(text: String): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val msg = buildMessage(
            identity = identity,
            type = MessageType.BROADCAST,
            hopsToLive = DEFAULT_BROADCAST_HOPS,
            payload = MessagePayload(ContentType.TEXT, text),
        )
        enqueueImmediate(msg)
        return msg
    }

    fun composeDirect(recipientId: String, recipientPublicKey: ByteArray, text: String): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val envelope = dmEnvelopeRegistry.forRecipient(recipientId)
        val encryptedPayload: String
        val rawCiphertext: ByteArray?
        if (envelope != null) {
            val ct = envelope.encrypt(recipientId, recipientPublicKey, text.toByteArray(Charsets.UTF_8))
            encryptedPayload = "${envelope.envelopeId}:${ct.toBase64()}"
            rawCiphertext = ct
        } else {
            val ephemeral = CryptoManager.generateX25519KeyPair()
            val sharedKey = CryptoManager.x25519Agreement(ephemeral.privateKeyBytes, recipientPublicKey)
            val ct = CryptoManager.aesGcmEncrypt(text.toByteArray(Charsets.UTF_8), sharedKey)
            encryptedPayload = ephemeral.publicKeyBytes.toBase64() + "." + ct.toBase64()
            rawCiphertext = null
        }
        val msg = buildMessage(
            identity = identity,
            type = MessageType.DIRECT,
            hopsToLive = DEFAULT_DIRECT_HOPS,
            encryptedPayload = encryptedPayload,
            recipientId = recipientId,
        )
        sentDmPlaintext.put(msg.id, text)
        if (envelope != null && rawCiphertext != null) {
            // Bridged DM: the bridge plugin picks this up via outboundBridgedDm and
            // forwards the raw ciphertext to the external network. Do not enqueue in the
            // gossip scheduler — bridged recipients are not reachable via Rumor peers.
            scope.launch {
                _outboundBridgedDm.emit(BridgedDmOutbound(recipientId, envelope.envelopeId, rawCiphertext))
            }
        } else {
            enqueueImmediate(msg)
        }
        return msg
    }

    fun manualRelay(msg: RumorMessage) {
        enqueueImmediate(messageStore.boostHopsForManualRelay(msg))
        scope.launch { messageStore.markRelayed(msg.id) }
    }

    fun composeChunkRequest(
        transferId: String,
        missingIndices: List<Int>,
        originalSenderId: String,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val body = WireJson.encodeToString(ChunkRequest(transferId, missingIndices))
        val msg = buildMessage(
            identity = identity,
            type = MessageType.CHUNK_REQUEST,
            hopsToLive = DEFAULT_DIRECT_HOPS,
            payload = MessagePayload(ContentType.CONTROL, body),
            recipientId = originalSenderId,
        )
        enqueueImmediate(msg)
        return msg
    }

    /**
     * O18: tell the original sender of a chunked transfer to abandon it.
     * The receiver calls this when the user hits Cancel; the sender stops
     * responding to chunk-requests for [transferId] on receipt.
     */
    fun composeTransferCancel(
        transferId: String,
        originalSenderId: String,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val body = WireJson.encodeToString(
            com.rumor.mesh.core.model.TransferCancel(transferId = transferId)
        )
        val msg = buildMessage(
            identity = identity,
            type = MessageType.TRANSFER_CANCEL,
            hopsToLive = DEFAULT_DIRECT_HOPS,
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
            hopsToLive = DEFAULT_DIRECT_HOPS,
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
            hopsToLive = DEFAULT_DIRECT_HOPS,
            payload = MessagePayload(ContentType.CONTROL, ""),
            recipientId = request.senderId,
        )
        enqueueImmediate(reply)
    }

    /**
     * Compose and broadcast an [MessageType.IDENTITY_ROTATION] migrating the
     * local identity from [oldUserId] (proven via [oldKeySign]) to the current
     * identity. The outer message is signed by the current (new) key in the
     * usual way; [oldKeySign] produces the inner continuity signature with the
     * old key, which contacts holding [oldUserId] verify before rebinding.
     * See O41.
     */
    suspend fun composeIdentityRotation(
        oldUserId: String,
        oldKeySign: (ByteArray) -> ByteArray,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val newPublicKeyB64 = identity.publicKeyBytes.toBase64()
        val authorizedAtMs = clock.now()
        val continuityBytes = identityRotationSignableBytes(
            oldUserId = oldUserId,
            newUserId = identity.userId,
            newPublicKey = newPublicKeyB64,
            authorizedAtMs = authorizedAtMs,
        )
        val continuitySig = oldKeySign(continuityBytes).toBase64()
        val payload = IdentityRotationPayload(
            oldUserId = oldUserId,
            newUserId = identity.userId,
            newPublicKey = newPublicKeyB64,
            authorizedAtMs = authorizedAtMs,
            continuitySignature = continuitySig,
        )
        val msg = buildMessage(
            identity = identity,
            type = MessageType.IDENTITY_ROTATION,
            hopsToLive = DEFAULT_BROADCAST_HOPS,
            payload = MessagePayload(ContentType.CONTROL, WireJson.encodeToString(payload)),
        )
        enqueueImmediate(msg)
        return msg
    }

    /**
     * Compose and broadcast a [MessageType.SELF_PRESENCE] beacon (O30 + O57).
     * The sender declares its current [mode] to the mesh. Used both as the
     * entry pulse when going Static or Free, and as the symmetric exit pulse
     * (mode = MOBILE) when leaving a higher mode — without the exit pulse,
     * peers continue to route through a ghost anchor for the full presence-
     * decay window.
     *
     * [recentlyExchangedWith] is the optional O31 route advertisement payload;
     * pass an empty list (the default) unless the user has opted into Tier-3
     * broadcast routing-visibility for their own visibility (typically Free
     * mode). The receiver decides how to weight this list given its own
     * O58 visibility settings.
     */
    fun composeSelfPresence(
        mode: UserMode,
        recentlyExchangedWith: List<String> = emptyList(),
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val payload = SelfPresencePayload(
            mode = mode,
            authorizedAtMs = clock.now(),
            recentlyExchangedWith = recentlyExchangedWith,
        )
        val msg = buildMessage(
            identity = identity,
            type = MessageType.SELF_PRESENCE,
            hopsToLive = DEFAULT_BROADCAST_HOPS,
            payload = MessagePayload(ContentType.CONTROL, WireJson.encodeToString(payload)),
        )
        enqueueImmediate(msg)
        return msg
    }

    /**
     * Compose and broadcast a [MessageType.BRIDGE_VOUCHED] envelope (O17).
     * Called by a bridge plugin when it wants to propagate foreign-network
     * content across the Rumor mesh beyond its direct peers. The outer message
     * is signed by the local (bridge) Rumor key in the usual way; the
     * [BridgeVouchedPayload] carries the foreign-network framing.
     *
     * Trust model: the outer Ed25519 signature certifies "this bridge
     * received this content from {originNetwork}." It does NOT vouch for
     * content authenticity — the foreign sender is not a Rumor user. The
     * display layer (O47) MUST render this as "via {bridge} from
     * {originNetwork}:{senderId}" so users don't conflate it with native
     * Rumor authenticity.
     */
    fun composeBridgeVouched(
        originNetwork: String,
        originSenderId: String,
        originContentType: ContentType,
        payload: String,
        originSignatureIfAny: String? = null,
        receivedAtMs: Long = clock.now(),
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val body = BridgeVouchedPayload(
            originNetwork = originNetwork,
            originSenderId = originSenderId,
            originSignatureIfAny = originSignatureIfAny,
            originContentType = originContentType,
            payload = payload,
            receivedAtMs = receivedAtMs,
        )
        val msg = buildMessage(
            identity = identity,
            type = MessageType.BRIDGE_VOUCHED,
            hopsToLive = DEFAULT_BROADCAST_HOPS,
            payload = MessagePayload(originContentType, WireJson.encodeToString(body)),
        )
        enqueueImmediate(msg)
        return msg
    }

    // ── Transport supply ──────────────────────────────────────────────────────

    /**
     * Returns messages to offer to [peerUserId]. The batch size is shaped by the
     * peer's recent overlap history: a peer that already knew most of our last
     * offer gets a smaller, freshest-only batch; a peer with low overlap gets a
     * full batch. This is the protocol-layer expression of diversity-aware
     * routing — see [NeighborStore]. Peer identity comes from the post-HELLO
     * Ed25519 fingerprint, never from MAC or pre-handshake hints.
     */
    fun messagesForExchange(peerUserId: String): List<RumorMessage> {
        val overlap = topologyTracker.overlapFor(peerUserId)
        val cap = when {
            overlap >= 0.8f -> 50    // peer is well-informed; send just the freshest
            overlap >= 0.5f -> 100
            else            -> 200   // novel peer or unknown — send the full batch
        }
        val batch = scheduler.take(cap)

        // O29 per-peer routing filter. A relayed DM marked with intendedPeers
        // (set at relay time when breadcrumbs named candidates) is only
        // offered to peers in that set — other peers see the batch without
        // it. Locally-composed DMs and broadcasts have null intendedPeers and
        // are offered to everyone. This is hard exclusion, not bias: the
        // hard ceiling on routedHops+floodedHops in relay() bounds worst-case
        // loops if a breadcrumb is stale.
        val routedFiltered = batch.filter { msg ->
            val intended = msg.intendedPeers ?: return@filter true
            peerUserId in intended
        }
        if (breadcrumbs == null) return routedFiltered

        // O29 ordering bias for messages that haven't been pre-routed
        // (locally-composed DMs and intended-peer-null relays): DMs whose
        // breadcrumbs include this peer float to the front of the batch.
        // Stable-partition preserves scheduler order within each class.
        val (preferred, rest) = routedFiltered.partition { msg ->
            msg.intendedPeers == null &&
                msg.type == MessageType.DIRECT &&
                msg.recipientId != null &&
                peerUserId in breadcrumbs.candidatePeersSync(msg.recipientId)
        }
        return preferred + rest
    }
    fun knownMessageIds(): Set<String> = duplicateFilter.knownIds()
    val queueDepth: Int get() = scheduler.queueDepth

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun processIncoming(
        rawMsg: RumorMessage,
        source: MessageSource,
        autoRelayIds: Set<String>,
        /**
         * The peer userId that handed us this message, when known. Used to
         * record a breadcrumb (O29 Tier 1) — "messages to sender X are
         * reachable via peer P." Null for locally-composed messages and
         * for bridge-injected traffic where the concept doesn't apply.
         */
        fromPeerId: String? = null,
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

        // O17: BRIDGE_VOUCHED messages have a real outer Ed25519 (the bridge's)
        // which has already been verified by messageStore.ingest above. The
        // trust level reflects the foreign-content semantics, not the bridge's
        // delivery signature. Display layer (O47) surfaces the via-bridge
        // framing so users don't conflate this with native Rumor authenticity.
        val finalTrust = when {
            bridged -> TrustLevel.BRIDGED
            clamped.type == MessageType.BRIDGE_VOUCHED -> TrustLevel.BRIDGE_VOUCHED
            else -> TrustLevel.VERIFIED
        }
        val msg = clamped.copy(trustLevel = finalTrust)

        // O29 Tier 1: record a breadcrumb pointing back through the peer that
        // delivered this message. Bridged messages are skipped because the
        // synthetic senderId doesn't map to a Rumor-mesh path. The cost of
        // recording every signed inbound is one upsert + prune per message —
        // dwarfed by signature verification already done in MessageStore.ingest.
        if (!bridged && fromPeerId != null && fromPeerId != msg.senderId) {
            breadcrumbs?.record(
                targetUserId = msg.senderId,
                fromPeerId = fromPeerId,
                hopCount = (DEFAULT_BROADCAST_HOPS - msg.hopsToLive).coerceAtLeast(1),
            )
        }

        // Auto-accept incoming priority link acceptance: mark the sender as priority peer.
        if (msg.type == MessageType.PRIORITY_LINK_ACCEPT &&
            msg.recipientId == identityProvider.identity.value?.userId) {
            scope.launch { contactRepo.setPriorityPeer(msg.senderId, true) }
        }

        // O41: identity rotation. The outer signature on `msg` is by the *new*
        // key (already verified above). We additionally check the *inner*
        // continuity signature against the existing contact's old public key —
        // proves the rotation is authorized by the old-key holder. Only then
        // do we rebind the contact record so display-name pinning (O21) and
        // routing breadcrumbs (O29) follow the same human across the rotation.
        if (msg.type == MessageType.IDENTITY_ROTATION) {
            scope.launch { applyIdentityRotation(msg) }
        }

        emitToInbox(msg)
        relay(msg, autoRelayIds)
    }

    private suspend fun applyIdentityRotation(msg: RumorMessage) {
        val payloadJson = msg.payload?.content ?: return
        val rotation = runCatching {
            WireJson.decodeFromString<IdentityRotationPayload>(payloadJson)
        }.getOrNull() ?: return

        // Outer-signature pubkey must equal claimed new pubkey, otherwise an
        // attacker could broadcast someone else's rotation with their own
        // outer key. The userId↔pubkey binding is cryptographic
        // (userId = SHA-256(pubkey)) so a simple equality on the new userId is
        // sufficient: if senderId != newUserId, drop.
        if (msg.senderId != rotation.newUserId) return
        if (msg.senderPublicKey != rotation.newPublicKey) return

        // Old key on file determines whether we accept this rotation. If we
        // don't have the old contact, there is nothing to rebind — drop
        // silently. (Relaying the message is fine; rebinding requires prior
        // knowledge of the old key.)
        val existing = contactRepo.getById(rotation.oldUserId) ?: return
        val oldPubKeyBytes = runCatching { existing.publicKey.fromBase64() }.getOrNull() ?: return
        val continuityBytes = runCatching { rotation.continuitySignature.fromBase64() }.getOrNull() ?: return
        val signable = identityRotationSignableBytes(
            oldUserId = rotation.oldUserId,
            newUserId = rotation.newUserId,
            newPublicKey = rotation.newPublicKey,
            authorizedAtMs = rotation.authorizedAtMs,
        )
        if (!CryptoManager.verify(signable, continuityBytes, oldPubKeyBytes)) {
            RumorLog.w(TAG, "Identity rotation from ${rotation.oldUserId.take(16)}… failed continuity check")
            return
        }

        val rebound = contactRepo.rebindIdentity(
            oldUserId = rotation.oldUserId,
            newUserId = rotation.newUserId,
            newPublicKey = rotation.newPublicKey,
        )
        if (rebound) {
            RumorLog.i(TAG, "Identity rotation: ${rotation.oldUserId.take(16)}… → ${rotation.newUserId.take(16)}…")
        }
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
        // foreign message into a vouch this node never made. BRIDGE_VOUCHED is
        // explicitly allowed because the bridge's outer Rumor signature certifies
        // delivery (not content) and is checked on every hop like any other.
        if (msg.trustLevel == TrustLevel.BRIDGED) return
        when (msg.type) {
            MessageType.BROADCAST,
            MessageType.BRIDGE_VOUCHED -> {
                val forwarded = messageStore.decrementHops(msg) ?: return
                val boosted = if (msg.senderId in autoRelayIds) {
                    messageStore.boostHopsForManualRelay(forwarded)
                } else forwarded
                enqueueRelayed(boosted)
            }
            MessageType.DIRECT -> {
                val localId = identityProvider.identity.value?.userId
                if (msg.recipientId == localId) return
                val forwarded = messageStore.decrementHops(msg) ?: return

                // O29 per-peer routing decision. If breadcrumbs name candidate
                // peers for the recipient, mark this relay as routed-to-those-
                // peers and increment routedHops (NOT floodedHops). Otherwise
                // fall back to flood: leave intendedPeers null and decrement
                // floodedHops via the legacy hopsToLive path. Hard ceiling at
                // MAX_TOTAL_HOPS regardless of split.
                val recipientId = forwarded.recipientId
                val candidates = if (breadcrumbs != null && recipientId != null) {
                    breadcrumbs.candidatePeersSync(recipientId).toSet().takeIf { it.isNotEmpty() }
                } else null

                val withSplit = if (candidates != null) {
                    // Routed hop: increment routedHops, leave floodedHops at
                    // the inherited value (don't decrement). intendedPeers
                    // restricts the next offer batch to the matched peers.
                    val newRouted = (forwarded.routedHops + 1).coerceAtMost(MAX_TOTAL_HOPS)
                    forwarded.withTtlSplit(
                        routedHops = newRouted,
                        floodedHops = forwarded.floodedHops,
                    ).copy(intendedPeers = candidates)
                } else {
                    // Flood fallback: floodedHops decrements via the existing
                    // hopsToLive path already done by decrementHops.
                    forwarded.withTtlSplit(
                        routedHops = forwarded.routedHops,
                        floodedHops = forwarded.hopsToLive,
                    )
                }
                if (withSplit.routedHops + withSplit.floodedHops > MAX_TOTAL_HOPS) return
                enqueueRelayed(withSplit)
            }
            MessageType.PING -> {
                val forwarded = msg.copy(hopsToLive = (msg.hopsToLive - 1).coerceAtLeast(0))
                if (forwarded.hopsToLive > 0) enqueueRelayed(forwarded)
            }
            MessageType.PONG -> { /* handled by routing */ }
            MessageType.TRANSFER_METADATA, MessageType.CHUNK -> {
                val localId = identityProvider.identity.value?.userId
                if (msg.recipientId == null || msg.recipientId == localId) return
                val forwarded = messageStore.decrementHops(msg) ?: return
                enqueueRelayed(forwarded)
            }
            MessageType.CHUNK_REQUEST,
            MessageType.TRANSFER_CANCEL -> {
                val localId = identityProvider.identity.value?.userId
                if (msg.recipientId == localId) return
                val forwarded = messageStore.decrementHops(msg) ?: return
                enqueueRelayed(forwarded)
            }
            MessageType.BLOCKLIST_PUBLISH, MessageType.BLOCKLIST_DIFF,
            MessageType.IDENTITY_ROTATION,
            MessageType.SELF_PRESENCE -> {
                val forwarded = messageStore.decrementHops(msg) ?: return
                enqueueRelayed(forwarded)
            }
            MessageType.PRIORITY_LINK_REQUEST, MessageType.PRIORITY_LINK_ACCEPT -> {
                val localId = identityProvider.identity.value?.userId
                if (msg.recipientId == localId) return
                val forwarded = messageStore.decrementHops(msg) ?: return
                enqueueRelayed(forwarded)
            }
        }
    }

    private fun clampTtl(msg: RumorMessage): RumorMessage {
        val ceiling = when (msg.type) {
            MessageType.BROADCAST, MessageType.PING, MessageType.PONG,
            MessageType.BLOCKLIST_PUBLISH, MessageType.BLOCKLIST_DIFF,
            MessageType.IDENTITY_ROTATION, MessageType.SELF_PRESENCE,
            MessageType.BRIDGE_VOUCHED -> MAX_BROADCAST_HOPS
            MessageType.DIRECT, MessageType.TRANSFER_METADATA,
            MessageType.CHUNK, MessageType.CHUNK_REQUEST,
            MessageType.TRANSFER_CANCEL,
            MessageType.PRIORITY_LINK_REQUEST,
            MessageType.PRIORITY_LINK_ACCEPT -> MAX_DIRECT_HOPS
        }
        return if (msg.hopsToLive > ceiling) msg.copy(hopsToLive = ceiling) else msg
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
        hopsToLive: Int,
        payload: MessagePayload? = null,
        encryptedPayload: String? = null,
        recipientId: String? = null,
    ): RumorMessage {
        val unsigned = RumorMessage(
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
