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
     * Count of sessions that failed (handshake failure, mid-exchange disconnect,
     * sig-failure storm). O3: ranking penalises high-failure peers by
     * `bytesRelayed / (1 + failureCount)`. A peer that exchanges a lot but
     * also fails a lot is less preferred than one with steady delivery.
     */
    val failureCount: Int = 0,
)

data class Breadcrumb(
    val targetUserId: String,
    val arrivedFromPeerId: String,
    val hopCount: Int,
    val recordedAtMs: Long,
)
