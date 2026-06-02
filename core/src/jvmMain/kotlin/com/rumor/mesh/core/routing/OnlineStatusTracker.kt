package com.rumor.mesh.core.routing

import com.rumor.mesh.core.model.OnlineStatus
import com.rumor.mesh.core.model.PeerPresence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val ONLINE_WINDOW_MS = 5 * 60 * 1000L
private const val RECENTLY_WINDOW_MS = 30 * 60 * 1000L

class OnlineStatusTracker {
    private val lastSeen = mutableMapOf<String, Long>()

    private val _statuses = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())
    val statuses: StateFlow<Map<String, PeerPresence>> = _statuses.asStateFlow()

    @Synchronized
    fun recordDirectContact(userId: String) {
        lastSeen[userId] = System.currentTimeMillis()
        recompute()
    }

    @Synchronized
    fun mergeRemoteStatus(remoteUsers: Map<String, Long>) {
        for ((id, ts) in remoteUsers) {
            val existing = lastSeen[id]
            if (existing == null || ts > existing) lastSeen[id] = ts
        }
        recompute()
    }

    @Synchronized
    fun currentSnapshot(): Map<String, Long> {
        val cutoff = System.currentTimeMillis() - RECENTLY_WINDOW_MS
        return lastSeen.filterValues { it >= cutoff }
    }

    @Synchronized
    fun statusFor(userId: String): OnlineStatus {
        val ts = lastSeen[userId] ?: return OnlineStatus.AWAY
        val age = System.currentTimeMillis() - ts
        return when {
            age <= ONLINE_WINDOW_MS -> OnlineStatus.ONLINE
            age <= RECENTLY_WINDOW_MS -> OnlineStatus.RECENTLY
            else -> OnlineStatus.AWAY
        }
    }

    private fun recompute() {
        val now = System.currentTimeMillis()
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
