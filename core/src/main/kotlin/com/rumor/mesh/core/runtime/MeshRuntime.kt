package com.rumor.mesh.core.runtime

import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.data.MessageRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.routing.BackboneRealizer
import com.rumor.mesh.core.routing.MeshViewTracker
import com.rumor.mesh.core.routing.PersistenceCoordinator
import com.rumor.mesh.core.time.HlcTimestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Where the HLC clock state survives process restart. Android backs this with
 * SharedPreferences; the :node host with a file. Restore is a max-merge inside
 * HlcClock — never moves the clock backward — so a lossy store is safe.
 */
interface HlcStore {
    fun load(): HlcTimestamp
    fun save(ts: HlcTimestamp)
}

/**
 * Host-agnostic mesh orchestration (O106): the engine↔transport wiring that
 * used to live inline in the Android `MeshService.startMesh()`. A host —
 * MeshService, the :node desktop `main()`, a future systemd unit — constructs
 * this with its platform edges as lambdas and calls [start]; transports stay
 * host-owned and feed [onExchange].
 *
 * Not wired through DI: like PersistenceCoordinator before it, construction
 * needs the unlocked identity, so hosts build it at mesh-start time.
 */
class MeshRuntime(
    private val gossipEngine: GossipEngine,
    private val identityProvider: IdentityProvider,
    private val contactRepo: ContactRepository,
    private val messageRepo: MessageRepository,
    private val meshView: MeshViewTracker,
    private val modeProvider: () -> UserMode,
    /** Mode envelope's beacon interval; null falls back to the MOBILE floor. */
    private val beaconIntervalMsProvider: () -> Long? = { null },
    private val hlcStore: HlcStore? = null,
    /** Downstream consumer of verified inbound messages (Android: PluginRegistry). */
    private val incomingSink: suspend (com.rumor.mesh.core.model.RumorMessage) -> Unit = {},
    /**
     * O98 Phase 3b: realize the held backbone as radio state. Android hands
     * this to WifiDirectTransport.applyBackboneRole; a LAN-only host leaves it
     * a no-op (there is no P2P group to realize).
     */
    private val backboneRealizer: (BackboneRealizer.Role) -> Unit = {},
) {
    private val TAG = "MeshRuntime"

    /**
     * O98 Phase 3 coordinator (brain). Built at [start] from the unlocked
     * identity; drives the backbone plan the transport's isPriorityPeer gate
     * consults. Null until the runtime is running.
     */
    @Volatile var coordinator: PersistenceCoordinator? = null
        private set

    /** False iff identity was locked — host should retry after unlock. */
    fun start(scope: CoroutineScope): Boolean {
        val identity = identityProvider.identity.value ?: return false

        // O92: rehydrate the volatile scheduler + dedup filter from the durable
        // store so a restarted host actually offers its buffered messages on the
        // next exchange (otherwise: "0 sent, 0 received" with a full repo).
        scope.launch { gossipEngine.reseedFromStore() }

        // Invariant: a node's own identity is never a contact. An older build
        // could persist one when a self-authored message echoed back through a
        // relay and hit MessageStore.ingest → ensureContact(self) (the receive
        // path now drops self-authored echoes before ingest). Purge any such row
        // on every start so existing installs self-heal and the invariant holds.
        scope.launch { contactRepo.delete(identity.userId) }

        // SELF_PRESENCE is ephemeral (verified, dedup-known, tracker-fed, never
        // archived) as of the echo-loop fix, but builds ≤0.6.1 persisted every
        // received beacon — stores grew 99% beacons, tripping the RBSR gate and
        // re-shipping the same diff forever. Purge the accumulated rows on every
        // start; re-received ids from unflashed peers no longer persist.
        scope.launch { messageRepo.deleteByType(MessageType.SELF_PRESENCE) }

        // O98 Phase 3: the coordinator turns inbound SELF_PRESENCE beacons (fed
        // into meshView by the engine) into a stable backbone plan. Its
        // backbonePeers set gates which links the transport holds persistent.
        val c = PersistenceCoordinator(
            selfId = identity.userId,
            meshView = meshView,
            selfMode = modeProvider,
        )
        coordinator = c

        // ── Wire gossip engine output → host sink ────────────────────────────
        scope.launch {
            gossipEngine.incomingMessages.collect { msg -> incomingSink(msg) }
        }

        // O95: HLC state must survive restart — a behind-clock node would
        // otherwise compose below its own pre-restart stamps. Restore is a
        // max-merge (never moves the clock backward); every advance persists.
        hlcStore?.let { store ->
            gossipEngine.hlc.restore(store.load())
            gossipEngine.hlc.onAdvance = { ts -> store.save(ts) }
        }

        // O124: answer a pulse from an unknown-or-stale peer with our own
        // (engine-gated per-peer cooldown; see PresenceReplyGate).
        gossipEngine.presencePulse = { pulseNow() }

        // ── O98: SELF_PRESENCE beacon loop ───────────────────────────────────
        // Advertise our mode + recent-exchange adjacency so every node's view can
        // span us into the backbone. See MOBILE_BEACON_FLOOR_MS for why MOBILE
        // still beacons here despite the null envelope interval.
        scope.launch {
            while (isActive) {
                pulseNow()
                delay(beaconIntervalMsProvider() ?: MOBILE_BEACON_FLOOR_MS)
            }
        }

        // ── O98: backbone recompute loop ─────────────────────────────────────
        // Fold the assembled view through planner + reconciler on a fixed tick.
        // Logs every change so the coordinator-free convergence is observable
        // during multi-device testing.
        scope.launch {
            while (isActive) {
                delay(BACKBONE_RECOMPUTE_INTERVAL_MS)
                val s = c.recompute()
                if (s.changed || s.backbonePeers.isNotEmpty()) {
                    RumorLog.i(
                        TAG,
                        "O98 backbone: view=${s.viewNodes}n/${s.viewEdges}e planned=${s.plannedLinks} " +
                            "held=${s.heldLinks} peers=${s.backbonePeers.map { it.take(8) }} " +
                            "+${s.added.size}/-${s.removed.size}",
                    )
                }
                // Phase 3b: realize the held backbone as actual radio state.
                // Idempotent while healthy; the transport owns retry/fallback.
                backboneRealizer(c.selfRole())
            }
        }
        return true
    }

    /**
     * Host transports feed every completed exchange here. [feedsCoordinator]
     * is true for radio transports whose links the backbone can realize;
     * false for LAN (O93: planning links for peers already reachable over the
     * LAN would make the transport fight the very AP association the LAN path
     * exists for).
     */
    fun onExchange(result: PeerExchangeResult, feedsCoordinator: Boolean = true) {
        gossipEngine.onExchange(result)
        if (feedsCoordinator) coordinator?.onExchanged(result.peerUserId)
    }

    /**
     * Immediate SELF_PRESENCE (O57/G12 mode-change pulse, O124 search-button
     * announce). Mode defaults to the current provider value; pass explicitly
     * when pulsing a just-changed mode before the provider reflects it.
     */
    fun pulseNow(mode: UserMode = modeProvider()) {
        gossipEngine.composeSelfPresence(mode, coordinator?.beaconNeighbors() ?: emptyList())
    }

    /** Loops die with the host scope; this only drops coordinator state. */
    fun stop() {
        coordinator = null
    }

    companion object {
        /**
         * O98: how often the coordinator recomputes the backbone from gossiped
         * beacons. Fast enough to react to a peer joining within a couple of
         * gossip rounds; the reconciler's hysteresis absorbs the churn.
         */
        const val BACKBONE_RECOMPUTE_INTERVAL_MS = 12_000L

        /**
         * O98: SELF_PRESENCE floor even in MOBILE. The ModeEnvelope leaves MOBILE
         * at `presenceBeaconIntervalMs = null` ("mode-change pulses only" per
         * O30/O57 to avoid advertising as an anchor), but the backbone planner
         * needs every participating node visible in the view or its edges get
         * dropped — a MOBILE phone that never beacons can't be spanned into the
         * backbone at all. The beacon is a tiny CONTROL message that rides
         * existing gossip rounds (no extra radio wake), so a conservative floor
         * is cheap; it only sets adjacency freshness, well under MeshViewTracker's
         * 6-min stale window.
         */
        const val MOBILE_BEACON_FLOOR_MS = 90_000L
    }
}
