package com.rumor.mesh.simulator.engine

import kotlinx.coroutines.delay

/**
 * Exchange delivery is asynchronous — `SimNode.deliverExchange` hands the
 * result to `GossipEngine.onExchange`, which launches ingest on the node
 * scope. Post-exchange assertions must await the effect rather than assert
 * (or fixed-delay) immediately; these suites shipped with bare asserts and
 * never ran (`:simulator:test` didn't compile on main), so the races were
 * never observed. Polls until [condition] holds or [timeoutMs] elapses; the
 * caller's own assertion then reports the failure state.
 */
suspend fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < deadline) delay(10)
}
