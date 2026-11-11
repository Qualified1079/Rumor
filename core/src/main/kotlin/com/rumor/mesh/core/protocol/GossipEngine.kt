package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.BridgeVouchedPayload
import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.ChunkRequest
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.MAX_ROUTED_HOPS
import com.rumor.mesh.core.model.RoomActionKind
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.TrustLevel
import com.rumor.mesh.core.platform.Base64Codec
import com.rumor.mesh.core.model.floodedHops
import com.rumor.mesh.core.model.routedHops
import com.rumor.mesh.core.model.withTtlSplit
import com.rumor.mesh.core.policy.InboxFilter
import com.rumor.mesh.core.routing.BreadcrumbCache
import com.rumor.mesh.core.mode.ModeState
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
import com.rumor.mesh.core.wire.WireJson
import com.rumor.mesh.core.wire.hlcTimestamp

private const val TAG = "GossipEngine"
/** O92 reseed bounds. Offer set is capped near the scheduler's own backlog cap;
 *  dedup seed is wider so our summary reflects everything we hold, not just what's offerable. */
private const val OFFER_RESEED_LIMIT = 4_000
private const val DEDUP_RESEED_LIMIT = 50_000
/** O42: RBSR snapshot cap — matches the store cap (50k, 200k static) order of magnitude. */
private const val RBSR_SNAPSHOT_LIMIT = 50_000
/** Deeper O92: per-exchange ceiling on fetch-by-id so a hostile Request can't scan the store. */
private const val MAX_BY_ID_FETCH = 500

