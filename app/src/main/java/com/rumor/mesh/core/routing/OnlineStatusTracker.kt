package com.rumor.mesh.core.routing

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.OnlineStatus
import com.rumor.mesh.core.model.PeerPresence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which User IDs have been recently seen on the mesh and classifies
 * them into coarse reachability buckets.
 *
 * Updated by two sources:
 * 1. [recordDirectContact] — called after a confirmed Wi-Fi Direct gossip exchange
 *    (identity verified via HELLO packet, most reliable)
 * 2. [mergeRemoteStatus] — called with the online-status map shared by a peer
 *    during a gossip exchange (second-hand information, less precise)
 *
 * Status thresholds (tunable via the private constants):
 *   [OnlineStatus.ONLINE]   — seen within 5 minutes
 *   [OnlineStatus.RECENTLY] — seen within 30 minutes
 *   [OnlineStatus.AWAY]     — older than 30 minutes; entry pruned after 24 hours
 *
 * [OnlineStatus] and [PeerPresence] live in core/model so both the UI and this
 * module can reference them without creating a UI → routing dependency.
 */
class OnlineStatusTracker() {

    private val TAG = "OnlineStatus"

    private val ONLINE_MS   = 5L  * 60 * 1_000
    private val RECENTLY_MS = 30L * 60 * 1_000
    private val PRUNE_MS    = 24L * 60 * 60 * 1_000

    private val lastSeen = ConcurrentHashMap<String, Long>()

    private val _statuses = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())
    val statuses: StateFlow<Map<String, PeerPresence>> = _statuses.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        scope.launch {
            while (true) {
                delay(60_000)
                refresh()
            }
        }
    }

    /** Call after a successful gossip session — identity was verified via HELLO. */
    fun recordDirectContact(userId: String) {
        lastSeen[userId] = System.currentTimeMillis()
        RumorLog.d(TAG, "Direct: ${userId.take(16)}…")
        refresh()
    }

    /**
     * Merge the online-status map received from a remote peer.
     *
     * Wire values are wall-clock epoch ms — every Android device has a hardware RTC,
     * so we can store and forward absolute timestamps directly without per-hop drift
     * accumulation. The fresher epoch wins. Tolerates small clock skew between
     * devices (worst case: a peer's slightly-fast clock makes a contact look
     * marginally more recent than reality).
     */
    fun mergeRemoteStatus(remoteUsers: Map<String, Long>) {
        for ((userId, sentAtMs) in remoteUsers) {
            lastSeen.merge(userId, sentAtMs) { existing, new -> maxOf(existing, new) }
        }
        if (remoteUsers.isNotEmpty()) refresh()
    }

    /**
     * Returns a snapshot of recently-seen users as wall-clock epoch timestamps,
     * suitable for inclusion in a [GossipPacket.OnlineStatus] message.
     * Only includes entries within [RECENTLY_MS] to keep the payload small.
     */
    fun currentSnapshot(): Map<String, Long> {
        val cutoff = System.currentTimeMillis() - RECENTLY_MS
        return lastSeen.filter { (_, ts) -> ts > cutoff }
    }

    fun statusFor(userId: String): OnlineStatus = classify(
        System.currentTimeMillis() - (lastSeen[userId] ?: return OnlineStatus.AWAY)
    )

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun refresh() {
        val now = System.currentTimeMillis()
        lastSeen.entries.removeIf { (_, ts) -> now - ts > PRUNE_MS }
        _statuses.value = lastSeen.entries.associate { (userId, ts) ->
            userId to PeerPresence(userId, classify(now - ts), ts)
        }
    }

    private fun classify(elapsedMs: Long) = when {
        elapsedMs < ONLINE_MS   -> OnlineStatus.ONLINE
        elapsedMs < RECENTLY_MS -> OnlineStatus.RECENTLY
        else                    -> OnlineStatus.AWAY
    }
}
