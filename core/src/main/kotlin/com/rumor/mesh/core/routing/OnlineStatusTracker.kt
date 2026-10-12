package com.rumor.mesh.core.routing

import com.rumor.mesh.core.Clock
import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.model.OnlineStatus
import com.rumor.mesh.core.model.PeerPresence
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// "Online" should mean reachable now: a live link refreshes this every ~10s
// round, so a lost peer reads RECENTLY within a minute rather than lingering
// as ONLINE for 5 (field 2026-10-11). Duty-cycled MOBILE peers may flicker.
private const val ONLINE_WINDOW_MS = 60 * 1000L
private const val RECENTLY_WINDOW_MS = 30 * 60 * 1000L

// O128: peer-asserted timestamps are clamped to now + this budget. Without it a
// single far-future lastSeen propagates hop-to-hop (each merge keeps the max)
// and pins a user "online" mesh-wide indefinitely. Same shape as the G42 HLC
// display clamp: honest clock skew passes, poison decays like any real entry.
private const val REMOTE_SKEW_BUDGET_MS = 2 * 60 * 1000L

// O120: entries past this age are AWAY and excluded from snapshots — keeping
// them only grows the map without bound over months of uptime.
private const val PRUNE_RETENTION_MS = 24 * 60 * 60 * 1000L

/**
 * Tracks the per-userId "last seen" timestamp and derives a coarse
 * [PeerPresence] for each known peer:
 *  - **ONLINE** — direct contact within the last [ONLINE_WINDOW_MS]
 *    (60 seconds).
 *  - **RECENTLY_SEEN** — between 1 and 30 minutes ago.
 *  - **OFFLINE** — more than [RECENTLY_WINDOW_MS] ago, or never seen.
 *
 * Two ingress paths:
 *  - [recordDirectContact] — first-hand: this node just received a
 *    verified message from the peer. Authoritative.
 *  - [mergeRemoteStatus] — second-hand: a peer's exchange-time
 *    snapshot of THEIR known peers. Used to learn about distant
 *    nodes the local node hasn't seen directly. Latest-wall-clock
 *    wins on merge.
 *
 * Recompute is synchronous on every mutation — the per-peer map is
 * small (typically dozens of contacts, maybe thousands in extreme
 * cases) and the StateFlow consumer renders the result. Thread-safe
 * via [SynchronizedObject].
 *
 * Privacy note: presence beacons that propagate to non-direct peers
 * are O30 / O58 Tier 3 territory and are not handled here — this
 * tracker only summarises what THIS node has observed. The
 * second-hand merge does carry presence information across hops,
 * but only as a coarse "this peer told me they saw X at time T"
 * signal — not full beacon propagation.
 */
class OnlineStatusTracker(
    private val wallClock: Clock = SystemClock,
    /**
     * Sightings are kept on a monotonic clock so their AGE survives any wall
     * clock change on this device, in either direction. Wall time appears only
     * at the edges: the legacy timestamp map and [PeerPresence.lastSeenMs].
     */
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : SynchronizedObject() {
    private val seenAtMono = mutableMapOf<String, Long>()

    private val _statuses = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())
    val statuses: StateFlow<Map<String, PeerPresence>> = _statuses.asStateFlow()

    fun recordDirectContact(userId: String) = synchronized(this) {
        seenAtMono[userId] = monotonicMs()
        recompute()
    }

    /** [remoteUsers] are timestamps on OUR wall clock (see OnlineStatus.recentUsersOnLocalClock). */
    fun mergeRemoteStatus(remoteUsers: Map<String, Long>) = synchronized(this) {
        val wallNow = wallClock.now()
        val monoNow = monotonicMs()
        val ceiling = wallNow + REMOTE_SKEW_BUDGET_MS
        for ((id, ts) in remoteUsers) {
            val mono = monoNow - (wallNow - minOf(ts, ceiling))
            val existing = seenAtMono[id]
            if (existing == null || mono > existing) seenAtMono[id] = mono
        }
        recompute()
    }

    /** O120: drop entries too old to ever surface again. Call from the host's maintenance tick. */
    fun pruneStale(retentionMs: Long = PRUNE_RETENTION_MS) = synchronized(this) {
        val cutoff = monotonicMs() - retentionMs
        if (seenAtMono.entries.removeAll { it.value < cutoff }) recompute()
    }

    /**
     * Re-derive statuses from elapsed time alone. Without a tick, a peer that
     * vanished kept its last status until some OTHER peer's contact happened
     * to trigger a recompute — on a quiet node, indefinitely.
     */
    fun refresh() = synchronized(this) { recompute() }

    /** Wall-clock form for peers that predate `_ext.ages`: now minus age. */
    fun currentSnapshot(): Map<String, Long> = synchronized(this) {
        val wallNow = wallClock.now()
        val monoNow = monotonicMs()
        seenAtMono.filterValues { monoNow - it <= RECENTLY_WINDOW_MS }
            .mapValues { (_, mono) -> wallNow - (monoNow - mono) }
    }

    fun statusFor(userId: String): OnlineStatus = synchronized(this) {
        val mono = seenAtMono[userId] ?: return@synchronized OnlineStatus.AWAY
        statusForAge(monotonicMs() - mono)
    }

    private fun statusForAge(age: Long) = when {
        age <= ONLINE_WINDOW_MS -> OnlineStatus.ONLINE
        age <= RECENTLY_WINDOW_MS -> OnlineStatus.RECENTLY
        else -> OnlineStatus.AWAY
    }

    private fun recompute() {
        val wallNow = wallClock.now()
        val monoNow = monotonicMs()
        _statuses.value = seenAtMono.mapValues { (userId, mono) ->
            val age = monoNow - mono
            PeerPresence(userId, statusForAge(age), wallNow - age)
        }
    }
}
