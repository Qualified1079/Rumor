package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.approxWireBytes
import com.rumor.mesh.core.model.BridgeVouchedPayload
import com.rumor.mesh.core.model.SelfPresencePayload
import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.ChunkRequest
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.MAX_TOTAL_HOPS
import com.rumor.mesh.core.model.MultiRecipientEnvelope
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.TrustLevel
import com.rumor.mesh.core.platform.Base64Codec
import com.rumor.mesh.core.model.floodedHops
import com.rumor.mesh.core.model.routedHops
import com.rumor.mesh.core.model.withTtlSplit
import com.rumor.mesh.core.policy.InboxFilter
import com.rumor.mesh.core.routing.BreadcrumbCache
import com.rumor.mesh.core.routing.MeshViewTracker
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
import com.rumor.mesh.core.wire.hlcTimestamp
import com.rumor.mesh.core.wire.withHlc
import com.rumor.mesh.core.wire.withCompressionMetadata
import com.rumor.mesh.core.wire.withMentions
import com.rumor.mesh.core.wire.withReplyTo
import com.rumor.mesh.core.wire.roomRoutingTag
import com.rumor.mesh.core.wire.withRoomRoutingTag
import com.rumor.mesh.core.wire.withSealedSenderTag

private const val TAG = "GossipEngine"
private const val DEFAULT_BROADCAST_HOPS = 7
private const val DEFAULT_DIRECT_HOPS = 15
private const val MAX_BROADCAST_HOPS = DEFAULT_BROADCAST_HOPS
private const val MAX_DIRECT_HOPS = DEFAULT_DIRECT_HOPS
/** O92 reseed bounds. Offer set is capped near the scheduler's own backlog cap;
 *  dedup seed is wider so our summary reflects everything we hold, not just what's offerable. */
