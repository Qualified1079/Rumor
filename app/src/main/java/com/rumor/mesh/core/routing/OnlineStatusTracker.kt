package com.rumor.mesh.core.routing

import com.rumor.mesh.core.logging.RumorLog
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

enum class OnlineStatus { ONLINE, RECENTLY, AWAY }

data class PeerPresence(
    val userId: String,
    val status: OnlineStatus,
    val lastSeenMs: Long,
)

/**
 * Tracks which User IDs have been recently seen on the mesh.
 * Updated by:
 *   - Direct gossip session completions (most reliable — identity confirmed via HELLO)
 *   - Remote online-status maps shared during gossip exchanges
 *
 * Status thresholds:
 *   ONLINE   — seen within 5 minutes
 *   RECENTLY — seen within 30 minutes
 *   AWAY     — older than 30 minutes (entry kept for 24h then pruned)
 */
@Singleton
class OnlineStatusTracker @Inject constructor() {

    private val TAG = "OnlineStatus"

    private val ONLINE_MS   = 5 * 60 * 1_000L
    private val RECENTLY_MS = 30 * 60 * 1_000L
    private val PRUNE_MS    = 24 * 60 * 60 * 1_000L

    // userId → last seen epoch ms
    private val lastSeen = ConcurrentHashMap<String, Long>()

    private val _statuses = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())
    val statuses: StateFlow<Map<String, PeerPresence>> = _statuses.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // Refresh status classifications and prune stale entries every minute
        scope.launch {
            while (true) {
                delay(60_000)
                refreshStatuses()
            }
        }
    }

    /** Called after a confirmed gossip session — identity is verified. */
    fun recordDirectContact(userId: String) {
        lastSeen[userId] = System.currentTimeMillis()
        RumorLog.d(TAG, "Direct contact: ${userId.take(16)}…")
        refreshStatuses()
    }

    /**
     * Merge online-status map received from a remote peer during gossip.
     * Remote timestamps are elapsed-ms (not epoch), so we convert to local epoch.
     */
    fun mergeRemoteStatus(remoteUsers: Map<String, Long>) {
        val now = System.currentTimeMillis()
        for ((userId, elapsedMs) in remoteUsers) {
            val estimatedLastSeen = now - elapsedMs
            // Only update if this is newer than what we already have
            lastSeen.merge(userId, estimatedLastSeen) { existing, new ->
                if (new > existing) new else existing
            }
        }
        if (remoteUsers.isNotEmpty()) refreshStatuses()
    }

    /**
     * Returns the current statuses as a map of userId → elapsed ms
     * suitable for inclusion in a [GossipPacket.OnlineStatus] message.
     */
    fun currentAsElapsed(): Map<String, Long> {
        val now = System.currentTimeMillis()
        return lastSeen
            .filter { (_, ts) -> now - ts < RECENTLY_MS }
            .mapValues { (_, ts) -> now - ts }
    }

    fun statusFor(userId: String): OnlineStatus {
        val ts = lastSeen[userId] ?: return OnlineStatus.AWAY
        return classify(System.currentTimeMillis() - ts)
    }

    private fun refreshStatuses() {
        val now = System.currentTimeMillis()
        // Prune entries older than 24h
        val iter = lastSeen.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value > PRUNE_MS) iter.remove()
        }

        val updated = lastSeen.entries.associate { (userId, ts) ->
            val elapsed = now - ts
            userId to PeerPresence(userId, classify(elapsed), ts)
        }
        _statuses.value = updated
    }

    private fun classify(elapsedMs: Long) = when {
        elapsedMs < ONLINE_MS   -> OnlineStatus.ONLINE
        elapsedMs < RECENTLY_MS -> OnlineStatus.RECENTLY
        else                    -> OnlineStatus.AWAY
    }
}
