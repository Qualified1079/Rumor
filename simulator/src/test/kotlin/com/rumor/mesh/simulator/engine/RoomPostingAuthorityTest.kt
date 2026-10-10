package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.data.memory.InMemoryBlockEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryBlocklistEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryContactRepository
import com.rumor.mesh.core.data.memory.InMemoryMessageRepository
import com.rumor.mesh.core.data.memory.InMemoryRouteRepository
import com.rumor.mesh.core.data.memory.InMemorySubscribedBlocklistRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.model.RoomPostingCert
import com.rumor.mesh.core.model.roomPostingCertSignableBytes
import com.rumor.mesh.core.policy.PermissiveInboxFilter
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.protocol.RoomRoutingTag
import com.rumor.mesh.core.protocol.RoomTagMatcher
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.scheduler.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.rumor.mesh.core.protocol.PeerExchangeResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O89 — Rooms write-authority enforcement.
 *
 * Before this, `RoomPostingCertVerifier` had zero production callers:
 * `composeRoomMessage` attached no cert and `handleRoomMessage` performed no
 * authority check. Because an OPEN room's routing tag is a deterministic,
 * keyless function of its public roomId, *any* client that could compute the
 * tag could post into a "governed" room — the governance was a UI label with
 * nothing behind it.
 *
 * Each case drives the real receive path (`processIncoming` -> matcher ->
 * authority check -> inbox), so a pass means the engine actually refused the
 * post, not that a helper returned false somewhere.
 */
class RoomPostingAuthorityTest {

    private class FakeIdentity(initial: LocalIdentity?) : IdentityProvider {
        private val flow = MutableStateFlow(initial)
        override val identity: StateFlow<LocalIdentity?> = flow
        override val isUnlocked: Boolean get() = flow.value != null
    }

    private val roomId = "room-alpha"
    private val scope = CoroutineScope(SupervisorJob())

    private val modKp = CryptoManager.generateEd25519KeyPair()
    private val modId = CryptoManager.publicKeyToUserId(modKp.publicKeyBytes)
    private val otherKp = CryptoManager.generateEd25519KeyPair()
    private val otherId = CryptoManager.publicKeyToUserId(otherKp.publicKeyBytes)

    /** The posting member: a separate identity whose engine composes the post. */
    private val senderKp = CryptoManager.generateEd25519KeyPair()
    private val senderId = CryptoManager.publicKeyToUserId(senderKp.publicKeyBytes)

    /** Receiver subscribes to the room and requires certs; `modId` is its mod. */
    /** Mutable so a test can ban or un-member the sender mid-scenario. */
    private val banned = mutableSetOf<String>()
    private val members = mutableSetOf(senderId)
    private var membershipRequired = false

    private val authority = object : GossipEngine.RoomAuthorityProvider {
        override fun requiresPostingCert(roomId: String) = true
        override fun isModerator(roomId: String, moderatorId: String) = moderatorId == modId
        override fun requiresMembershipToPost(roomId: String) = membershipRequired
        override fun isMember(roomId: String, userId: String) = userId in members
        override fun isBanned(roomId: String, userId: String) = userId in banned
    }

    private val subscription = object : GossipEngine.RoomSubscriptionProvider {
        override fun openRoomIds() = listOf(roomId)
        override fun encryptedRoomSubscriptions(): List<RoomTagMatcher.EncryptedRoomSubscription> = emptyList()
        override fun localX25519StaticPrivate(): ByteArray? = null
    }

    private fun engine(
        identity: LocalIdentity?,
        auth: GossipEngine.RoomAuthorityProvider? = null,
        subs: GossipEngine.RoomSubscriptionProvider? = null,
    ): GossipEngine {
        val contactRepo = InMemoryContactRepository()
        val dedup = DuplicateFilter()
        return GossipEngine(
            messageStore = MessageStore(InMemoryMessageRepository(), contactRepo, dedup),
            duplicateFilter = dedup,
            identityProvider = FakeIdentity(identity),
            onlineStatusTracker = OnlineStatusTracker(),
            topologyTracker = TopologyTracker(InMemoryRouteRepository()),
            contactRepo = contactRepo,
            blockManager = BlockManager(
                InMemoryBlockEntryRepository(),
                InMemorySubscribedBlocklistRepository(),
                InMemoryBlocklistEntryRepository(),
            ),
            scheduler = Scheduler(),
            inboxFilter = PermissiveInboxFilter(),
            breadcrumbs = null,
            scope = scope,
            relayBatchMinWindowMs = 1L,
            relayBatchSpreadMs = 1L,
            roomSubscriptionProvider = subs,
            roomAuthorityProvider = auth,
        )
    }

    private val senderIdentity = LocalIdentity(senderId, "dev", senderKp.publicKeyBytes, senderKp.privateKeyBytes)

