package com.rumor.mesh.core.model

data class Route(
    val peerId: String,
    val latencyMs: Long,
    val hopCount: Int,
    val lastUpdatedMs: Long,
    val sessionCount: Int,
    /** Cumulative bytes successfully transferred with this peer. Primary ranking signal. */
    val bytesRelayed: Long = 0,
    /**
     * Number of attempted sessions that ended without a successful exchange.
     * Combined with [bytesRelayed] to rank peers by reliability-adjusted throughput:
     * a flaky high-bytes peer ranks below a steady moderate-bytes peer.
     */
    val failureCount: Int = 0,
)

data class Breadcrumb(
    val targetUserId: String,
    val arrivedFromPeerId: String,
    val hopCount: Int,
    val recordedAtMs: Long,
)
