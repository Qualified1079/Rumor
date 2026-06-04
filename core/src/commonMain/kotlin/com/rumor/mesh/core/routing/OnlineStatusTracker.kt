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
