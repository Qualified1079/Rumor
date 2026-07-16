package com.rumor.mesh.core.model

/**
 * Persistent per-peer link summary. One row per peerId; updated
 * after every successful gossip session. Ranking signal for the
 * gossip scheduler — top-K by `bytesRelayed / (1 + failureCount)`
 * via `RouteRepository.getPreferred` (G19, O3).
 *
 * **Latency is stored but NOT used for ranking** — on BLE/Wi-Fi
 * Direct, `latencyMs` is mostly discovery timing rather than link
 * quality, so it's kept for diagnostics only. See the architectural
 * decisions section in CLAUDE.md.
 *
 * Survives restart (Room-backed on Android, in-memory on simulator).
 * Contrast with `NeighborStore`, which is the live "right now in
 * radio range" view that disappears on disconnect.
 */
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

/**
 * O29 Tier-1 routing primitive. "I saw target via fromPeer at
 * recordedAtMs." Local-only data — never broadcast (per O58
 * tiering), never sent in HELLO. Powers next-hop selection: when
 * the local node wants to DM `targetUserId`, it consults
 * `BreadcrumbRepository.getCandidates` for recent inbound peers
 * and hands the DM to top-K rather than flooding.
 *
 * [hopCount] is the inbound message's effective hop distance at
 * record time; lower is a stronger candidate. Older breadcrumbs
 * decay out via `BreadcrumbRepository.pruneOld`.
 */
data class Breadcrumb(
    val targetUserId: String,
    val arrivedFromPeerId: String,
    val hopCount: Int,
    val recordedAtMs: Long,
)