    /** A cert signed by [kp], naming [grantee], scoped to [room]. */
    private fun cert(
        grantee: String = senderId,
        room: String = roomId,
        kp: CryptoManager.Ed25519KeyPair = modKp,
        issuerId: String = modId,
        validFromMs: Long = 0L,
        validToMs: Long = Long.MAX_VALUE / 2,
    ): RoomPostingCert {
        val signed = roomPostingCertSignableBytes(
            room, null, grantee, validFromMs, validToMs, issuerId, kp.publicKeyBytes.toBase64(),
        )
        return RoomPostingCert(
            roomId = room,
            userId = grantee,
            validFromMs = validFromMs,
            validToMs = validToMs,
            moderatorId = issuerId,
            moderatorPublicKey = kp.publicKeyBytes.toBase64(),
            signature = CryptoManager.sign(signed, kp.privateKeyBytes).toBase64(),
        )
    }

    @After fun tearDown() = scope.cancel()

    /**
     * Compose a post on a sender-side engine, hand it to a receiver that
     * subscribes to the room and enforces authority, and report whether it
     * reached the receiver's inbox. Drives the real public inbound path
     * (`onExchange` -> processIncoming -> matcher -> authority check), so a
     * refusal here is the engine refusing, not a helper consulted in isolation.
     */
    private fun postAndReceive(
        postingCert: RoomPostingCert?,
        auth: GossipEngine.RoomAuthorityProvider? = authority,
    ): Boolean = runBlocking {
        val sender = engine(senderIdentity)
        val msg = sender.composeRoomMessage(
            routingTag = RoomRoutingTag.openRoomTag(roomId),
            plaintext = "hello room",
            postingCert = postingCert,
        ) ?: error("compose failed")

        val receiver = engine(null, auth = auth, subs = subscription)
        val inbox = MutableStateFlow<List<String>>(emptyList())
        scope.launch { receiver.incomingMessages.collect { m -> inbox.update { it + m.id } } }
        delay(30)

        receiver.onExchange(
            PeerExchangeResult(
                peerUserId = "peer",
                messagesReceived = listOf(msg),
                ackedByPeer = emptyList(),
                peerOnlineUsers = emptyMap(),
                durationMs = 1L,
            )
        )
        delay(400)
        msg.id in inbox.value
    }

    @Test
    fun `a valid cert from a real moderator is accepted`() {
        assertTrue("a properly certified post must reach the inbox", postAndReceive(cert()))
    }

    @Test
    fun `a post with no cert is refused`() {
        // The headline hole: the routing tag is public and keyless, so without
        // this check anyone who could compute it could post to a governed room.
        assertFalse("an uncertified post must not reach the inbox", postAndReceive(null))
    }

    @Test
    fun `a cert issued by a non-moderator is refused`() {
        assertFalse(
            "a self-signed cert from a non-mod must not authorize posting",
            postAndReceive(cert(kp = otherKp, issuerId = otherId)),
        )
    }

    @Test
    fun `a cert naming a different user is refused`() {
        // Otherwise a valid cert observed on the wire could be replayed by
        // anyone to authorize their own posts.
        assertFalse("a cert for someone else must not authorize this sender", postAndReceive(cert(grantee = otherId)))
    }

    @Test
    fun `a cert scoped to another room is refused`() {
        assertFalse("a cert for room B must not authorize a post to room A", postAndReceive(cert(room = "room-beta")))
    }

    @Test
    fun `an expired cert is refused`() {
        assertFalse("a cert outside its validity window must not authorize", postAndReceive(cert(validToMs = 1L)))
    }

    @Test
    fun `a not-yet-valid cert is refused`() {
        assertFalse(
            "a cert whose window starts in the future must not authorize",
            postAndReceive(cert(validFromMs = Long.MAX_VALUE / 4, validToMs = Long.MAX_VALUE / 2)),
        )
    }

    @Test
    fun `a banned sender is refused even holding a valid cert`() {
        // A ban must outrank a cert issued before it — otherwise banning someone
        // does nothing until their cert happens to expire.
        banned.add(senderId)
        try {
            assertFalse("a banned sender must be refused despite a valid cert", postAndReceive(cert()))
        } finally {
            banned.remove(senderId)
        }
        // Control: the same cert works once the ban is lifted.
        assertTrue("un-banning must restore posting", postAndReceive(cert()))
    }

    @Test
    fun `a non-member is refused when the room requires membership`() {
        // The gap this closes: the first cut returned early for every room that
        // did not require a cert, with a comment claiming membership enforced
        // it — and nothing did, so MEMBER_ONLY rooms had no write enforcement.
        membershipRequired = true
        members.remove(senderId)
        try {
            assertFalse("a non-member must be refused", postAndReceive(cert()))
            members.add(senderId)
            assertTrue("a member with a valid cert must be accepted", postAndReceive(cert()))
        } finally {
            membershipRequired = false
            members.add(senderId)
        }
    }

    /**
     * Discrimination control for the whole suite: with no authority provider
     * wired, nothing is enforced. This is the ungoverned feed (O154/O186) and
     * engines with no rooms, and it proves the refusals above come from the
     * authority check rather than from room delivery being broken outright.
     */
    @Test
    fun `with no authority provider an uncertified post is delivered`() {
        assertTrue(
            "no provider means no enforcement — the ungoverned feed must still work",
            postAndReceive(null, auth = null),
        )
    }
}
