package com.rumor.mesh.core.model

data class Route(
    val peerId: String,
    val latencyMs: Long,
    val hopCount: Int,
    val lastUpdatedMs: Long,
    val sessionCount: Int,
    /** Cumulative bytes successfully transferred with this peer. Primary ranking signal. */
    val bytesRelayed: Long = 0,
)

data class Breadcrumb(
    val targetUserId: String,
    val arrivedFromPeerId: String,
    val hopCount: Int,
    val recordedAtMs: Long,
)