// O234: weeks of partition are normal under O55, so a request stays answerable a while.
private const val PRIORITY_REQUEST_TTL_MS = 7L * 24 * 60 * 60 * 1000
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
     * O89 — write-authority resolver for inbound room messages. Null disables
     * enforcement (the ungoverned feed, and engines without rooms).
     */
    private val roomAuthorityProvider: RoomAuthorityProvider? = null,
    /**
     * O187 — room metadata/roster store. Null means this engine does not track
     * rooms (the simulator's feed-only nodes, `:node`), in which case inbound
     * lifecycle messages are still relayed but change no local state.
     */
    private val roomRepo: com.rumor.mesh.core.data.RoomRepository? = null,
    /**
     * O98 (MeshView substrate) — optional tracker fed by inbound SELF_PRESENCE
     * beacons. When non-null, every verified SELF_PRESENCE records the sender's
     * mode + advertised neighbours (with recency decay); the O98 persistence
     * planner reads [MeshViewTracker.assembleView] to pick a degree-bounded
     * backbone. Null disables tracking — relay of the beacon still happens.
     */
    private val meshView: MeshViewTracker? = null,
    /**
     * O205 — live device mode, read for the [livenessWindowMs] cadence only (same
     * optional-and-nullable shape as MessageStore/Scheduler use for their boosts).
     * Null = fall back to [DEFAULT_LIVENESS_WINDOW_MS].
     */
    private val modeState: ModeState? = null,
) {
    var livenessRouting: Boolean
        get() = offers.livenessRouting
        set(value) { offers.livenessRouting = value }

    var livenessWindowOverrideMs: Long?
        get() = offers.livenessWindowOverrideMs
        set(value) { offers.livenessWindowOverrideMs = value }

    val livenessWindowMs: Long get() = offers.livenessWindowMs

    /** O79 receive-side subscription snapshot consumed by ROOM_MESSAGE dispatch. */
    /**
     * O89 — resolves a room's write-authority state for the receive path.
     *
     * Kept separate from [RoomSubscriptionProvider] (which answers "am I in
     * this room?") because this answers "may this sender post to it?", and the
     * real implementation needs a `RoomRepository` read that does not exist yet
     * (O187). A null provider means no enforcement, which is correct for the
     * ungoverned feed (O154/O186) and for engines with no rooms at all.
     */
    interface RoomAuthorityProvider {
        /**
         * Whether [roomId] requires a moderator-issued posting certificate.
         * False for the ungoverned feed; true for governed rooms (O155).
         */
        fun requiresPostingCert(roomId: String): Boolean

        /**
         * Whether [moderatorId] is *currently* authorized to issue posting
         * certs for [roomId]. Checked per message rather than trusted from the
         * cert, so a demoted moderator's outstanding certs stop being honoured
         * without waiting for them to expire.
         */
        fun isModerator(roomId: String, moderatorId: String): Boolean

        /**
         * Whether [roomId] admits posts only from members. True for every
         * bounded-membership room; false for the ungoverned feed, which by
         * definition has no roster.
         */
        fun requiresMembershipToPost(roomId: String): Boolean

        fun isMember(roomId: String, userId: String): Boolean

        /**
         * Checked unconditionally, ahead of every other gate — a ban must hold
         * regardless of the room's posting policy and regardless of any cert
         * the banned user still holds from before the ban.
         */
        fun isBanned(roomId: String, userId: String): Boolean
    }

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
    /**
     * O95 — the node's Hybrid Logical Clock. Stamped into `_ext.hlc` on every
     * compose, folded on every verified receive. Public so the host can wire
     * persistence (restore at start, onAdvance → durable store) — HLC state
     * must survive restart or a behind-clock node composes below its own
     * pre-restart stamps.
     */
    val hlc = com.rumor.mesh.core.time.HlcClock { clock.now() }

    private val outbound = OutboundComposer(
        messageStore, identityProvider, scheduler, canaryMetrics, clock, hlc,
    )

    fun sentPlaintextFor(messageId: String): String? = direct.sentPlaintextFor(messageId)

    // O234: a PRIORITY_LINK_ACCEPT is honoured only against a request we sent.
    // In memory, so a restart forgets them and the user simply asks again.
    private val outstandingPriorityRequests = com.rumor.mesh.core.platform.BoundedFifoMap<String, Long>(256)

    private val _incomingMessages = MutableSharedFlow<RumorMessage>(extraBufferCapacity = 256)
    val incomingMessages: SharedFlow<RumorMessage> = _incomingMessages

    private val _deliveryEvents = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val deliveryEvents: SharedFlow<String> = _deliveryEvents

    /**
     * O202 — end-to-end delivery receipts. Emits the messageId of a DIRECT DM
     * we authored once its final recipient sent back a [MessageType.DIRECT_ACK]
     * and that ACK reached us. Distinct from [deliveryEvents], which fires on
     * mere peer-hop acceptance (session-layer `Ack`) and says nothing about the
     * message ever reaching the person it was addressed to. The host/UI marks
     * the DM "delivered" (double-check) on this signal.
     */
    private val _deliveryReceipts = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val deliveryReceipts: SharedFlow<String> = _deliveryReceipts

    /**
     * Fires when the user queues something (post, DM, manual relay, room post) so
     * a transport holding a live link can run its next round now rather than at
     * the round tick. Machine traffic (ACKs, presence, chunk control) does not
     * fire it — an auto-reply kicking a round that produces an auto-reply loops.
     * Relayed traffic does not either: the [relayBatcher] delay is deliberate.
     */
    private val _userSendQueued = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val userSendQueued: SharedFlow<Unit> = _userSendQueued

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

    private val rooms = RoomMessaging(
        identityProvider, clock, roomRepo, roomAuthorityProvider, roomSubscriptionProvider,
        outbound, _userSendQueued, ::emitToInbox,
    )

    private val direct = DirectMessaging(
        identityProvider, dmEnvelopeRegistry, messageStore, scope, outbound, _userSendQueued, _outboundBridgedDm,
    )

    private val presence = PresenceBeacons(identityProvider, clock, meshView, outbound)

    private val offers = OfferSelector(
        topologyTracker, scheduler, messageStore, identityProvider, breadcrumbs, clock, modeState,
    )

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
                offers.recordDirectExchange(result.peerUserId)   // O198 liveness signal (first-hand, routing-cadence)
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

            for (msg in rooms.historyFirst(result.messagesReceived)) {
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
        val base = outbound.buildMessage(
            identity = identity,
            type = MessageType.BROADCAST,
            hopsToLive = DEFAULT_BROADCAST_HOPS,
            payload = MessagePayload(ContentType.TEXT, text),
        )
        val msg = base.applyThreadAndMentionExt(replyTo, mentions)
        outbound.enqueueImmediate(msg)
        _userSendQueued.tryEmit(Unit)
        scope.launch { messageStore.ingestOwn(msg) }
        return msg
    }

    fun composeDirect(
        recipientId: String,
        recipientPublicKey: ByteArray,
        text: String,
        replyTo: String? = null,
        mentions: List<String> = emptyList(),
        hopsToLive: Int? = null,
    ): RumorMessage? = direct.composeDirect(recipientId, recipientPublicKey, text, replyTo, mentions, hopsToLive)

    fun manualRelay(msg: RumorMessage) {
        // Same invariant as relay(): unsigned bridge traffic is never re-relayed
        // onto the signed mesh — re-broadcasting it here would launder a foreign
        // message this node never received over a verified link. The store-backed
        // msg the Feed hands us carries its persisted trustLevel (O162), so this
        // guard is meaningful even after a reload.
        if (msg.trustLevel == TrustLevel.BRIDGED) return
        RumorLog.i(TAG, "Manual relay of ${msg.id.take(8)}")
        outbound.enqueueImmediate(messageStore.boostHopsForManualRelay(msg))
        _userSendQueued.tryEmit(Unit)
        scope.launch { messageStore.markRelayed(msg.id) }
    }

    fun composeDirectAck(
        originalSenderId: String,
        ackedMessageId: String,
    ): RumorMessage? = direct.composeDirectAck(originalSenderId, ackedMessageId)

    fun composeChunkRequest(
        transferId: String,
        missingIndices: List<Int>,
        originalSenderId: String,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val body = WireJson.encodeToString(ChunkRequest(transferId, missingIndices))
        val msg = outbound.buildMessage(
            identity = identity,
            type = MessageType.CHUNK_REQUEST,
            hopsToLive = DEFAULT_DIRECT_HOPS,
            payload = MessagePayload(ContentType.CONTROL, body),
            recipientId = originalSenderId,
        )
        outbound.enqueueImmediate(msg)
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
        val msg = outbound.buildMessage(
            identity = identity,
            type = MessageType.TRANSFER_CANCEL,
            hopsToLive = DEFAULT_DIRECT_HOPS,
            payload = MessagePayload(ContentType.CONTROL, body),
            recipientId = originalSenderId,
        )
        outbound.enqueueImmediate(msg)
        return msg
    }

    fun composeOutbound(
        type: MessageType,
        payload: MessagePayload,
        recipientId: String? = null,
    ): RumorMessage? = outbound.composeOutbound(type, payload, recipientId)

    /**
     * Request a mutual priority (persistent) link with [recipientId].
     * The recipient receives the request in their inbox and can accept via
     * [acceptPriorityLink].
     */
    fun composePriorityLinkRequest(recipientId: String): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val msg = outbound.buildMessage(
            identity = identity,
            type = MessageType.PRIORITY_LINK_REQUEST,
            hopsToLive = DEFAULT_DIRECT_HOPS,
            payload = MessagePayload(ContentType.CONTROL, ""),
            recipientId = recipientId,
        )
        outstandingPriorityRequests.put(recipientId, clock.now())
        outbound.enqueueImmediate(msg)
        return msg
    }

    /**
     * Accept an incoming [PRIORITY_LINK_REQUEST]. Marks the sender as a
     * priority peer locally and sends [PRIORITY_LINK_ACCEPT] back to them so
     * they can do the same on their side.
     */
    fun acceptPriorityLink(request: RumorMessage) {
        if (request.type != MessageType.PRIORITY_LINK_REQUEST) return
        if (request.recipientId != identityProvider.identity.value?.userId) return
        scope.launch {
            contactRepo.setPriorityPeer(request.senderId, true)
            RumorLog.i(TAG, "Accepted priority link from ${request.senderId.take(8)}…")
        }
        val identity = identityProvider.identity.value ?: return
        val reply = outbound.buildMessage(
            identity = identity,
            type = MessageType.PRIORITY_LINK_ACCEPT,
            hopsToLive = DEFAULT_DIRECT_HOPS,
            payload = MessagePayload(ContentType.CONTROL, ""),
            recipientId = request.senderId,
        )
        outbound.enqueueImmediate(reply)
    }

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
        val msg = outbound.buildMessage(
            identity = identity,
            type = MessageType.MESSAGE_DELETE,
            hopsToLive = DEFAULT_BROADCAST_HOPS,
            payload = MessagePayload(ContentType.CONTROL, WireJson.encodeToString(payload)),
        )
        // Purge locally first — the relayed broadcast will purge it
        // everywhere else as it propagates.
        messageStore.deleteById(messageId)
        outbound.enqueueImmediate(msg)
        return msg
    }

    suspend fun composeRoomCreate(
        nonce: String,
        name: String,
        membershipPolicy: com.rumor.mesh.core.model.RoomMembershipPolicy,
        postingPolicy: com.rumor.mesh.core.model.RoomPostingPolicy,
        allowMedia: Boolean,
    ): RumorMessage? = rooms.composeRoomCreate(nonce, name, membershipPolicy, postingPolicy, allowMedia)
        ?.also { messageStore.ingestOwn(it) }

    suspend fun composeRoomInvite(
        roomId: String,
        invitedUserId: String,
        encryptedRoomKey: String,
        expiresAtMs: Long,
    ): RumorMessage? = rooms.composeRoomInvite(roomId, invitedUserId, encryptedRoomKey, expiresAtMs)
        ?.also { messageStore.ingestOwn(it) }

    suspend fun composeRoomAction(
        roomId: String,
        kind: RoomActionKind,
        target: String,
        reason: String? = null,
    ): RumorMessage? = rooms.composeRoomAction(roomId, kind, target, reason)
        ?.also { messageStore.ingestOwn(it) }

    fun composeRoomMessage(
        routingTag: ByteArray,
        plaintext: String,
        recipients: List<MultiRecipientEnvelopeCodec.Recipient> = emptyList(),
        replyTo: String? = null,
        mentions: List<String> = emptyList(),
        postingCert: com.rumor.mesh.core.model.RoomPostingCert? = null,
    ): RumorMessage? = rooms.composeRoomMessage(routingTag, plaintext, recipients, replyTo, mentions, postingCert)
        ?.also { scope.launch { messageStore.ingestOwn(it) } }

    fun composeSelfPresence(
        mode: UserMode,
        recentlyExchangedWith: List<String> = emptyList(),
    ): RumorMessage? = presence.composeSelfPresence(mode, recentlyExchangedWith)

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
        val msg = outbound.buildMessage(
            identity = identity,
            type = MessageType.BRIDGE_VOUCHED,
            hopsToLive = DEFAULT_BROADCAST_HOPS,
            payload = MessagePayload(originContentType, WireJson.encodeToString(body)),
        )
        outbound.enqueueImmediate(msg)
        return msg
    }

    // ── Transport supply ──────────────────────────────────────────────────────

    suspend fun messagesForExchange(peerUserId: String): List<RumorMessage> =
        offers.messagesForExchange(peerUserId)

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
            // Same refusal as messagesForExchange: this path reads the store
            // directly and would otherwise hand unsigned bridge traffic to a peer.
            .filter { it.trustLevel != TrustLevel.BRIDGED }

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

    /**
     * O120: one periodic hygiene entry point for every subsystem that accretes
     * state — three of them shipped prunes that nothing ever called, so tables
     * grew without bound over exactly the months-long uptimes O55 targets.
     * MeshRuntime owns the tick; keep any new prunable state hooked in HERE so
     * the next orphan can't happen (PruneWiringInvariantTest greps for this).
     */
    /** Re-age presence statuses; see [com.rumor.mesh.core.routing.OnlineStatusTracker.refresh]. */
    fun refreshPresence() = onlineStatusTracker.refresh()

    fun pruneMaintenance() {
        breadcrumbs?.pruneOld()
        topologyTracker.pruneStale()
        onlineStatusTracker.pruneStale()
        // O177: MeshViewTracker.pruneStale() was the fourth orphaned prune — a
        // freshness sweep with zero call sites. It's bounded by maxPeers (LRU),
        // but without this stale beacons linger as ghost routing anchors past
        // their freshness window. Hooked in here like the other three.
        meshView?.pruneStale()
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
            // O121(b): traversed hops = the type's own starting budget minus
            // what's left. Using the broadcast budget for DIRECTs (budget 15)
            // made every DM breadcrumb clamp to the floor — meaningless once
            // routing consumes hopCount.
            val startBudget =
                if (msg.type == MessageType.DIRECT) DEFAULT_DIRECT_HOPS else DEFAULT_BROADCAST_HOPS
            breadcrumbs?.record(
                targetUserId = msg.senderId,
                fromPeerId = fromPeerId,
                hopCount = (startBudget - msg.hopsToLive).coerceAtLeast(1),
            )
        }

        if (msg.type == MessageType.PRIORITY_LINK_ACCEPT &&
            msg.recipientId == identityProvider.identity.value?.userId) {
            val requestedAt = outstandingPriorityRequests.remove(msg.senderId)
            if (requestedAt != null && clock.now() - requestedAt <= PRIORITY_REQUEST_TTL_MS) {
                scope.launch { contactRepo.setPriorityPeer(msg.senderId, true) }
            } else {
                RumorLog.w(TAG, "Ignoring unsolicited PRIORITY_LINK_ACCEPT from ${msg.senderId.take(8)}…")
            }
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
            presence.handleSelfPresence(msg)
        }

        // O202: on-receipt delivery ACK. A DIRECT DM that actually reached its
        // final recipient (recipientId == us) is acknowledged straight back to
        // the sender, giving them an end-to-end "delivered" signal that survives
        // low duty-cycle meshes far better than hoping for a reply. Gated on
        // recipientId == self so a relay forwarding someone else's DM never
        // ACKs it; bridged traffic is skipped (the synthetic senderId has no
        // Rumor-mesh return path); DIRECT_ACK itself is NOT re-ACKed, so there
        // is no acknowledgement loop.
        val localUserId = identityProvider.identity.value?.userId
        if (!bridged && msg.type == MessageType.DIRECT && msg.recipientId == localUserId &&
            msg.senderId != localUserId) {
            composeDirectAck(originalSenderId = msg.senderId, ackedMessageId = msg.id)
        }

        // O202: inbound DIRECT_ACK addressed to us confirms one of our own DMs
        // was delivered end-to-end. Surface the acked messageId on
        // deliveryReceipts; never emit it to the inbox (pure control signal).
        // O233: the ACK is signed, but by whoever sent it — so only its addressee
        // may confirm a DM. Without this any node could mark any of our DMs
        // delivered. An own DM evicted from the store loses its receipt; that
        // only costs the ✓✓, never a false one.
        if (msg.type == MessageType.DIRECT_ACK && msg.recipientId == localUserId) {
            msg.payload?.content?.takeIf { it.isNotBlank() }?.let { ackedId ->
                scope.launch {
                    val original = messageStore.getById(ackedId)
                    if (original != null &&
                        original.type == MessageType.DIRECT &&
                        original.senderId == localUserId &&
                        original.recipientId == msg.senderId
                    ) {
                        _deliveryReceipts.emit(ackedId)
                    } else {
                        RumorLog.w(TAG, "Ignoring DIRECT_ACK from ${msg.senderId.take(8)}… for a DM it was not sent")
                    }
                }
            }
        }

        // O79: room message dispatch. Match the routing tag against the
        // local subscription cache; on match, decrypt (ENCRYPTED) or
        // pass through (OPEN), emit the resulting plaintext-bearing
        // message to inbox. Relay still happens below regardless — room
        // messages are broadcast-tier and propagate through the mesh
        // even if this node isn't subscribed.
        if (msg.type == MessageType.ROOM_MESSAGE) {
            scope.launch { rooms.handleRoomMessage(msg) }
        }
        if (msg.type == MessageType.ROOM_CREATE ||
            msg.type == MessageType.ROOM_INVITE ||
            msg.type == MessageType.ROOM_ACTION
        ) {
            // O187: handled INLINE, not via scope.launch, because order within a
            // batch is load-bearing. Launching per message raced CREATE against
            // the INVITE that depends on it: the invite ran first, found no room
            // to check moderator authority against, and was dropped — and dedup
            // had already marked it seen, so it was never retried. An exchange
            // commonly carries a room's whole history at once.
            rooms.handleRoomLifecycle(msg)
        }

        // O79: ROOM_MESSAGE inbox emission is handled by handleRoomMessage above
        // ONLY on subscription match. Skip the unconditional emit here so that
        // non-subscribed nodes (relay-only) don't surface the message in their
        // local inbox. Relay still happens unconditionally below.
        // DIRECT_ACK is a control signal consumed via deliveryReceipts above, not
        // a user-visible message — keep it out of the inbox like ROOM_MESSAGE.
        if (msg.type != MessageType.ROOM_MESSAGE && msg.type != MessageType.DIRECT_ACK) {
            // O41: identity rotation. The outer signature on `msg` is by the *new*
            // key (already verified above). We additionally check the *inner*
            // continuity signature against the existing contact's old public key —
            // proves the rotation is authorized by the old-key holder. Only then
            emitToInbox(msg)
        }
        relay(msg, autoRelayIds)
    }

    /** O38 — exposed for sender-side consultation in composeDirect (follow-up). */
    val prekeyCache: PrekeyCache = PrekeyCache()

    var presencePulse: (() -> Unit)?
        get() = presence.presencePulse
        set(value) { presence.presencePulse = value }

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
            // O187: room lifecycle relays broadcast-style. A roster change has
            // to reach members who were offline when it happened, or a kick/ban
            // silently fails to propagate and nodes disagree about who may post.
            MessageType.ROOM_CREATE,
            MessageType.ROOM_INVITE,
            MessageType.ROOM_ACTION,
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
            // DIRECT_ACK (O202) routes identically to a DIRECT DM: next-hop back
            // toward the original sender via breadcrumbs, dropped once it reaches
            // that sender (recipientId == self).
            MessageType.DIRECT, MessageType.DIRECT_ACK -> {
                val localId = identityProvider.identity.value?.userId
                if (msg.recipientId == localId) return

                // O29 per-peer routing decision. If breadcrumbs name candidate
                // peers for the recipient, take the ROUTED path; otherwise flood.
                val recipientId = msg.recipientId
                // `+ recipientId`: the RECIPIENT is always a legal next hop, crumb or
                // not. Without it a DM whose recipient is a direct peer that no crumb
                // happens to name is withheld from the one node it is FOR, reaching
                // them only on a later store-backfill re-offer (whose re-derivation
                // DOES allow the recipient — which is why this looked like mere
                // delay and stayed invisible). Under O55 duty-cycling a "later round"
                // can be hours away or never: a brief contact window is the whole
                // delivery opportunity. Found via a PerPeerRoutingTest flake whose
                // outcome depended on whether the marked copy was still queued when
                // the recipient happened to be offered to.
                val candidates = if (breadcrumbs != null && recipientId != null) {
                    breadcrumbs.candidatePeersSync(recipientId).toSet().takeIf { it.isNotEmpty() }
                        ?.plus(recipientId)
                } else null

                val withSplit = if (candidates != null) {
                    // Routed hop (O160): a directional unicast forward along a
                    // known breadcrumb toward the recipient. It does NOT spend
                    // any flood budget — a DM rides the breadcrumb trail for its
                    // full length, however long. Only the routedHops odometer
                    // advances, bounded by MAX_ROUTED_HOPS purely as a
                    // stale-trail/resource cap (dedup is the real loop-breaker).
                    // hopsToLive/floodedHops are preserved untouched, ready to be
                    // spent as flood budget only once the trail runs out.
                    val newRouted = msg.routedHops + 1
                    if (newRouted > MAX_ROUTED_HOPS) return
                    msg.withTtlSplit(
                        routedHops = newRouted,
                        floodedHops = msg.floodedHops,
                    ).copy(intendedPeers = candidates)
                } else {
                    // End of the known route: no breadcrumb for the next hop, so
                    // fall back to flood and spend the ordinary flood budget from
                    // here (dies at hopsToLive 0). intendedPeers stays null so the
                    // message is offered to every peer, not a routed subset.
                    val forwarded = messageStore.decrementHops(msg) ?: return
                    forwarded.withTtlSplit(
                        routedHops = forwarded.routedHops,
                        floodedHops = forwarded.hopsToLive,
                    )
                }
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
            MessageType.ROOM_CREATE,
            MessageType.ROOM_INVITE,
            MessageType.ROOM_ACTION,
            MessageType.ROOM_MESSAGE -> MAX_BROADCAST_HOPS
            MessageType.DIRECT, MessageType.DIRECT_ACK,
            MessageType.TRANSFER_METADATA,
            MessageType.CHUNK, MessageType.CHUNK_REQUEST,
            MessageType.TRANSFER_CANCEL,
            MessageType.PRIORITY_LINK_REQUEST,
            MessageType.PRIORITY_LINK_ACCEPT -> MAX_DIRECT_HOPS
        }
        return if (msg.hopsToLive > ceiling) msg.copy(hopsToLive = ceiling) else msg
    }

    /** Relayed messages go through the batcher for timing correlation resistance. */
    private fun enqueueRelayed(msg: RumorMessage) {
        relayBatcher.add(msg)
        canaryMetrics.recordRelay()
    }

    companion object {
        internal const val DEFAULT_BROADCAST_HOPS = 7
        internal const val DEFAULT_DIRECT_HOPS = 15
        internal const val MAX_BROADCAST_HOPS = DEFAULT_BROADCAST_HOPS
        internal const val MAX_DIRECT_HOPS = DEFAULT_DIRECT_HOPS

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
