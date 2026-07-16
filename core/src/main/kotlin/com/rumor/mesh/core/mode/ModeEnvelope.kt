package com.rumor.mesh.core.mode

import com.rumor.mesh.core.model.UserMode

/**
 * O62 — the single source of truth for what each [UserMode] *does*.
 *
 * [ModeProfile] decides *which* mode the device is in (state → mode);
 * [ModeEnvelope] says what that mode's behaviour envelope is. Every component
 * that used to branch on the old binary `StaticMode` flag now reads the value
 * it needs off `ModeEnvelope.forMode(currentMode)` instead — no scattered
 * `if (isStatic)` checks anywhere (that scattering was the O62 gate violation
 * this type exists to close; see handoff §6).
 *
 * **Envelope ordering.** For every dimension, FREE ⊇ STATIC ⊇ MOBILE in
 * effort: FREE spends the most radio/CPU/battery/storage, MOBILE the least.
 * Free-mode + plugged-in is the natural infrastructure anchor (O57) without a
 * separate build.
 *
 * **Platform neutrality.** `:core` has no Android imports, so scan aggression
 * is a neutral [ScanPower] the app maps to `ScanSettings.SCAN_MODE_*` /
 * `AdvertiseSettings.ADVERTISE_MODE_*`. Millisecond cadences are plain Longs
 * the orchestrator consumes.
 *
 * **The recorded values live in CLAUDE.md's O62 row** — this file and that
 * row must stay in sync; changing a number here is a design decision, note it
 * there.
 */
