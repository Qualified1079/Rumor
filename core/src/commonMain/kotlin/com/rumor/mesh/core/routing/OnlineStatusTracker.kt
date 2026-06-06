package com.rumor.mesh.core.routing

import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.model.OnlineStatus
import com.rumor.mesh.core.model.PeerPresence
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val ONLINE_WINDOW_MS = 5 * 60 * 1000L
private const val RECENTLY_WINDOW_MS = 30 * 60 * 1000L

/**
 * Tracks the per-userId "last seen" timestamp and derives a coarse
 * [PeerPresence] for each known peer:
 *  - **ONLINE** — direct contact within the last [ONLINE_WINDOW_MS]
 *    (5 minutes by default).
 *  - **RECENTLY_SEEN** — between 5 and 30 minutes ago.
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
class OnlineStatusTracker : SynchronizedObject() {
    private val lastSeen = mutableMapOf<String, Long>()

    private val _statuses = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())
    val statuses: StateFlow<Map<String, PeerPresence>> = _statuses.asStateFlow()

    fun recordDirectContact(userId: String) = synchronized(this) {
        lastSeen[userId] = SystemClock.now()
        recompute()
    }

    fun mergeRemoteStatus(remoteUsers: Map<String, Long>) = synchronized(this) {
        for ((id, ts) in remoteUsers) {
            val existing = lastSeen[id]
            if (existing == null || ts > existing) lastSeen[id] = ts
        }
        recompute()
    }

    fun currentSnapshot(): Map<String, Long> = synchronized(this) {
        val cutoff = SystemClock.now() - RECENTLY_WINDOW_MS
        lastSeen.filterValues { it >= cutoff }
    }

    fun statusFor(userId: String): OnlineStatus = synchronized(this) {
        val ts = lastSeen[userId] ?: return@synchronized OnlineStatus.AWAY
        val age = SystemClock.now() - ts
        when {
            age <= ONLINE_WINDOW_MS -> OnlineStatus.ONLINE
            age <= RECENTLY_WINDOW_MS -> OnlineStatus.RECENTLY
            else -> OnlineStatus.AWAY
        }
    }

    private fun recompute() {
        val now = SystemClock.now()
        val updated = lastSeen.mapValues { (userId, ts) ->
            val status = when {
                now - ts <= ONLINE_WINDOW_MS -> OnlineStatus.ONLINE
                now - ts <= RECENTLY_WINDOW_MS -> OnlineStatus.RECENTLY
                else -> OnlineStatus.AWAY
            }
            PeerPresence(userId, status, ts)
        }
        _statuses.value = updated
    }
}
