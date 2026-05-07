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
import javax.inject.Inject
import javax.inject.Singleton

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
@Singleton
class OnlineStatusTracker @Inject constructor() {

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
     * Remote entries carry elapsed-ms (not epoch) computed at the peer's send time,
     * so they are converted to local epoch before storing. [sessionDurationMs] is
     * added to each elapsed value to compensate for the time the exchange itself
     * took — without this, every entry would be underaged by up to ~30 seconds.
     * Existing fresher entries are never overwritten.
     */
    fun mergeRemoteStatus(remoteUsers: Map<String, Long>, sessionDurationMs: Long = 0) {
        val now = System.currentTimeMillis()
        for ((userId, elapsedMs) in remoteUsers) {
            val estimated = now - elapsedMs - sessionDurationMs
            lastSeen.merge(userId, estimated) { existing, new -> maxOf(existing, new) }
        }
        if (remoteUsers.isNotEmpty()) refresh()
    }

    /**
     * Returns a snapshot of recently-seen users as elapsed-ms values,
     * suitable for inclusion in a [GossipPacket.OnlineStatus] message.
     * Only includes entries within [RECENTLY_MS] to keep the payload small.
     */
    fun currentAsElapsed(): Map<String, Long> {
        val now = System.currentTimeMillis()
        return lastSeen
            .filter { (_, ts) -> now - ts < RECENTLY_MS }
            .mapValues { (_, ts) -> now - ts }
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
