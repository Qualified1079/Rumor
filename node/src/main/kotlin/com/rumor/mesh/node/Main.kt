package com.rumor.mesh.node

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.data.memory.InMemoryBlockEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryBlocklistEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryBreadcrumbRepository
import com.rumor.mesh.core.data.memory.InMemoryChunkRepository
import com.rumor.mesh.core.data.memory.InMemoryContactRepository
import com.rumor.mesh.core.data.memory.InMemoryMessageRepository
import com.rumor.mesh.core.data.memory.InMemoryRouteRepository
import com.rumor.mesh.core.data.memory.InMemorySubscribedBlocklistRepository
import com.rumor.mesh.core.data.memory.InMemoryTransferRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.policy.PermissiveInboxFilter
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.routing.BreadcrumbCache
import com.rumor.mesh.core.routing.MeshViewTracker
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.runtime.MeshRuntime
import com.rumor.mesh.core.scheduler.Scheduler
import com.rumor.mesh.core.transfer.TransferAssembler
import com.rumor.mesh.core.transfer.TransferSender
import com.rumor.mesh.core.transport.lan.LanTransport
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * O106 headless test node: the real protocol stack (`:core` engine +
 * MeshRuntime + O93/G40 LAN transport) hosted in a plain JVM `main()` with
 * in-memory repos. A directly-runnable, fully-inspectable second peer for
 * protocol debugging — joinable by any phone on the same Wi-Fi.
 *
 * Not the product node (O106 d/e): no persistence beyond identity+HLC, no
 * GUI beyond the localhost status page, no packaging.
 *
 * Usage: node [--bind <ip>] [--http <port>] [--data <dir>] [--quiet]
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)
    RumorLog.debugMode = !opts.quiet

    val dataDir = File(opts.dataDir)
    val identityProvider = NodeIdentityProvider(dataDir)
    val identity = identityProvider.identity.value ?: error("identity init failed")
    println("rumor:node — userId ${identity.userId}")

    // ── Engine graph (mirror of SimNode, wall-clock, real relay windows) ─────
    val contactRepo = InMemoryContactRepository()
    val messageRepo = InMemoryMessageRepository()
    val duplicateFilter = DuplicateFilter()
    val messageStore = MessageStore(messageRepo, contactRepo, duplicateFilter)
    val onlineTracker = OnlineStatusTracker()
    val meshView = MeshViewTracker()
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val gossipEngine = GossipEngine(
        messageStore = messageStore,
        duplicateFilter = duplicateFilter,
        identityProvider = identityProvider,
        onlineStatusTracker = onlineTracker,
        topologyTracker = TopologyTracker(InMemoryRouteRepository()),
        contactRepo = contactRepo,
        blockManager = BlockManager(
            InMemoryBlockEntryRepository(),
            InMemorySubscribedBlocklistRepository(),
            InMemoryBlocklistEntryRepository(),
        ),
        scheduler = Scheduler(),
        inboxFilter = PermissiveInboxFilter(),
        breadcrumbs = BreadcrumbCache(InMemoryBreadcrumbRepository()),
        scope = scope,
        meshView = meshView,
    )
    // Full peer for chunked transfers: sender + assembler (ctor subscribes).
    val transferRepo = InMemoryTransferRepository()
    val chunkRepo = InMemoryChunkRepository()
    TransferSender(gossipEngine, identityProvider, transferRepo, chunkRepo)
    TransferAssembler(gossipEngine, transferRepo, chunkRepo)

    // ── LAN transport (the node's only transport) ────────────────────────────
    val bindAddress = opts.bind?.let { InetAddress.getByName(it) } ?: pickLanAddress()
        ?: error("no usable IPv4 LAN address found — pass --bind <ip>")
    val lan = LanTransport(
        LanTransport.Config(
            localUserId = identity.userId,
            localPublicKey = Base64.getEncoder().encodeToString(identity.publicKeyBytes),
            signer = { bytes ->
                identityProvider.identity.value?.let { CryptoManager.sign(bytes, it.privateKeyBytes) }
            },
            messageProvider = gossipEngine::messagesForExchange,
            messagesByIds = gossipEngine::messagesByIds,
            knownIdsProvider = gossipEngine::knownMessageIds,
            onlineUsersProvider = onlineTracker::currentSnapshot,
            onExchangeFailed = gossipEngine::onExchangeFailed,
            rbsrItemsProvider = gossipEngine::rbsrSnapshot,
        )
    )

    // ── Status page (localhost) ──────────────────────────────────────────────
    val status = StatusServer(
        port = opts.httpPort,
        userId = identity.userId,
        lanInfo = { "lan ${bindAddress.hostAddress}:${lan.boundPort() ?: "?"}" },
        peerCount = { lan.peerCount.value },
        storedMessages = { messageRepo.snapshot() },
        sendBroadcast = { text -> gossipEngine.composeBroadcast(text) != null },
    )

    // ── Host-agnostic runtime (the same one MeshService hosts) ───────────────
    val runtime = MeshRuntime(
        gossipEngine = gossipEngine,
        identityProvider = identityProvider,
        contactRepo = contactRepo,
        messageRepo = messageRepo,
        meshView = meshView,
        // A wall-powered laptop is a static anchor.
        modeProvider = { UserMode.STATIC },
        hlcStore = FileHlcStore(dataDir),
        incomingSink = { msg ->
            status.record("RECV ${msg.type} from ${msg.senderId.take(12)}…")
        },
        // LAN-only host: no P2P group to realize.
        backboneRealizer = {},
    )
    check(runtime.start(scope)) { "runtime refused to start (identity locked?)" }

    scope.launch {
        lan.exchangeResults.collect { r ->
            // feedsCoordinator=false — same O93 rule as the Android host.
            runtime.onExchange(r, feedsCoordinator = false)
            status.record(
                "EXCHANGE peer=${r.peerUserId.take(12)}… sent=${r.messagesSent} " +
                    "recv=${r.messagesReceived} bytes=${r.bytesTransferred} ${r.durationMs}ms",
            )
        }
    }

    lan.start(bindAddress)
    status.start()
    println("LAN transport on ${bindAddress.hostAddress} — status page http://127.0.0.1:${opts.httpPort}/")
    println("Type to broadcast, 'quit' to stop. (EOF = headless: keeps running until killed.)")

    // Console loop: any line broadcasts, "quit" exits. stdin EOF means we're
    // running detached (systemd, `&`, redirected) — park forever; the process
    // is stopped by signal, and in-memory state has nothing to flush.
    while (true) {
        val line = readLine() ?: run { Thread.currentThread().join(); null } ?: break
        val text = line.trim()
        if (text == "quit") break
        if (text.isNotEmpty()) {
            val ok = gossipEngine.composeBroadcast(text) != null
            println(if (ok) "sent: $text" else "send failed (identity locked?)")
        }
    }

    println("shutting down…")
    status.stop()
    lan.stop()
    runtime.stop()
    runBlocking { scope.cancel() }
}

private data class Opts(
    val bind: String? = null,
    val httpPort: Int = 8180,
    val dataDir: String = System.getProperty("user.home") + "/.rumor-node",
    val quiet: Boolean = false,
)

private fun parseArgs(args: Array<String>): Opts {
    var opts = Opts()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--bind" -> { opts = opts.copy(bind = args[++i]) }
            "--http" -> { opts = opts.copy(httpPort = args[++i].toInt()) }
            "--data" -> { opts = opts.copy(dataDir = args[++i]) }
            "--quiet" -> { opts = opts.copy(quiet = true) }
            else -> error("unknown arg ${args[i]} — usage: node [--bind <ip>] [--http <port>] [--data <dir>] [--quiet]")
        }
        i++
    }
    return opts
}

/** First site-local IPv4 on an up, non-loopback interface — the LAN face. */
private fun pickLanAddress(): InetAddress? =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
