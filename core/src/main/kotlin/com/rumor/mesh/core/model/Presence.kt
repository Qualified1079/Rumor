package com.rumor.mesh.core.model

/**
 * Coarse reachability status of a peer on the mesh.
 * Produced by [com.rumor.mesh.core.routing.OnlineStatusTracker],
 * consumed by UI and plugins. Lives in core/model so neither layer
 * needs to import from the other.
 */
enum class OnlineStatus {
    /** Seen via direct gossip exchange within the last 5 minutes. */
    ONLINE,
    /** Seen within the last 30 minutes. */
    RECENTLY,
    /** Not seen in the last 30 minutes (or never). */
    AWAY,
}

data class PeerPresence(
    val userId: String,
    val status: OnlineStatus,
    /** Epoch milliseconds of the last confirmed sighting. */
    val lastSeenMs: Long,
)
