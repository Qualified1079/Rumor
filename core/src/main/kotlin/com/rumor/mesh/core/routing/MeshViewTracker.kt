package com.rumor.mesh.core.routing

import com.rumor.mesh.core.Clock
import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.model.UserMode

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * O98 (MeshView substrate) — assembles a node's local view of the mesh from
 * inbound `SELF_PRESENCE` beacons (O30/O57), for the [PersistencePlanner].
 *
 * One beacon carries everything the planner needs: the author's declared
 * [UserMode] (→ [MeshView.modes], the degree budget) AND its
 * `recentlyExchangedWith` list (→ [MeshView.edges], who can hold a persistent
 * link). No separate adjacency-advertisement message type is needed — the
 * self-presence beacon already advertises both.
 *
 * **Provenance is clean.** SELF_PRESENCE is self-presence: the author IS the
 * subject, so mode and adjacency are first-hand about that node — no "I saw X"
 * hearsay to launder (the §4 trap). A *future* beacon timestamp is clamped to
 * now; a beacon older than what we hold for that peer is ignored.
 *
 * **Recency decay is the safety.** A STATIC/FREE node that stops beaconing
 * (unplugged, pocketed, crashed) must not linger as a ghost anchor. A peer with
 * no beacon fresher than [freshWindowMs] drops out of the assembled view
 * entirely — the planner then re-spans around it, matching the O30 exit-pulse
 * intent even when the exit pulse is lost.
 *
 * **Bounded** (unlike the audited [OnlineStatusTracker], §4): the map is capped
 * at [maxPeers] with LRU-by-beacon eviction, so SELF_PRESENCE spam for
 * fabricated userIds can't grow it without bound. Thread-safe via
 * [SynchronizedObject].
 *
 * Determinism note: the planner requires that peers converge on identical
 * inputs. This tracker doesn't force that (each node hears beacons at different
 * times); the convergence guarantee is that *once beacon propagation settles*
 * every node's [assembleView] agrees, and the planner is a pure function of it.
 * Transient disagreement only delays a link, never conflicts — see
 * [PersistencePlanner]'s KDoc.
 */
class MeshViewTracker(
    private val freshWindowMs: Long = DEFAULT_FRESH_WINDOW_MS,
    private val maxPeers: Int = DEFAULT_MAX_PEERS,
    private val clock: Clock = SystemClock,
) : SynchronizedObject() {

    private data class Entry(val mode: UserMode, val neighbors: List<String>, val beaconAtMs: Long)

    private val peers = LinkedHashMap<String, Entry>()

    /**
     * Record a peer's SELF_PRESENCE beacon. [beaconAtMs] is the beacon's own
     * timestamp (future values clamped to now); a beacon no newer than what we
     * hold is ignored (returns false).
     */
    fun record(
        peerUserId: String,
        mode: UserMode,
        neighbors: List<String>,
        beaconAtMs: Long,
    ): Boolean = synchronized(this) {
        val now = clock.now()
        val stamp = if (beaconAtMs > now) now else beaconAtMs
        val prev = peers[peerUserId]
        if (prev != null && stamp <= prev.beaconAtMs) return false
        peers.remove(peerUserId)                 // re-insert at MRU end
        peers[peerUserId] = Entry(mode, neighbors, stamp)
        if (peers.size > maxPeers) {
            val eldest = peers.entries.iterator().next().key
            peers.remove(eldest)
        }
        true
    }

    /** True if we hold a non-stale beacon entry for [peerUserId] (O124 solicited-reply gate). */
    fun hasFresh(peerUserId: String): Boolean = synchronized(this) {
        peers[peerUserId]?.let { !isStale(it) } == true
    }

    /**
     * The peer's effective mode now. Fresh beacon → its declared mode; stale or
     * unknown → [UserMode.MOBILE] (the conservative "not a reliable anchor"
     * default).
     */
    fun modeFor(peerUserId: String): UserMode = synchronized(this) {
        val e = peers[peerUserId] ?: return UserMode.MOBILE
        if (isStale(e)) UserMode.MOBILE else e.mode
    }

    /**
     * Assemble the [MeshView] for the planner from all *fresh* beacons, plus
     * the local node itself.
     *
     * A node never beacons about itself, so the local node must inject its own
     * identity here — the planner has to budget the local node's own degree and
     * include its own adjacency. Pass [selfId] (with [selfMode] and
     * [selfNeighbors]) and it is folded into the view exactly like a received
     * beacon. Omit it (the no-self overload) for pure "what I heard from others".
     *
     * - `modes` = each fresh peer's declared mode (+ self).
     * - `edges` = undirected pairs from each node's `recentlyExchangedWith`,
     *   kept only when the *other* endpoint is also a known node — a link both
     *   endpoints could realize. An edge naming a node no beacon covers is
     *   dropped (can't budget an unknown node's degree; a one-sided edge can't
     *   converge).
     *
     * Pure read; does not mutate (stale entries linger until [pruneStale] or
     * LRU eviction, but never appear in the view).
     */
    fun assembleView(
        selfId: String? = null,
        selfMode: UserMode = UserMode.MOBILE,
        selfNeighbors: List<String> = emptyList(),
    ): MeshView = synchronized(this) {
        val modes = LinkedHashMap<String, UserMode>()
        val adjacency = LinkedHashMap<String, List<String>>()
        for ((id, e) in peers) if (!isStale(e)) {
            modes[id] = e.mode
            adjacency[id] = e.neighbors
        }
        if (selfId != null) {
            modes[selfId] = selfMode
            adjacency[selfId] = selfNeighbors
        }
        val edges = LinkedHashSet<Pair<String, String>>()
        for ((id, neighbors) in adjacency) {
            for (n in neighbors) if (n != id && n in modes) edges += canonical(id, n)
        }
        MeshView(modes = modes, edges = edges)
    }

    /** Drop entries whose last beacon is older than [olderThanMs]. Call periodically. */
    fun pruneStale(olderThanMs: Long = freshWindowMs) = synchronized(this) {
        val cutoff = clock.now() - olderThanMs
        val it = peers.entries.iterator()
        while (it.hasNext()) if (it.next().value.beaconAtMs < cutoff) it.remove()
    }

    private fun isStale(e: Entry): Boolean = clock.now() - e.beaconAtMs > freshWindowMs

    private fun canonical(x: String, y: String): Pair<String, String> =
        if (x <= y) x to y else y to x

    companion object {
        /** Beacons older than this drop out of the view. ~3× the FREE beacon interval (2 min). */
        const val DEFAULT_FRESH_WINDOW_MS = 6 * 60 * 1000L
        const val DEFAULT_MAX_PEERS = 2_000
    }
}
