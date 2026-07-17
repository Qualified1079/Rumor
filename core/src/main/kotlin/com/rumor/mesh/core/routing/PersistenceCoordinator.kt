package com.rumor.mesh.core.routing

import com.rumor.mesh.core.Clock
import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.model.UserMode

/**
 * O98 Phase 3 (coordinator) — the pure decision layer that turns gossiped
 * [MeshViewTracker] state into a stable set of backbone peers this node should
 * hold persistent links to. Radio-free and coroutine-free on purpose: the host
 * ([com.rumor.mesh.core.protocol.GossipEngine] beacons + MeshService timing on
 * Android, or a scenario driver in the simulator) owns *when* [beaconNeighbors],
 * [recompute], and [onExchanged] fire; this class owns *what* the answers are.
 *
 * The pipeline is [MeshViewTracker.assembleView] → [PersistencePlanner.plan] →
 * [PersistenceReconciler] (hysteresis) → [backbonePeers]. The reconciler is what
 * keeps a one-scan BLE blip or a late-arriving beacon from thrashing the radio.
 *
 * The load-bearing property is unchanged from [PersistencePlanner]: because the
 * plan is a pure function of the shared view, every node that has heard the same
 * beacons computes the identical link set, so both endpoints of a backbone edge
 * independently decide to hold it — no coordinator, no election.
 *
 * Not thread-safe; drive it from one coroutine (the mesh loop).
 */
class PersistenceCoordinator(
    private val selfId: String,
    private val meshView: MeshViewTracker,
    private val selfMode: () -> UserMode,
    private val redundancy: Int = 1,
    private val recentPeerLimit: Int = DEFAULT_RECENT_PEER_LIMIT,
    private val clock: Clock = SystemClock,
) {
    private val reconciler = PersistenceReconciler()

    /**
     * Peers this node has actually exchanged with recently, MRU-ordered and
     * bounded. This is what we advertise as our own `recentlyExchangedWith`
     * (the candidate edges we could realize) and what we inject as our own
     * adjacency when assembling the view — the planner has to budget our degree
     * and see our edges, and we never beacon about ourselves.
     */
    private val recent = LinkedHashSet<String>()

    @Volatile
    private var _backbonePeers: Set<String> = emptySet()

    /** Backbone neighbours the transport should hold persistent links to. */
    val backbonePeers: Set<String> get() = _backbonePeers

    /** Modes from the last assembled view — capacity ranking for [selfRole]. */
    @Volatile
    private var lastModes: Map<String, UserMode> = emptyMap()

    /** Record a completed exchange so the peer counts as a realizable edge. */
    fun onExchanged(peerUserId: String) {
        if (peerUserId == selfId) return
        recent.remove(peerUserId)          // re-insert at MRU end
        recent.add(peerUserId)
        while (recent.size > recentPeerLimit) {
            recent.remove(recent.iterator().next())
        }
    }

    /** The adjacency to advertise in this node's SELF_PRESENCE beacon. */
    fun beaconNeighbors(): List<String> = recent.toList()

    /**
     * Recompute the backbone from the current view and fold it through the
     * reconciler. Returns a summary for logging/diagnostics. Idempotent apart
     * from hysteresis aging: calling it on an unchanged view eventually settles
     * to empty deltas.
     */
    fun recompute(): Summary {
        val view = meshView.assembleView(
            selfId = selfId,
            selfMode = selfMode(),
            selfNeighbors = recent.toList(),
        )
        val plan = PersistencePlanner.plan(view, redundancy)
        val delta = reconciler.reconcile(plan)
        val held = BackbonePlan(reconciler.activeLinks())
        _backbonePeers = held.persistentPeersOf(selfId)
        lastModes = view.modes
        return Summary(
            viewNodes = view.modes.size,
            viewEdges = view.edges.size,
            plannedLinks = plan.links.size,
            heldLinks = reconciler.activeLinks().size,
            backbonePeers = _backbonePeers,
            added = delta.toAdd,
            removed = delta.toRemove,
        )
    }

    /**
     * O98 Phase 3b: this node's radio role under the current held backbone.
     * Deterministic over (held links, view modes), so both endpoints of a
     * realized edge agree on who hosts without any election traffic. Read
     * after [recompute]; hysteresis in the held set keeps it stable.
     */
    fun selfRole(): BackboneRealizer.Role =
        BackboneRealizer.realize(reconciler.activeLinks(), lastModes).roleOf(selfId)

    /** One-line-loggable snapshot of a [recompute]. */
    data class Summary(
        val viewNodes: Int,
        val viewEdges: Int,
        val plannedLinks: Int,
        val heldLinks: Int,
        val backbonePeers: Set<String>,
        val added: Set<Link>,
        val removed: Set<Link>,
    ) {
        val changed: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()
    }

    companion object {
        const val DEFAULT_RECENT_PEER_LIMIT = 16
    }
}
