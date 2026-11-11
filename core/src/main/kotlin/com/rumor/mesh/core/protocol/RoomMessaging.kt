package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.MultiRecipientEnvelope
import com.rumor.mesh.core.model.RoomAction
import com.rumor.mesh.core.model.RoomActionKind
import com.rumor.mesh.core.model.RoomCreate
import com.rumor.mesh.core.model.RoomInvite
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.roomActionSignableBytes
import com.rumor.mesh.core.model.roomCreateSignableBytes
import com.rumor.mesh.core.model.roomInviteSignableBytes
import com.rumor.mesh.core.platform.Base64Codec
import com.rumor.mesh.core.protocol.GossipEngine.Companion.DEFAULT_BROADCAST_HOPS
import com.rumor.mesh.core.wire.WireJson
import com.rumor.mesh.core.wire.roomPostingCert
import com.rumor.mesh.core.wire.roomRoutingTag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString

private const val TAG = "GossipEngine"

internal class RoomMessaging(
    private val identityProvider: IdentityProvider,
    private val clock: com.rumor.mesh.core.Clock,
    private val roomRepo: com.rumor.mesh.core.data.RoomRepository?,
    private val roomAuthorityProvider: GossipEngine.RoomAuthorityProvider?,
    private val roomSubscriptionProvider: GossipEngine.RoomSubscriptionProvider?,
    private val outbound: OutboundComposer,
    private val userSendQueued: MutableSharedFlow<Unit>,
    private val emitToInbox: suspend (RumorMessage) -> Unit,
) {
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
    /**
     * O187 — announce a new room. Signs the inner [RoomCreate] with the local
     * identity, which is therefore the creator (and so a moderator by
     * construction, per `RoomRecord`). Applies the record locally too: the
     * creator must not have to wait for its own broadcast to come back before
     * it can moderate.
     */
    suspend fun composeRoomCreate(
        nonce: String,
        name: String,
        membershipPolicy: com.rumor.mesh.core.model.RoomMembershipPolicy,
        postingPolicy: com.rumor.mesh.core.model.RoomPostingPolicy,
        allowMedia: Boolean,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val createdAtMs = clock.now()
        // Derived, not supplied — the caller picks a nonce, not an id.
        val roomId = RoomId.derive(identity.userId, nonce)
        val signed = roomCreateSignableBytes(
            roomId, name, identity.userId, createdAtMs, membershipPolicy, postingPolicy, allowMedia, nonce,
        )
        val create = RoomCreate(
            roomId = roomId,
            name = name,
            createdBy = identity.userId,
            createdAtMs = createdAtMs,
            membershipPolicy = membershipPolicy,
            postingPolicy = postingPolicy,
            allowMedia = allowMedia,
            nonce = nonce,
            signature = CryptoManager.sign(signed, identity.privateKeyBytes).toBase64(),
        )
        roomRepo?.upsert(
            com.rumor.mesh.core.data.RoomRecord(
                roomId, name, identity.userId, createdAtMs, membershipPolicy, postingPolicy, allowMedia,
            )
        )
        return outbound.composeOutbound(
            type = MessageType.ROOM_CREATE,
            payload = MessagePayload(ContentType.CONTROL, WireJson.encodeToString(create)),
        )
    }

    /**
     * O187 — invite [invitedUserId] to [roomId]. The caller supplies
     * [encryptedRoomKey] already wrapped to the invitee (this engine does not
     * hold room keys). Fails if the local identity is not a moderator, rather
     * than emitting an invite every receiver will reject.
     */
    suspend fun composeRoomInvite(
        roomId: String,
        invitedUserId: String,
        encryptedRoomKey: String,
        expiresAtMs: Long,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        if (roomRepo?.isModerator(roomId, identity.userId) == false) {
            RumorLog.w(TAG, "Refusing to compose ROOM_INVITE: not a moderator of ${roomId.take(8)}…")
            return null
        }
        val signed = roomInviteSignableBytes(roomId, invitedUserId, encryptedRoomKey, expiresAtMs, identity.userId)
        val invite = RoomInvite(
            roomId = roomId,
            invitedUserId = invitedUserId,
            encryptedRoomKey = encryptedRoomKey,
            expiresAtMs = expiresAtMs,
            moderatorId = identity.userId,
            moderatorSignature = CryptoManager.sign(signed, identity.privateKeyBytes).toBase64(),
        )
        roomRepo?.addMember(roomId, invitedUserId)
        return outbound.composeOutbound(
            type = MessageType.ROOM_INVITE,
            payload = MessagePayload(ContentType.CONTROL, WireJson.encodeToString(invite)),
        )
    }

    /** O187 — take a moderation action. Same moderator precondition as invite. */
    suspend fun composeRoomAction(
        roomId: String,
        kind: RoomActionKind,
        target: String,
        reason: String? = null,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        if (roomRepo?.isModerator(roomId, identity.userId) == false) {
            RumorLog.w(TAG, "Refusing to compose ROOM_ACTION: not a moderator of ${roomId.take(8)}…")
            return null
        }
        val takenAtMs = clock.now()
        val signed = roomActionSignableBytes(roomId, kind, target, reason, identity.userId, takenAtMs)
        val action = RoomAction(
            roomId = roomId,
            kind = kind,
            target = target,
            reason = reason,
            moderatorId = identity.userId,
            takenAtMs = takenAtMs,
            moderatorSignature = CryptoManager.sign(signed, identity.privateKeyBytes).toBase64(),
        )
        when (kind) {
            RoomActionKind.KICK_USER -> roomRepo?.removeMember(roomId, target)
            RoomActionKind.BAN_USER -> roomRepo?.addBan(roomId, target)
            RoomActionKind.UNBAN_USER -> roomRepo?.removeBan(roomId, target)
            RoomActionKind.REMOVE_MESSAGE -> Unit
        }
        return outbound.composeOutbound(
            type = MessageType.ROOM_ACTION,
            payload = MessagePayload(ContentType.CONTROL, WireJson.encodeToString(action)),
        )
    }

    fun composeRoomMessage(
        routingTag: ByteArray,
        plaintext: String,
        recipients: List<MultiRecipientEnvelopeCodec.Recipient> = emptyList(),
        replyTo: String? = null,
        mentions: List<String> = emptyList(),
        // O89: the moderator-issued cert proving we may post here. Required by
        // governed rooms; omitted for the ungoverned feed. Stamped BEFORE
        // signing (buildMessage) so it is covered by the rumor-msg-v3
        // transcript and cannot be stripped en route.
        postingCert: com.rumor.mesh.core.model.RoomPostingCert? = null,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        // O132: room messages are gossip-tier and unchunked — same cap as broadcasts.
        if (plaintext.length > com.rumor.mesh.core.model.MAX_BROADCAST_CONTENT_BYTES) {
            RumorLog.w(TAG, "Refusing room message: ${plaintext.length}B exceeds cap")
            return null
        }

        val tagBase64 = Base64Codec.encode(routingTag)
        val baseMsg: RumorMessage = if (recipients.isEmpty()) {
            // OPEN room — signed plaintext broadcast.
            outbound.buildMessage(
                identity = identity,
                type = MessageType.ROOM_MESSAGE,
                hopsToLive = DEFAULT_BROADCAST_HOPS,
                payload = MessagePayload(ContentType.TEXT, plaintext),
                roomRoutingTag = tagBase64,
                roomPostingCert = postingCert,
            )
        } else {
            // ENCRYPTED room — multi-recipient envelope.
            val envelope = MultiRecipientEnvelopeCodec.encrypt(
                plaintext = plaintext.toByteArray(Charsets.UTF_8),
                senderEd25519Private = identity.privateKeyBytes,
                senderId = identity.userId,
                senderEd25519Public = identity.publicKeyBytes,
                recipients = recipients,
                roomRoutingTag = tagBase64,
            )
            outbound.buildMessage(
                identity = identity,
                type = MessageType.ROOM_MESSAGE,
                hopsToLive = DEFAULT_BROADCAST_HOPS,
                encryptedPayload = WireJson.encodeToString(envelope),
                roomRoutingTag = tagBase64,
                roomPostingCert = postingCert,
            )
        }

        // O157: the routing tag is now stamped + signed inside buildMessage.
        // Only the unsigned O90 thread/mention metadata is applied here.
        val routedMsg = baseMsg.applyThreadAndMentionExt(replyTo, mentions)

        outbound.enqueueImmediate(routedMsg)
        userSendQueued.tryEmit(Unit)
        return routedMsg
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
    /**
     * O89 — may [msg]'s sender post to [roomId]?
     *
     * Fails CLOSED for a governed room: a missing, malformed, expired,
     * mis-scoped or mod-less cert is a drop, because the alternative ("allow
     * when unsure") is what made the governed label meaningless. Fails OPEN
     * only where there is genuinely nothing to enforce — no authority provider
     * wired (engines without rooms), or a room that declares it needs no cert
     * (the ungoverned feed, O154/O186).
     *
     * Checks, in order of cost:
     *  1. the room requires a cert at all;
     *  2. a cert is present and well-formed;
     *  3. the cert is scoped to THIS room (a cert for room B must not authorize
     *     a post to room A — the cert's roomId is signed, so this is a real
     *     binding, not a hint);
     *  4. the cert names THIS sender (otherwise anyone could replay someone
     *     else's valid cert);
     *  5. the moderator's own signature over the cert verifies, and the
     *     moderatorId binds to its pubkey ([RoomPostingCertVerifier]);
     *  6. the issuing moderator is authorized RIGHT NOW, not merely at issue
     *     time, so demotion takes effect without waiting for expiry;
     *  7. the validity window contains the current time.
     *
     * The cert itself cannot be altered (moderator-signed) nor stripped (bound
     * into the `rumor-msg-v3:` transcript, O89 note in signableBytes).
     */
    /**
     * O187 — apply a verified room-lifecycle decision to [roomRepo].
     *
     * **Load-bearing invariant: the carrying message's sender must BE the
     * author of the inner decision.** Relay preserves `senderId` and
     * `senderPublicKey` (only hop counters change), and `ingest` has already
     * proven `senderId == SHA-256(senderPublicKey)`, so that one rule gives
     * every hop the right key to check the inner signature against without a
     * contact lookup — and stops a third party wrapping someone else's room
     * decision in a fresh envelope of their own.
     *
     * The inner signature is what receivers act on: the outer sig proves who
     * handed you the bytes, the inner proves who authored the decision.
     */
    suspend fun handleRoomLifecycle(msg: RumorMessage) {
        val repo = roomRepo ?: return
        val json = msg.payload?.content ?: return
        val pubKey = runCatching { msg.senderPublicKey.fromBase64() }.getOrNull() ?: return

        when (msg.type) {
            MessageType.ROOM_CREATE -> {
                val c = runCatching { WireJson.decodeFromString<RoomCreate>(json) }.getOrNull() ?: return
                if (c.createdBy != msg.senderId) {
                    RumorLog.w(TAG, "ROOM_CREATE: createdBy is not the sender — dropped")
                    return
                }
                // The roomId must DERIVE from its creator. Without this the id
                // is a free-for-all string and first-writer-wins becomes a
                // squatting race whose winner differs per node, so the mesh
                // disagrees about who governs the room. Derivation removes the
                // race instead of arbitrating it (see RoomId).
                if (!RoomId.matches(c.roomId, c.createdBy, c.nonce)) {
                    RumorLog.w(TAG, "ROOM_CREATE: roomId does not derive from createdBy — dropped")
                    return
                }
                val signed = roomCreateSignableBytes(
                    c.roomId, c.name, c.createdBy, c.createdAtMs,
                    c.membershipPolicy, c.postingPolicy, c.allowMedia, c.nonce,
                )
                val sig = runCatching { c.signature.fromBase64() }.getOrNull() ?: return
                if (!CryptoManager.verify(signed, sig, pubKey)) {
                    RumorLog.w(TAG, "ROOM_CREATE: inner signature invalid — dropped")
                    return
                }
                // First-writer-wins: a room's identity is its creator's. Letting a
                // later CREATE for the same roomId overwrite the record would let
                // anyone reset another room's policies by re-announcing it.
                if (repo.get(c.roomId) != null) return
                repo.upsert(
                    com.rumor.mesh.core.data.RoomRecord(
                        roomId = c.roomId,
                        name = c.name,
                        creatorId = c.createdBy,
                        createdAtMs = c.createdAtMs,
                        membershipPolicy = c.membershipPolicy,
                        postingPolicy = c.postingPolicy,
                        allowMedia = c.allowMedia,
                    )
                )
                RumorLog.i(TAG, "Room ${c.roomId.take(8)}… created by ${c.createdBy.take(8)}…")
            }

            MessageType.ROOM_INVITE -> {
                val inv = runCatching { WireJson.decodeFromString<RoomInvite>(json) }.getOrNull() ?: return
                if (inv.moderatorId != msg.senderId) return
                val signed = roomInviteSignableBytes(
                    inv.roomId, inv.invitedUserId, inv.encryptedRoomKey, inv.expiresAtMs, inv.moderatorId,
                )
                val sig = runCatching { inv.moderatorSignature.fromBase64() }.getOrNull() ?: return
                if (!CryptoManager.verify(signed, sig, pubKey)) return
                if (!repo.isModerator(inv.roomId, inv.moderatorId)) {
                    RumorLog.w(TAG, "ROOM_INVITE for ${inv.roomId.take(8)}… from a non-moderator — dropped")
                    return
                }
                if (clock.now() >= inv.expiresAtMs) return
                // A ban outranks an invite: otherwise any moderator could undo a
                // ban implicitly by re-inviting, bypassing the explicit UNBAN that
                // O152 makes the auditable path.
                if (repo.isBanned(inv.roomId, inv.invitedUserId)) {
                    RumorLog.w(TAG, "ROOM_INVITE for a banned user — dropped")
                    return
                }
                repo.addMember(inv.roomId, inv.invitedUserId)
            }

            MessageType.ROOM_ACTION -> {
                val a = runCatching { WireJson.decodeFromString<RoomAction>(json) }.getOrNull() ?: return
                if (a.moderatorId != msg.senderId) return
                val signed = roomActionSignableBytes(
                    a.roomId, a.kind, a.target, a.reason, a.moderatorId, a.takenAtMs,
                )
                val sig = runCatching { a.moderatorSignature.fromBase64() }.getOrNull() ?: return
                if (!CryptoManager.verify(signed, sig, pubKey)) return
                if (!repo.isModerator(a.roomId, a.moderatorId)) {
                    RumorLog.w(TAG, "ROOM_ACTION on ${a.roomId.take(8)}… from a non-moderator — dropped")
                    return
                }
                when (a.kind) {
                    RoomActionKind.KICK_USER -> repo.removeMember(a.roomId, a.target)
                    RoomActionKind.BAN_USER -> repo.addBan(a.roomId, a.target)
                    RoomActionKind.UNBAN_USER -> repo.removeBan(a.roomId, a.target)
                    // Message removal is a display-layer concern with its own
                    // signed type (MESSAGE_DELETE); a roster repo has nothing to
                    // apply here. Verified and relayed, deliberately not acted on.
                    RoomActionKind.REMOVE_MESSAGE -> Unit
                }
                RumorLog.i(TAG, "Room ${a.roomId.take(8)}…: ${a.kind} applied to ${a.target.take(8)}…")
            }

            else -> Unit
        }
    }

    private fun isAuthorizedToPost(msg: RumorMessage, roomId: String): Boolean {
        val authority = roomAuthorityProvider ?: return true

        // A ban is unconditional and is checked FIRST — before the cert branch,
        // because a banned user may still hold an unexpired cert issued before
        // the ban, and because a ban must bite in every room shape, not only in
        // the ones that happen to require certs.
        if (authority.isBanned(roomId, msg.senderId)) {
            RumorLog.w(TAG, "Room ${roomId.take(8)}…: dropping post from banned ${msg.senderId.take(8)}…")
            return false
        }
        // Membership. The first cut returned early for every non-MOD_APPROVED
        // room with a comment claiming membership enforced it — and nothing
        // did, so MEMBER_ONLY rooms had NO write enforcement at all and anyone
        // who could compute the public routing tag could post. For ENCRYPTED
        // rooms a non-member also has no key wrap, but that is a property of
        // the envelope, not an access check, and OPEN-carried rooms have no
        // such backstop.
        if (authority.requiresMembershipToPost(roomId) && !authority.isMember(roomId, msg.senderId)) {
            RumorLog.w(TAG, "Room ${roomId.take(8)}…: dropping post from non-member ${msg.senderId.take(8)}…")
            return false
        }
        if (!authority.requiresPostingCert(roomId)) return true

        val cert = msg.roomPostingCert ?: run {
            RumorLog.w(TAG, "Room ${roomId.take(8)}…: dropping post from ${msg.senderId.take(8)}… — no posting cert")
            return false
        }
        if (cert.roomId != roomId) {
            RumorLog.w(TAG, "Room ${roomId.take(8)}…: cert is scoped to a different room")
            return false
        }
        if (cert.userId != msg.senderId) {
            RumorLog.w(TAG, "Room ${roomId.take(8)}…: cert names ${cert.userId.take(8)}…, not the sender")
            return false
        }
        val verdict = RoomPostingCertVerifier.verify(cert)
        if (verdict is RoomPostingCertVerifier.Result.Rejected) {
            RumorLog.w(TAG, "Room ${roomId.take(8)}…: cert rejected — ${verdict.reason}")
            return false
        }
        if (!authority.isModerator(roomId, cert.moderatorId)) {
            RumorLog.w(TAG, "Room ${roomId.take(8)}…: cert issuer ${cert.moderatorId.take(8)}… is not a moderator")
            return false
        }
        val now = clock.now()
        if (now < cert.validFromMs || now >= cert.validToMs) {
            RumorLog.w(TAG, "Room ${roomId.take(8)}…: cert outside its validity window")
            return false
        }
        return true
    }

    suspend fun handleRoomMessage(msg: RumorMessage) {
        val provider = roomSubscriptionProvider ?: return
        val tagB64 = msg.roomRoutingTag ?: return
        val tag = runCatching { Base64Codec.decode(tagB64) }.getOrNull() ?: return

        val match = RoomTagMatcher.match(
            inboundTag = tag,
            messageId = msg.id,
            openSubscriptions = provider.openRoomIds(),
            encryptedSubscriptions = provider.encryptedRoomSubscriptions(),
        ) ?: return  // not subscribed; drop receive-side (relay still happened)

        // O89: write-authority enforcement. Until this existed, any client that
        // knew a room's routing tag could post to it — the tag is public and
        // keyless by construction, so "governed room" was a UI label with
        // nothing behind it. RoomPostingCertVerifier existed but had zero
        // production callers.
        if (!isAuthorizedToPost(msg, match.roomId)) return

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
}
