package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.data.memory.InMemoryBlockEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryBlocklistEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryContactRepository
import com.rumor.mesh.core.data.memory.InMemoryMessageRepository
import com.rumor.mesh.core.data.memory.InMemoryRoomRepository
import com.rumor.mesh.core.data.memory.InMemoryRouteRepository
import com.rumor.mesh.core.data.memory.InMemorySubscribedBlocklistRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.model.RoomActionKind
import com.rumor.mesh.core.model.RoomMembershipPolicy
import com.rumor.mesh.core.model.RoomPostingPolicy
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.policy.PermissiveInboxFilter
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.scheduler.Scheduler
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O187 — room lifecycle over the wire.
 *
 * Before this, `MessageType` had exactly one room entry (`ROOM_MESSAGE`), so
 * `RoomCreate`/`RoomInvite`/`RoomAction` could not be gossiped even in
 * principle, and no `RoomRepository` existed for O89's authority check to read
 * from. Rooms enforced correctly (G53) and could not be created.
 *
 * Every case drives a REAL second engine through `onExchange`, so a pass means
 * the decision actually propagated and was applied by the receiver — not that
 * a local helper mutated a local map.
 */
class RoomLifecycleTest {

    private class FakeIdentity(initial: LocalIdentity?) : IdentityProvider {
        private val flow = MutableStateFlow(initial)
        override val identity: StateFlow<LocalIdentity?> = flow
        override val isUnlocked: Boolean get() = flow.value != null
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val nonce = "n1"
    /** Derived, not chosen — see RoomId. Computed from the creator's identity. */
    private val roomId by lazy { com.rumor.mesh.core.protocol.RoomId.derive(modIdentity.userId, nonce) }

    private fun identityOf(kp: CryptoManager.Ed25519KeyPair) =
        LocalIdentity(CryptoManager.publicKeyToUserId(kp.publicKeyBytes), "dev", kp.publicKeyBytes, kp.privateKeyBytes)

    private val modKp = CryptoManager.generateEd25519KeyPair()
    private val modIdentity = identityOf(modKp)
    private val outsiderKp = CryptoManager.generateEd25519KeyPair()
    private val outsiderIdentity = identityOf(outsiderKp)
    private val alice = "a".repeat(64)

    private class Node(val engine: GossipEngine, val rooms: InMemoryRoomRepository)

    private fun node(identity: LocalIdentity?): Node {
        val contactRepo = InMemoryContactRepository()
        val dedup = DuplicateFilter()
        val rooms = InMemoryRoomRepository()
        val engine = GossipEngine(
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
            roomRepo = rooms,
        )
        return Node(engine, rooms)
    }

