package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.Clock

/**
 * O12: deterministic time source for sim. SimWorld advances [nowMs] from
 * its tick loop; every node + every protocol call inside the engine reads
 * `now()` from this object and sees the exact same value across replays.
 *
 * Volatile (not atomic) because reads can race with the tick-loop writer
 * but always within the same tick boundary — observers may see either the
 * previous or current tick's value; both are deterministic across runs.
 */
class SimClock(@Volatile var nowMs: Long = 0L) : Clock {
    override fun now(): Long = nowMs
}