private const val OFFER_RESEED_LIMIT = 4_000
private const val DEDUP_RESEED_LIMIT = 50_000
/** O42: RBSR snapshot cap — matches the store cap (50k, 200k static) order of magnitude. */
private const val RBSR_SNAPSHOT_LIMIT = 50_000
/** Deeper O92: per-exchange ceiling on fetch-by-id so a hostile Request can't scan the store. */
private const val MAX_BY_ID_FETCH = 500
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
    /**
     * O79 — optional subscription provider for ROOM_MESSAGE receive
     * dispatch. When non-null, the engine consults this on every
     * inbound ROOM_MESSAGE: the returned lists feed
     * [com.rumor.mesh.core.protocol.RoomTagMatcher] for tag → roomId
     * resolution, and on match the engine attempts
     * [com.rumor.mesh.core.protocol.MultiRecipientEnvelopeCodec.decrypt]
     * (for ENCRYPTED rooms) or pass-through to inbox (for OPEN).
     *
     * Null disables room-message receive dispatch — relay still
     * happens (rooms are broadcast-tier traffic and propagate
     * regardless of local subscription), the local inbox just won't
     * see anything from rooms this node is in. Useful for nodes that
     * are purely relay infrastructure with no local UI to display
     * room messages.
     *
     * The provider returns a snapshot — caller may freely cache and
     * refresh on subscription mutation. The receive path is itself
     * synchronous in the dispatch step (matcher + decode); only the
     * tag computation is meaningful CPU.
     */
    private val roomSubscriptionProvider: RoomSubscriptionProvider? = null,
    /**
     * O98 (MeshView substrate) — optional tracker fed by inbound SELF_PRESENCE
     * beacons. When non-null, every verified SELF_PRESENCE records the sender's
     * mode + advertised neighbours (with recency decay); the O98 persistence
     * planner reads [MeshViewTracker.assembleView] to pick a degree-bounded
     * backbone. Null disables tracking — relay of the beacon still happens.
     */
    private val meshView: MeshViewTracker? = null,
) {
    /** O79 receive-side subscription snapshot consumed by ROOM_MESSAGE dispatch. */
    interface RoomSubscriptionProvider {
        /** OPEN-mode roomIds the local user is subscribed to. */
        fun openRoomIds(): List<String>
        /** ENCRYPTED-mode subscriptions with their routing keys. */
        fun encryptedRoomSubscriptions(): List<RoomTagMatcher.EncryptedRoomSubscription>
        /**
         * The local user's X25519 static private key for ENCRYPTED-room
         * decryption. Returning null means decryption is not possible
         * (identity locked, or this engine instance doesn't support
         * receive-side room decryption). On null, ENCRYPTED room
         * messages still match for routing purposes but the engine
         * skips the decrypt step.
         *
         * **Implementations should return a FRESH buffer each call** —
         * the engine zeroes the returned bytes in a finally block after
         * decryption to satisfy the O39 sender/receiver-FS contract.
         * Returning a long-lived shared buffer would let the engine
         * scribble over the long-term key. Per O91 the natural
         * implementation derives the X25519 private from the Ed25519
         * seed (SHA-512(seed)[0:32] with RFC 7748 clamping) on each
         * call, which is cheap and produces a fresh buffer by
         * construction.
         */
        fun localX25519StaticPrivate(): ByteArray?
    }
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
                // The peer proved ownership of this key via HELLO challenge-response,
                // so a completed exchange creates the contact even when zero messages
                // moved (two fresh installs meeting for the first time).
                if (result.peerPublicKey.isNotEmpty()) {
                    messageStore.ensureContact(result.peerUserId, result.peerPublicKey)
                }
                topologyTracker.recordSession(
                    peerId           = result.peerUserId,
                    latencyMs        = result.durationMs,
                    hopCount         = 1,
                    bytesTransferred = result.bytesTransferred,
                    overlapFraction  = result.peerOverlapFraction,
                )
                onlineStatusTracker.recordDirectContact(result.peerUserId)
                // O76 capability cache — store the peer's advertised features
                // for use by future compose-side feature gating. JSON-encoded
                // list; setSupportedFeatures is a no-op if the contact isn't
                // on file yet.
                if (result.source == ExchangeSource.WIFI_DIRECT) {
                    val json = WireJson.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(
                            kotlinx.serialization.serializer<String>()
                        ),
                        result.peerSupportedFeatures,
                    )
                    contactRepo.setSupportedFeatures(result.peerUserId, json)
                }
            }
            // A peer's snapshot includes everyone IT has seen — which normally
            // includes us (it just exchanged with us). Merging that verbatim marks
            // our own userId ONLINE in our own tracker, so we render ourselves as
            // an unnamed contact (no contact row → bare hex userId). Never record
            // self as a peer.
            val selfUserId = identityProvider.identity.value?.userId
            onlineStatusTracker.mergeRemoteStatus(
                if (selfUserId != null) result.peerOnlineUsers - selfUserId
                else result.peerOnlineUsers
            )

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

    /**
     * @param replyTo Optional parent messageId for thread reconstruction
     *   (O90). Carried in `_ext.replyTo`; unsigned (local-display only).
     * @param mentions Optional userIds explicitly @-mentioned by the
     *   sender (O90). Carried in `_ext.mentions`; unsigned (UI uses for
     *   notification feeds + cross-room mention aggregators).
     */
    fun composeBroadcast(
        text: String,
        replyTo: String? = null,
        mentions: List<String> = emptyList(),
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        // O132: broadcasts are never chunked; refuse oversized text so it can't
        // become a >4 MB monolithic frame that wedges sync.
        if (text.length > com.rumor.mesh.core.model.MAX_BROADCAST_CONTENT_BYTES) {
            RumorLog.w(TAG, "Refusing broadcast: ${text.length}B exceeds ${com.rumor.mesh.core.model.MAX_BROADCAST_CONTENT_BYTES}B cap")
            return null
        }
        val base = buildMessage(
            identity = identity,
            type = MessageType.BROADCAST,
            hopsToLive = DEFAULT_BROADCAST_HOPS,
            payload = MessagePayload(ContentType.TEXT, text),
        )
        val msg = base.applyThreadAndMentionExt(replyTo, mentions)
        enqueueImmediate(msg)
        scope.launch { messageStore.ingestOwn(msg) }
        return msg
    }

    private fun RumorMessage.applyThreadAndMentionExt(
        replyTo: String?,
        mentions: List<String>,
    ): RumorMessage {
        var result = this
        if (replyTo != null) result = result.withReplyTo(replyTo)
        if (mentions.isNotEmpty()) result = result.withMentions(mentions)
        return result
    }

    /**
     * @param replyTo Optional parent messageId for thread reconstruction
     *   (O90). Carried in `_ext.replyTo`; unsigned (local-display only).
     * @param mentions Optional userIds explicitly @-mentioned by the
     *   sender (O90). Carried in `_ext.mentions`; unsigned.
     */
    fun composeDirect(
        recipientId: String,
        recipientPublicKey: ByteArray,
        text: String,
        replyTo: String? = null,
        mentions: List<String> = emptyList(),
        hopsToLive: Int? = null,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val envelope = dmEnvelopeRegistry.forRecipient(recipientId)
        val encryptedPayload: String
        val rawCiphertext: ByteArray?
        // O76 — set only on the native-envelope path when compression succeeded.
        var compressionMeta: Pair<Int, Int>? = null
        if (envelope != null) {
            val ct = envelope.encrypt(recipientId, recipientPublicKey, text.toByteArray(Charsets.UTF_8))
            encryptedPayload = "${envelope.envelopeId}:${ct.toBase64()}"
            rawCiphertext = ct
        } else {
            // O76 compose-side flip: compress + pad TEXT plaintext, AEAD with
            // compressionAad as associated data so a relay cannot tamper with
            // originalLength. compression-v1 is in LOCAL_SUPPORTED_FEATURES so
            // every Rumor build can decode; no per-recipient gate needed for v1.
            val plaintextBytes = text.toByteArray(Charsets.UTF_8)
            val encoded = com.rumor.mesh.core.wire.CompressedPaddedCodec.encodeForWire(plaintextBytes)
            val ephemeral = CryptoManager.generateX25519KeyPair()
            // O91: recipientPublicKey is an Ed25519 identity pubkey; convert to
            // X25519 via the birational map before DH. Without this, the agreement
            // silently produces a wrong-but-stable secret on the sender side and a
            // different wrong-but-stable secret on the receiver side — pinned by
            // Ed25519AsX25519RoundtripTest. The matching conversion lives in
            // ThreadViewModel.decryptPayload (Ed25519 seed → X25519 priv).
            val recipientX25519Pub = CryptoManager.ed25519ToX25519Public(recipientPublicKey)
            val sharedKey = CryptoManager.x25519Agreement(ephemeral.privateKeyBytes, recipientX25519Pub)
            if (encoded != null) {
                val aad = com.rumor.mesh.core.wire.compressionAad(encoded.originalLength)
                val ct = CryptoManager.aesGcmEncrypt(encoded.bytes, sharedKey, aad)
                encryptedPayload = ephemeral.publicKeyBytes.toBase64() + "." + ct.toBase64()
                compressionMeta = Pair(encoded.bucketIndex, encoded.originalLength)
            } else {
                // Plaintext exceeded MAX_SINGLE_MESSAGE post-compress; fall back to
                // uncompressed-and-unpadded path. Chunker fallback for >64 KB
                // compressed text is the open follow-up.
                val ct = CryptoManager.aesGcmEncrypt(plaintextBytes, sharedKey)
                encryptedPayload = ephemeral.publicKeyBytes.toBase64() + "." + ct.toBase64()
                compressionMeta = null
            }
            rawCiphertext = null
            // O39 sender-side FS: actively zero the ephemeral private and the derived
            // AES key before they wait for GC. Bytes that live in the heap until GC
            // remain readable by a process-memory dump up to that point; zero-fill
            // makes the FS-after-send guarantee a property of the code, not luck.
            ephemeral.privateKeyBytes.fill(0)
            sharedKey.fill(0)
        }
        val baseMsg = buildMessage(
            identity = identity,
            type = MessageType.DIRECT,
            hopsToLive = (hopsToLive ?: DEFAULT_DIRECT_HOPS).coerceIn(1, MAX_DIRECT_HOPS),
            encryptedPayload = encryptedPayload,
            recipientId = recipientId,
        )
        val withMeta = (compressionMeta?.let { (bucket, origLen) ->
            baseMsg.withCompressionMetadata(bucket, origLen)
        } ?: baseMsg).applyThreadAndMentionExt(replyTo, mentions)
        // O53 sealed-sender: stamp _ext.t with HMAC(perContactKey, "rumor-dm-v1:" || id)
        // alongside the existing plaintext recipientId (coexistence phase). Recipient
        // can pre-match the tag against per-contact keys without learning recipientId
        // from the wire once the plaintext field is dropped. _ext is not in
        // signableBytes, so the outer Ed25519 sig stays valid. Skipped for bridged DMs
        // — recipientPublicKey is a foreign-network key, not Rumor Ed25519, so the
        // Ed25519→X25519 derivation isn't applicable.
        val msg = if (envelope == null) {
            val tagKey = SealedSenderKey.derive(
                myUserId = identity.userId,
                myEd25519Priv = identity.privateKeyBytes,
                theirUserId = recipientId,
                theirEd25519Pub = recipientPublicKey,
            )
            try {
                val tag = SealedSenderTag.tagFor(tagKey, withMeta.id)
                withMeta.withSealedSenderTag(tag.toBase64())
            } finally {
                tagKey.fill(0)
            }
        } else withMeta
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
            scope.launch { messageStore.ingestOwn(msg) }
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
    /**
     * Compose a signed delete-on-ACK request for a DIRECT message you
     * either authored or were the recipient of (O40).
     *
     * Returns null if (a) identity is locked, (b) the target message
     * isn't in the local store, or (c) the local userId doesn't match
     * either the target's senderId or recipientId. Anything else is
     * the caller broadcasting a delete they aren't authorized to issue
     * — relays would reject it anyway, so we don't compose.
     */
    suspend fun composeMessageDelete(messageId: String): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val target = messageStore.getById(messageId) ?: return null
        if (identity.userId != target.senderId && identity.userId != target.recipientId) return null

        val issuerPubKeyB64 = com.rumor.mesh.core.platform.Base64Codec.encode(identity.publicKeyBytes)
        val signed = com.rumor.mesh.core.model.messageDeleteSignableBytes(messageId, issuerPubKeyB64)
        val sigBytes = com.rumor.mesh.core.crypto.CryptoManager.sign(signed, identity.privateKeyBytes)
        val payload = com.rumor.mesh.core.model.MessageDeletePayload(
            messageId = messageId,
            issuerPublicKey = issuerPubKeyB64,
            signature = com.rumor.mesh.core.platform.Base64Codec.encode(sigBytes),
        )
        val msg = buildMessage(
            identity = identity,
            type = MessageType.MESSAGE_DELETE,
            hopsToLive = DEFAULT_BROADCAST_HOPS,
            payload = MessagePayload(ContentType.CONTROL, WireJson.encodeToString(payload)),
        )
        // Purge locally first — the relayed broadcast will purge it
        // everywhere else as it propagates.
        messageStore.deleteById(messageId)
        enqueueImmediate(msg)
        return msg
    }

    /**
     * Compose a Room-addressed message (O79).
     *
     * **Caller provides:**
     *  - [routingTag] — opaque 16-byte routing identifier from
     *    [com.rumor.mesh.core.protocol.RoomRoutingTag.openRoomTag]
     *    (OPEN rooms) or [com.rumor.mesh.core.protocol.RoomRoutingTag.encryptedRoomTag]
     *    (ENCRYPTED rooms). The plaintext roomId stays off the wire.
     *  - [recipients] — for ENCRYPTED rooms, the list of authorized
     *    receivers with their X25519 static public keys. Empty list
     *    is treated as OPEN-mode (plaintext signed broadcast).
     *  - [plaintext] — the message body to encrypt (or carry signed
     *    plaintext for OPEN).
     *
     * **Why the caller provides recipients:** the events-derived
     * membership projection is a separate concern from compose. UI
     * code (or test fixtures) enumerates the current room membership
     * and passes the recipient list explicitly. This keeps
     * [GossipEngine] decoupled from room-membership storage.
     *
     * For ENCRYPTED rooms uses
     * [com.rumor.mesh.core.protocol.MultiRecipientEnvelopeCodec.encrypt]
     * under the hood with the sender's Ed25519 identity. The
     * resulting [com.rumor.mesh.core.model.MultiRecipientEnvelope]
     * is JSON-serialised into the message's `encryptedPayload`.
     * For OPEN rooms the plaintext goes into `payload.content`.
     *
     * The 16-byte [routingTag] is Base64-encoded and stored in
     * `_ext.rt`; the receiver's
     * [com.rumor.mesh.core.protocol.RoomTagMatcher] consumes it.
     *
     * Returns null if identity is locked. Otherwise returns the
     * outgoing [RumorMessage] (already enqueued for transport).
     */
    fun composeRoomMessage(
        routingTag: ByteArray,
        plaintext: String,
        recipients: List<MultiRecipientEnvelopeCodec.Recipient> = emptyList(),
        replyTo: String? = null,
        mentions: List<String> = emptyList(),
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        // O132: room messages are gossip-tier and unchunked — same cap as broadcasts.
        if (plaintext.length > com.rumor.mesh.core.model.MAX_BROADCAST_CONTENT_BYTES) {
            RumorLog.w(TAG, "Refusing room message: ${plaintext.length}B exceeds cap")
            return null
        }

        val baseMsg: RumorMessage = if (recipients.isEmpty()) {
            // OPEN room — signed plaintext broadcast.
            buildMessage(
                identity = identity,
                type = MessageType.ROOM_MESSAGE,
                hopsToLive = DEFAULT_BROADCAST_HOPS,
                payload = MessagePayload(ContentType.TEXT, plaintext),
            )
        } else {
            // ENCRYPTED room — multi-recipient envelope.
            val envelope = MultiRecipientEnvelopeCodec.encrypt(
                plaintext = plaintext.toByteArray(Charsets.UTF_8),
                senderEd25519Private = identity.privateKeyBytes,
                senderId = identity.userId,
                senderEd25519Public = identity.publicKeyBytes,
                recipients = recipients,
                roomRoutingTag = Base64Codec.encode(routingTag),
            )
            buildMessage(
                identity = identity,
                type = MessageType.ROOM_MESSAGE,
                hopsToLive = DEFAULT_BROADCAST_HOPS,
                encryptedPayload = WireJson.encodeToString(envelope),
            )
        }

        // Stamp the routing tag into _ext.rt + apply O90 thread/mention metadata.
        val routedMsg = baseMsg.withRoomRoutingTag(Base64Codec.encode(routingTag))
            .applyThreadAndMentionExt(replyTo, mentions)

        enqueueImmediate(routedMsg)
        return routedMsg
    }

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
    suspend fun messagesForExchange(peerUserId: String): List<RumorMessage> {
        val overlap = topologyTracker.overlapFor(peerUserId)
        val cap = when {
            overlap >= 0.8f -> 50    // peer is well-informed; send just the freshest
            overlap >= 0.5f -> 100
            else            -> 200   // novel peer or unknown — send the full batch
        }
        // Deeper O92: the scheduler is the priority-shaped head (class priority +
        // DRR fairness for fresh/contended traffic), but its take() is destructive
        // — whatever the previous peer drained would never be offered again this
        // run. Backfill from the durable store so every exchange offers what we
        // hold, not what happens to still sit in a volatile queue. The peer's
        // summary (bloom/RBSR) dedups on the wire, so re-offering is free.
        val head = scheduler.take(cap)
        val batch = if (head.size >= cap) head else {
            val seen = head.mapTo(HashSet()) { it.id }
            head + messageStore.offerable(cap).filter { it.id !in seen }.take(cap - head.size)
        }

        // O29 per-peer routing filter. A relayed DM marked with intendedPeers
        // (set at relay time when breadcrumbs named candidates) is only
        // offered to peers in that set — other peers see the batch without
        // it. Locally-composed DMs and broadcasts have null intendedPeers and
        // are offered to everyone. This is hard exclusion, not bias: the
        // hard ceiling on routedHops+floodedHops in relay() bounds worst-case
        // loops if a breadcrumb is stale.
        val localUserId = identityProvider.identity.value?.userId
        val routedFiltered = ArrayList<RumorMessage>(batch.size)
        for (msg in batch) {
            val intended = msg.intendedPeers
            if (intended != null) {
                if (peerUserId in intended) routedFiltered.add(msg)
                continue
            }
            // Deeper O92: a backfilled relayed DM has lost its relay-time
            // intendedPeers mark (that lives on the scheduler copy; the repo
            // copy predates the relay decision). Re-derive the same O29
            // restriction statelessly: offer a relayed DM only to its
            // recipient or a current breadcrumb candidate; flood when no
            // breadcrumbs exist (same fallback as relay()). Uses the
            // authoritative (repo-backed) suspend lookup, NOT the sync
            // snapshot — the snapshot can be stale by offer time, and this
            // filter must see the same view relay() saw when it decided.
            if (breadcrumbs != null && msg.type == MessageType.DIRECT &&
                msg.recipientId != null && msg.senderId != localUserId &&
                msg.recipientId != peerUserId
            ) {
                val candidates = breadcrumbs.candidatePeers(msg.recipientId)
                if (candidates.isNotEmpty() && peerUserId !in candidates) continue
            }
            routedFiltered.add(msg)
        }
        if (breadcrumbs == null) return budgetOfferBatch(routedFiltered)

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
        return budgetOfferBatch(preferred + rest)
    }

    /**
     * O132 head-of-line-block fix: bound an offer batch so its serialized frame
     * can't exceed the GossipSession 4 MB read guard. Skip any single message
     * over [MAX_OFFERABLE_MESSAGE_BYTES] entirely (a legacy/oversized message
     * that would otherwise reset the session every round and wedge ALL sync to
     * the peer), and stop adding once the cumulative budget is reached. Order is
     * preserved, so the freshest/priority-shaped head still goes first.
     */
    private fun budgetOfferBatch(msgs: List<RumorMessage>): List<RumorMessage> {
        var acc = 0
        val out = ArrayList<RumorMessage>(msgs.size)
        for (msg in msgs) {
            val sz = msg.approxWireBytes()
            if (sz > com.rumor.mesh.core.model.MAX_OFFERABLE_MESSAGE_BYTES) {
                RumorLog.w(TAG, "Skipping un-offerable ${msg.id.take(8)}… (${sz}B > frame budget) — never wedge the link")
                continue
            }
            if (acc + sz > com.rumor.mesh.core.model.MAX_OFFER_BATCH_BYTES) break
            acc += sz
            out.add(msg)
        }
        return out
    }

    fun knownMessageIds(): Set<String> = duplicateFilter.knownIds()
    val queueDepth: Int get() = scheduler.queueDepth

    /**
     * O42: whole-store `(sentAtMs, id)` snapshot for RBSR reconciliation.
     * Handed to the transport as a suspend provider so the (possibly 50k-row)
     * query only runs when a session's adaptive gate actually selects RBSR.
     */
    suspend fun rbsrSnapshot(): List<com.rumor.mesh.core.sync.RbsrItem> =
        messageStore.rbsrItems(RBSR_SNAPSHOT_LIMIT)

    /**
     * Deeper O92: fetch specific messages from the durable store by id, for the
     * session's exact-diff paths (an RBSR peer requests precisely what it lacks,
     * which may be older than anything in the capped offer batch). Bounded so a
     * malicious Request frame can't turn into an unbounded store scan.
     */
    suspend fun messagesByIds(ids: List<String>): List<RumorMessage> =
        ids.take(MAX_BY_ID_FETCH).mapNotNull { messageStore.getById(it) }

    /**
     * O92: rebuild the volatile outbound state from the durable store on mesh
     * start. Without this, a phone that restarts offers nothing on its next
     * exchange even though its repo is full (field-observed: two phones of old
     * broadcasts traded "0 sent, 0 received") — [messagesForExchange] drains the
     * in-memory [Scheduler], which starts empty every launch.
     *
     * Two reseeds, both necessary:
     *  - **Scheduler** ← offer-eligible content, so we have something to send.
     *  - **DuplicateFilter** ← recent stored ids, so [knownMessageIds] reports an
     *    accurate summary and peers don't re-offer us everything we already hold.
     *
     * Reseeded messages carry their stored [RumorMessage.hopsToLive]; no hop
     * mutation happens here, and the peer's want-set still dedups, so this cannot
     * re-flood content a peer already has. Idempotent — safe to call once per start.
     */
    suspend fun reseedFromStore() {
        duplicateFilter.seed(messageStore.knownIds(DEDUP_RESEED_LIMIT))
        val offerable = messageStore.offerable(OFFER_RESEED_LIMIT)
        offerable.forEach { scheduler.enqueue(it) }
        canaryMetrics.publish(scheduler.queueDepth)
        RumorLog.i(TAG, "Reseeded ${offerable.size} messages into scheduler on start")
    }

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
        // A message we authored, handed back to us by a peer, is always an echo —
        // never news. Drop it before the handlers below so we don't re-relay it
        // or (for SELF_PRESENCE) record our own presence as if it were a peer's.
        // BUT record the id (after full verification — §2: never record an
        // unverified id, or a forged "self" message could blackhole a real one):
        // an unrecorded echo id stays out of our summaries and RBSR snapshot, so
        // peers re-ship the same echoes every round, forever (field-found
        // 2026-07-18: ~400KB/round between two phones, driven by the RBSR diff
        // over beacon ids the author itself never held).
        if (source == MessageSource.PEER &&
            rawMsg.senderId == identityProvider.identity.value?.userId
        ) {
            messageStore.ingest(clampTtl(rawMsg), persist = false)
            return
        }

        val clamped = clampTtl(rawMsg)
        // Per-transport trust gate. The BRIDGE_UNSIGNED sentinel is honored only
        // for messages handed in by a local bridge plugin — a network peer cannot
        // forge it to skip Ed25519 verification.
        val bridged = source == MessageSource.LOCAL_BRIDGE && clamped.signature == BRIDGE_UNSIGNED
        val sigFailuresBefore = messageStore.sigFailureCount
        val isNew = if (bridged) {
            duplicateFilter.recordAndCheck(clamped.id)
        } else {
            // SELF_PRESENCE is ephemeral control traffic: full verify + dedup
            // record + tracker + relay, but never archived — MeshViewTracker
            // holds the (6-min-fresh) state it feeds, and persisting every 90s
            // beacon made stores 99% bloat and pushed them over the RBSR gate.
            messageStore.ingest(clamped, persist = clamped.type != MessageType.SELF_PRESENCE)
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

        // O95: fold the sender's HLC stamp into local state — after full
        // verification (a forged message must not advance our clock) and
        // drift-clamped inside HlcClock against adversarial far-future values.
        msg.hlcTimestamp?.let { hlc.update(it) }

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

        // O40: signed delete-on-ACK request. Verify the issuer is the
        // sender or recipient of the targeted message, then purge from
        // the local store. The relay path below still propagates the
        // MESSAGE_DELETE itself so downstream nodes also purge.
        if (msg.type == MessageType.MESSAGE_DELETE) {
            scope.launch { handleMessageDelete(msg) }
        }

        // O38: PREKEY_PUBLISH — verify the publisher binding + sig and
        // cache the freshest valid prekey for that publisher so a future
        // composeDirect to them can DH against the short-lived prekey
        // (receiver-side FS) instead of the long-term static identity.
        if (msg.type == MessageType.PREKEY_PUBLISH) {
            handlePrekeyPublish(msg)
        }

        // O98: SELF_PRESENCE — record the sender's self-declared mode (self-
        // presence, so senderId IS the subject: first-hand, no hearsay). Feeds
        // the persistence planner's per-node degree budget. Relay still happens
        // below so the beacon propagates broadcast-style.
        if (msg.type == MessageType.SELF_PRESENCE) {
            handleSelfPresence(msg)
        }

        // O79: room message dispatch. Match the routing tag against the
        // local subscription cache; on match, decrypt (ENCRYPTED) or
        // pass through (OPEN), emit the resulting plaintext-bearing
        // message to inbox. Relay still happens below regardless — room
        // messages are broadcast-tier and propagate through the mesh
        // even if this node isn't subscribed.
        if (msg.type == MessageType.ROOM_MESSAGE) {
            scope.launch { handleRoomMessage(msg) }
        }

        // O79: ROOM_MESSAGE inbox emission is handled by handleRoomMessage above
        // ONLY on subscription match. Skip the unconditional emit here so that
        // non-subscribed nodes (relay-only) don't surface the message in their
        // local inbox. Relay still happens unconditionally below.
        if (msg.type != MessageType.ROOM_MESSAGE) {
            // O41: identity rotation. The outer signature on `msg` is by the *new*
            // key (already verified above). We additionally check the *inner*
            // continuity signature against the existing contact's old public key —
            // proves the rotation is authorized by the old-key holder. Only then
            emitToInbox(msg)
        }
        relay(msg, autoRelayIds)
    }

    /**
     * O79 receive-side handler. No-op when [roomSubscriptionProvider]
     * is null. On match:
     *  - OPEN: emit the carrier message to inbox (its payload.content
     *    is the plaintext already).
     *  - ENCRYPTED: decrypt the inner envelope via
     *    [MultiRecipientEnvelopeCodec.decrypt]; on success, emit a
     *    synthetic message with the decrypted plaintext as
     *    `payload.content`.
     *  - No match: drop (the message was addressed to a room we're not
     *    in; relay still happens elsewhere).
     */
    private suspend fun handleRoomMessage(msg: RumorMessage) {
        val provider = roomSubscriptionProvider ?: return
        val tagB64 = msg.roomRoutingTag ?: return
        val tag = runCatching { Base64Codec.decode(tagB64) }.getOrNull() ?: return

        val match = RoomTagMatcher.match(
            inboundTag = tag,
            messageId = msg.id,
            openSubscriptions = provider.openRoomIds(),
            encryptedSubscriptions = provider.encryptedRoomSubscriptions(),
        ) ?: return  // not subscribed; drop receive-side (relay still happened)

        when (match) {
            is RoomTagMatcher.MatchResult.OpenMatch -> {
                // OPEN rooms — payload.content is plaintext, signed by sender,
                // verified at outer-sig check earlier in processIncoming.
                emitToInbox(msg)
            }
            is RoomTagMatcher.MatchResult.EncryptedMatch -> {
                val xPriv = provider.localX25519StaticPrivate() ?: return
                try {
                    val envelopeJson = msg.encryptedPayload ?: return
                    val envelope = runCatching {
                        WireJson.decodeFromString<MultiRecipientEnvelope>(envelopeJson)
                    }.getOrNull() ?: return
                    val localId = identityProvider.identity.value?.userId ?: return
                    val plaintext = MultiRecipientEnvelopeCodec.decrypt(envelope, localId, xPriv) ?: return
                    // Synthesize a plaintext-bearing carrier for inbox emission.
                    val synthetic = msg.copy(
                        payload = MessagePayload(ContentType.TEXT, plaintext.decodeToString()),
                        encryptedPayload = null,
                    )
                    emitToInbox(synthetic)
                } finally {
                    // O39 / O91: the provider returns a freshly derived X25519
                    // private each call (from the Ed25519 identity seed); zero
                    // it once the codec is done so a heap dump can't recover it.
                    xPriv.fill(0)
                }
            }
        }
    }

    /** O38 — exposed for sender-side consultation in composeDirect (follow-up). */
    val prekeyCache: PrekeyCache = PrekeyCache()

    /**
     * O95 — the node's Hybrid Logical Clock. Stamped into `_ext.hlc` on every
     * compose, folded on every verified receive. Public so the host can wire
     * persistence (restore at start, onAdvance → durable store) — HLC state
     * must survive restart or a behind-clock node composes below its own
     * pre-restart stamps.
     */
    val hlc = com.rumor.mesh.core.time.HlcClock { clock.now() }

    /**
     * O124 — host-wired on-demand pulse (composes a SELF_PRESENCE with the
     * host's current mode + adjacency). Null disables solicited replies.
     * Self-echoes never reach [handleSelfPresence] (dropped upstream) and
     * dedup fires the handler at most once per beacon, so this cannot loop.
     */
    var presencePulse: (() -> Unit)? = null
    private val presenceReplyGate = com.rumor.mesh.core.routing.PresenceReplyGate(clock)

    private fun handleSelfPresence(msg: RumorMessage) {
        val tracker = meshView ?: return
        val json = msg.payload?.content ?: return
        val payload = runCatching {
            WireJson.decodeFromString<SelfPresencePayload>(json)
        }.getOrNull() ?: return
        // Subject is the beacon author. authorizedAtMs is the beacon's own
        // timestamp; MeshViewTracker clamps a future value and applies decay.
        // recentlyExchangedWith is the author's advertised adjacency.
        val wasFresh = tracker.hasFresh(msg.senderId)
        tracker.record(msg.senderId, payload.mode, payload.recentlyExchangedWith, payload.authorizedAtMs)
        // O124 solicited response: a pulse from an unknown-or-stale peer gets our
        // own pulse back (they're as blind to us as we were to them), gated
        // per-peer by OUR clock so probe spam can't force reply spam.
        val pulse = presencePulse ?: return
        if (presenceReplyGate.shouldReply(msg.senderId, wasFresh)) {
            // O124/O127 instrumentation: one line per solicited pulse fire, so a
            // sybil-storm harness can count amplification directly instead of
            // inferring it from evictable downstream broadcasts.
            RumorLog.d(TAG, "presence solicit-reply → ${msg.senderId.take(8)} (wasFresh=$wasFresh)")
            pulse()
        }
    }

    private fun handlePrekeyPublish(msg: RumorMessage) {
        val json = msg.payload?.content ?: return
        val payload = runCatching {
            WireJson.decodeFromString<com.rumor.mesh.core.model.PrekeyPublish>(json)
        }.getOrNull() ?: return
        when (val r = PrekeyVerifier.verify(payload)) {
            is PrekeyVerifier.Result.Accepted -> prekeyCache.put(payload)
            is PrekeyVerifier.Result.Rejected ->
                RumorLog.w("GossipEngine", "O38 prekey rejected: ${r.reason}")
        }
    }

    private suspend fun handleMessageDelete(msg: RumorMessage) {
        val json = msg.payload?.content ?: return
        val payload = runCatching {
            WireJson.decodeFromString<com.rumor.mesh.core.model.MessageDeletePayload>(json)
        }.getOrNull() ?: return
        val result = MessageDeleteVerifier.verify(payload) { id -> messageStore.getById(id) }
        when (result) {
            is MessageDeleteVerifier.Result.Authorized -> {
                if (result.target != null) {
                    messageStore.deleteById(payload.messageId)
                    RumorLog.i("GossipEngine", "O40 delete applied for ${payload.messageId}")
                }
            }
            is MessageDeleteVerifier.Result.Rejected -> {
                RumorLog.w("GossipEngine", "O40 delete rejected: ${result.reason}")
            }
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
            MessageType.BRIDGE_VOUCHED,
            MessageType.ROOM_MESSAGE -> {
                // Room messages relay like broadcasts: every honest relay
                // forwards regardless of subscription state. Routing is via
                // the opaque tag in _ext.rt — only subscribed receivers
                // recognize their own room's tag and emit to inbox; everyone
                // else just propagates. autoRelay boost applies the same way
                // as BROADCAST for room messages from priority-peer senders.
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
            MessageType.KEYWORD_FILTER_PUBLISH,
            MessageType.SELF_PRESENCE,
            MessageType.MESSAGE_DELETE,
            MessageType.PREKEY_PUBLISH -> {
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
            MessageType.KEYWORD_FILTER_PUBLISH,
            MessageType.SELF_PRESENCE,
            MessageType.MESSAGE_DELETE,
            MessageType.PREKEY_PUBLISH,
            MessageType.BRIDGE_VOUCHED,
            MessageType.ROOM_MESSAGE -> MAX_BROADCAST_HOPS
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
        // O95: HLC stamp rides _ext (unsigned, like every _ext field) so it can
        // be applied after signing without invalidating the sig.
        return unsigned.copy(signature = sig).withHlc(hlc.tick())
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
