package com.rumor.mesh.core.routing

import com.rumor.mesh.core.Clock
import com.rumor.mesh.core.SystemClock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * O124 — decides whether to answer an inbound SELF_PRESENCE pulse with our own.
 *
 * A node that just joined (or was wiped) is invisible to the mesh until its
 * first exchange carries traffic; conversely it knows nothing about anyone
 * until peers' beacons reach it. Replying to a pulse from an unknown-or-stale
 * peer closes that loop within one exchange round.
 *
 * Anti-abuse shape: the cooldown is enforced against OUR clock, per peer —
 * nothing wire-supplied is trusted, so a modified client spamming probes gets
 * exactly one reply per [cooldownMs] window no matter what it sends. Bounded
 * LRU so a sybil swarm can't grow the map without bound.
 */
class PresenceReplyGate(
    private val clock: Clock = SystemClock,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxPeers: Int = DEFAULT_MAX_PEERS,
) : SynchronizedObject() {
    private val lastReplyAt = LinkedHashMap<String, Long>()

    /**
     * True exactly when we should answer [peerId]'s pulse: the peer was NOT
     * already fresh in our mesh view, and we haven't answered it within the
     * cooldown. A `true` return records the reply stamp.
     */
    fun shouldReply(peerId: String, peerWasFresh: Boolean): Boolean = synchronized(this) {
        if (peerWasFresh) return false
        val now = clock.now()
        val last = lastReplyAt[peerId]
        if (last != null && now - last < cooldownMs) return false
        lastReplyAt.remove(peerId)
        lastReplyAt[peerId] = now
        if (lastReplyAt.size > maxPeers) {
            lastReplyAt.remove(lastReplyAt.entries.iterator().next().key)
        }
        true
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 2 * 60_000L
        const val DEFAULT_MAX_PEERS = 2000
    }
}
