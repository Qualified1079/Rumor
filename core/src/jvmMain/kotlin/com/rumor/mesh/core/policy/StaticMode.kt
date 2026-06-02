package com.rumor.mesh.core.policy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the user has designated this device an always-on "static" node —
 * plugged in and free to scan harder, hold a little more, cache more for
 * peers, and push larger batches per exchange.
 *
 * Deliberately a plain on/off switch, not a role or a backbone tier: it just
 * means "I'm plugged in for the night". Components read [enabled] and pick
 * heavier defaults when it is true.
 */
interface StaticMode {
    val enabled: StateFlow<Boolean>
}

/** Fixed [StaticMode] for tests and the simulator. Off unless constructed otherwise. */
class FixedStaticMode(enabled: Boolean = false) : StaticMode {
    override val enabled: StateFlow<Boolean> = MutableStateFlow(enabled)
}