data class ModeEnvelope(
    /** (a) BLE scan + advertise aggression. App maps to ScanSettings/AdvertiseSettings. */
    val scanPower: ScanPower,
    /**
     * (b) Wi-Fi Direct discovery cadence. How often the orchestrator kicks
     * `discoverPeers()`. 0 means "continuous" (kick again as soon as the last
     * cycle settles); a positive value is the pause between cycles.
     */
    val wifiDiscoveryCadenceMs: Long,
    /** (c) Gossip round interval — how often the client re-runs a session while a peer is nearby. */
    val gossipRoundIntervalMs: Long,
    /**
     * (e) Breadcrumb-cache decay: crumbs older than this are pruned. Mobile
     * decays fast (this node is a poor, transient anchor); static/free hold
     * longer (they're stable routing substrate).
     */
    val breadcrumbDecayMs: Long,
    /**
     * (f) Self-presence beacon interval (G12/O30). null = "fire on mode change
     * only, never periodically" — the mobile default (a moving phone shouldn't
     * advertise itself as an anchor). Static/free beacon periodically so peers
     * learn they're reachable infrastructure.
     */
    val presenceBeaconIntervalMs: Long?,
    /**
     * (g) Routing-weight multiplier as a breadcrumb/anchor candidate (O58/O98).
     * Higher = this node is preferred as a next hop and a backbone dominator.
     * Feeds O98's degree-budget (a FREE anchor holds more persistent links).
     */
    val routingWeightMultiplier: Int,
    /**
     * (h, part 1) Scheduler quantum/cap multiplier — replaces the old
     * `Scheduler.STATIC_BOOST`. A plugged-in node pushes larger batches per
     * exchange and holds a deeper outbound queue.
     */
    val schedulerBoost: Int,
    /**
     * (h, part 2) Message-store capacity multiplier — replaces the old
     * `MessageStore.STATIC_CACHE_BOOST`. A plugged-in node caches more for
     * peers before eviction (O55: months of store-and-forward on infra nodes).
     */
    val storageCacheBoost: Int,
) {
    companion object {
        /**
         * The three recorded envelopes. Grounded in the pre-O62 constants
         * (STATIC_BOOST=3, STATIC_CACHE_BOOST=4, gossip 15s, breadcrumb 24h,
         * BLE LOW_POWER/BALANCED) so MOBILE/STATIC reproduce today's behaviour;
         * FREE is the new, more-aggressive tier that STATIC used to stand in for.
         *
         * (d) relay-batcher window is deliberately NOT a per-mode field: the
         * batcher's 100–500 ms window is a timing-correlation defence (O27/§12),
         * and weakening it just because a device is plugged in is a privacy
         * regression for a negligible latency win. Kept uniform across modes by
         * decision; RelayBatcher owns the constant.
         */
        fun forMode(mode: UserMode): ModeEnvelope = when (mode) {
            UserMode.MOBILE -> ModeEnvelope(
                scanPower = ScanPower.LOW_POWER,
                wifiDiscoveryCadenceMs = 30_000,   // periodic + backoff; conserve battery (O55 <2%/hr target)
                gossipRoundIntervalMs = 15_000,    // = pre-O62 GOSSIP_ROUND_INTERVAL_MS
                breadcrumbDecayMs = 60 * 60 * 1000L,          // 1h: transient node, crumbs go stale fast
                presenceBeaconIntervalMs = null,   // mode-change pulses only; don't advertise as anchor
                routingWeightMultiplier = 1,
                schedulerBoost = 1,
                storageCacheBoost = 1,
            )
            UserMode.STATIC -> ModeEnvelope(
                scanPower = ScanPower.BALANCED,    // = pre-O62 static BLE mode
                wifiDiscoveryCadenceMs = 15_000,
                gossipRoundIntervalMs = 10_000,
                breadcrumbDecayMs = 24 * 60 * 60 * 1000L,     // 24h: = pre-O62 BreadcrumbCache prune cutoff
                presenceBeaconIntervalMs = 5 * 60 * 1000L,    // 5 min
                routingWeightMultiplier = 3,       // = pre-O62 STATIC_BOOST spirit
                schedulerBoost = 3,                // = pre-O62 Scheduler.STATIC_BOOST
                storageCacheBoost = 4,             // = pre-O62 MessageStore.STATIC_CACHE_BOOST
            )
            UserMode.FREE -> ModeEnvelope(
                scanPower = ScanPower.LOW_LATENCY, // dedicate the radio (plugged in + screen off)
                wifiDiscoveryCadenceMs = 0,        // continuous
                gossipRoundIntervalMs = 5_000,
                breadcrumbDecayMs = 24 * 60 * 60 * 1000L,     // 24h (same as static; longer buys little)
                presenceBeaconIntervalMs = 2 * 60 * 1000L,    // 2 min
                routingWeightMultiplier = 10,      // strong backbone dominator (O58 Tier-3 opt-in)
                schedulerBoost = 6,
                storageCacheBoost = 8,
            )
        }
    }
}

/**
 * Platform-neutral scan aggression. The Android app maps these to
 * `ScanSettings.SCAN_MODE_*` and `AdvertiseSettings.ADVERTISE_MODE_*`; other
 * platforms map to their own APIs. Ascending in power/latency-tradeoff.
 */
enum class ScanPower { LOW_POWER, BALANCED, LOW_LATENCY }

/**
 * The device's current mode as a live signal. Replaces the old binary
 * `StaticMode` — components observe [mode] and read [ModeEnvelope.forMode] off
 * it. The app-side impl is driven by the manual toggle + plug/screen auto-
 * triggers (via [ModeProfile]); the simulator/tests use [FixedModeState].
 */
interface ModeState {
    val mode: kotlinx.coroutines.flow.StateFlow<UserMode>
    /** Convenience: the envelope for the current mode. */
    val envelope: ModeEnvelope get() = ModeEnvelope.forMode(mode.value)
}

/** Fixed [ModeState] for tests and the simulator. MOBILE unless constructed otherwise. */
class FixedModeState(mode: UserMode = UserMode.MOBILE) : ModeState {
    override val mode: kotlinx.coroutines.flow.StateFlow<UserMode> =
        kotlinx.coroutines.flow.MutableStateFlow(mode)
}