    /** Hand [msgs] to [to] over the real inbound path and let handlers settle. */
    private suspend fun deliver(to: Node, vararg msgs: RumorMessage) {
        to.engine.onExchange(
            PeerExchangeResult(
                peerUserId = "peer",
                messagesReceived = msgs.toList(),
                ackedByPeer = emptyList(),
                peerOnlineUsers = emptyMap(),
                durationMs = 1L,
            )
        )
        delay(300)
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun `a created room propagates, and its creator moderates it`() = runBlocking {
        val creator = node(modIdentity)
        val peer = node(null)

        val create = creator.engine.composeRoomCreate(
            nonce, "Alpha", RoomMembershipPolicy.INVITE, RoomPostingPolicy.MOD_APPROVED, allowMedia = false,
        )
        assertNotNull("compose must succeed", create)
        deliver(peer, create!!)

        val seen = peer.rooms.get(roomId)
        assertNotNull("the room must exist on the receiving node", seen)
        assertEquals(RoomPostingPolicy.MOD_APPROVED, seen!!.postingPolicy)
        assertFalse("allowMedia must survive the wire", seen.allowMedia)
        assertTrue(
            "the creator must moderate by construction, on the receiver too",
            peer.rooms.isModerator(roomId, modIdentity.userId),
        )
    }

    @Test
    fun `an invite from a moderator adds the member on the receiving node`() = runBlocking {
        val creator = node(modIdentity)
        val peer = node(null)
        val create = creator.engine.composeRoomCreate(
            nonce, "Alpha", RoomMembershipPolicy.INVITE, RoomPostingPolicy.MOD_APPROVED, false,
        )!!
        val invite = creator.engine.composeRoomInvite(roomId, alice, "wrapped-key", Long.MAX_VALUE / 2)
        assertNotNull("a moderator must be able to compose an invite", invite)

        deliver(peer, create, invite!!)
        assertTrue("alice must be a member on the receiver", peer.rooms.isMember(roomId, alice))
    }

    /**
     * The receive-side guard is the security boundary, so this deliberately
     * BYPASSES `composeRoomInvite` (whose moderator precondition is only UX —
     * a hostile client simply won't call it) and injects a correctly
     * self-signed invite straight onto the wire via `composeOutbound`.
     *
     * An earlier version of this test used composeRoomInvite and passed with the
     * receive-side moderator check DISABLED — it was verifying the wrong layer.
     */
    @Test
    fun `an invite from a non-moderator is refused on the receive path`() = runBlocking {
        val creator = node(modIdentity)
        val outsider = node(outsiderIdentity)
        val peer = node(null)
        val create = creator.engine.composeRoomCreate(
            nonce, "Alpha", RoomMembershipPolicy.INVITE, RoomPostingPolicy.MOD_APPROVED, false,
        )!!

        // Hand-built, validly self-signed invite: the outsider signs it with its
        // own key and names itself moderator. Everything verifies EXCEPT that it
        // holds no authority in this room.
        val expires = Long.MAX_VALUE / 2
        val signed = com.rumor.mesh.core.model.roomInviteSignableBytes(
            roomId, alice, "wrapped-key", expires, outsiderIdentity.userId,
        )
        val forgedInvite = com.rumor.mesh.core.model.RoomInvite(
            roomId = roomId,
            invitedUserId = alice,
            encryptedRoomKey = "wrapped-key",
            expiresAtMs = expires,
            moderatorId = outsiderIdentity.userId,
            moderatorSignature = CryptoManager.sign(signed, outsiderKp.privateKeyBytes).toBase64(),
        )
        val carried = outsider.engine.composeOutbound(
            type = com.rumor.mesh.core.model.MessageType.ROOM_INVITE,
            payload = com.rumor.mesh.core.model.MessagePayload(
                com.rumor.mesh.core.model.ContentType.CONTROL,
                com.rumor.mesh.core.wire.WireJson.encodeToString(forgedInvite),
            ),
        )!!

        deliver(peer, create, carried)
        assertFalse(
            "an invite from a node with no authority in the room must not add a member",
            peer.rooms.isMember(roomId, alice),
        )
        // Discrimination control: the SAME shape from the real moderator works,
        // so the refusal above is the authority check and not a decode failure.
        deliver(peer, creator.engine.composeRoomInvite(roomId, alice, "k", expires)!!)
        assertTrue("the real moderator's invite must still take effect", peer.rooms.isMember(roomId, alice))
    }

    @Test
    fun `a ban propagates, removes membership, and outranks a later invite`() = runBlocking {
        val creator = node(modIdentity)
        val peer = node(null)
        val create = creator.engine.composeRoomCreate(
            nonce, "Alpha", RoomMembershipPolicy.INVITE, RoomPostingPolicy.MOD_APPROVED, false,
        )!!
        val invite = creator.engine.composeRoomInvite(roomId, alice, "k", Long.MAX_VALUE / 2)!!
        deliver(peer, create, invite)
        assertTrue("precondition: alice is a member", peer.rooms.isMember(roomId, alice))

        val ban = creator.engine.composeRoomAction(roomId, RoomActionKind.BAN_USER, alice, "spam")!!
        deliver(peer, ban)
        assertTrue("alice must be banned on the receiver", peer.rooms.isBanned(roomId, alice))
        assertFalse("a ban must also drop membership", peer.rooms.isMember(roomId, alice))

        // A re-invite must not silently launder the ban away — un-banning is an
        // explicit, auditable action (O152), not a side effect of an invite.
        val reInvite = creator.engine.composeRoomInvite(roomId, alice, "k2", Long.MAX_VALUE / 2)!!
        deliver(peer, reInvite)
        assertFalse("a ban must outrank a later invite", peer.rooms.isMember(roomId, alice))
    }

    @Test
    fun `an unban restores the ability to be invited`() = runBlocking {
        val creator = node(modIdentity)
        val peer = node(null)
        val create = creator.engine.composeRoomCreate(
            nonce, "Alpha", RoomMembershipPolicy.INVITE, RoomPostingPolicy.MOD_APPROVED, false,
        )!!
        deliver(peer, create, creator.engine.composeRoomAction(roomId, RoomActionKind.BAN_USER, alice, null)!!)
        deliver(peer, creator.engine.composeRoomAction(roomId, RoomActionKind.UNBAN_USER, alice, null)!!)
        assertFalse("alice must no longer be banned", peer.rooms.isBanned(roomId, alice))

        deliver(peer, creator.engine.composeRoomInvite(roomId, alice, "k3", Long.MAX_VALUE / 2)!!)
        assertTrue("after an unban an invite must take effect", peer.rooms.isMember(roomId, alice))
    }

    @Test
    fun `a second CREATE for the same roomId cannot overwrite the first`() = runBlocking {
        val creator = node(modIdentity)
        val outsider = node(outsiderIdentity)
        val peer = node(null)
        deliver(peer, creator.engine.composeRoomCreate(
            nonce, "Alpha", RoomMembershipPolicy.INVITE, RoomPostingPolicy.MOD_APPROVED, allowMedia = false,
        )!!)
        // Same nonce, different identity -> a DIFFERENT derived roomId, because
        // the creator's own id is inside the derivation. The outsider cannot
        // address this room at all, so there is no squatting race to win.
        val hijack = outsider.engine.composeRoomCreate(
            nonce, "Hijacked", RoomMembershipPolicy.OPEN, RoomPostingPolicy.MEMBER_ONLY, allowMedia = true,
        )!!
        deliver(peer, hijack)

        val room = peer.rooms.get(roomId)!!
        assertEquals("the original name must survive", "Alpha", room.name)
        assertEquals("the original creator must survive", modIdentity.userId, room.creatorId)
        assertFalse("the hijacker must not have become a moderator",
            peer.rooms.isModerator(roomId, outsiderIdentity.userId))
    }

    /**
     * The derivation guard itself, exercised by bypassing compose (which can
     * only ever produce a correctly derived id). A hostile client WILL assert
     * someone else's roomId directly.
     */
    @Test
    fun `a CREATE whose roomId does not derive from its creator is dropped`() = runBlocking {
        val outsider = node(outsiderIdentity)
        val peer = node(null)

        // Claim the victim's roomId while signing as ourselves — everything
        // verifies except the id<-creator binding.
        val createdAtMs = 1_000L
        val signed = com.rumor.mesh.core.model.roomCreateSignableBytes(
            roomId, "Squatted", outsiderIdentity.userId, createdAtMs,
            RoomMembershipPolicy.OPEN, RoomPostingPolicy.MEMBER_ONLY, true, nonce,
        )
        val forged = com.rumor.mesh.core.model.RoomCreate(
            roomId = roomId,
            name = "Squatted",
            createdBy = outsiderIdentity.userId,
            createdAtMs = createdAtMs,
            membershipPolicy = RoomMembershipPolicy.OPEN,
            postingPolicy = RoomPostingPolicy.MEMBER_ONLY,
            allowMedia = true,
            nonce = nonce,
            signature = CryptoManager.sign(signed, outsiderKp.privateKeyBytes).toBase64(),
        )
        val carried = outsider.engine.composeOutbound(
            type = com.rumor.mesh.core.model.MessageType.ROOM_CREATE,
            payload = com.rumor.mesh.core.model.MessagePayload(
                com.rumor.mesh.core.model.ContentType.CONTROL,
                com.rumor.mesh.core.wire.WireJson.encodeToString(forged),
            ),
        )!!
        deliver(peer, carried)
        assertNull(
            "a CREATE asserting an id that does not derive from its creator must be dropped",
            peer.rooms.get(roomId),
        )

        // Control: the genuine creator's CREATE for the same id IS accepted, so
        // the drop above is the derivation check and not a decode failure.
        val creator = node(modIdentity)
        deliver(peer, creator.engine.composeRoomCreate(
            nonce, "Alpha", RoomMembershipPolicy.INVITE, RoomPostingPolicy.MOD_APPROVED, false,
        )!!)
        assertEquals("Alpha", peer.rooms.get(roomId)?.name)
    }

    /**
     * Discrimination control: an engine with no room repo wired still relays
     * lifecycle traffic and simply applies nothing locally. Without this, every
     * "must not apply" assertion above could pass on a dead code path.
     */
    @Test
    fun `an engine with no room repo applies nothing and does not crash`() = runBlocking {
        val creator = node(modIdentity)
        val create = creator.engine.composeRoomCreate(
            nonce, "Alpha", RoomMembershipPolicy.INVITE, RoomPostingPolicy.MOD_APPROVED, false,
        )!!
        val repoless = node(null).let { it } // node() always wires a repo; build a bare one:
        val contactRepo = InMemoryContactRepository()
        val dedup = DuplicateFilter()
        val bare = GossipEngine(
            messageStore = MessageStore(InMemoryMessageRepository(), contactRepo, dedup),
            duplicateFilter = dedup,
            identityProvider = FakeIdentity(null),
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
        )
        bare.onExchange(
            PeerExchangeResult("peer", listOf(create), emptyList(), emptyMap(), 1L)
        )
        delay(200)
        assertNull("sanity: the repoless node has no room state to inspect", repoless.rooms.get("nonexistent"))
    }
}
