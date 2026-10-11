package com.rumor.mesh.simulator.engine

import kotlinx.coroutines.delay

/**
 * Exchange delivery is asynchronous — `SimNode.deliverExchange` hands the
 * result to `GossipEngine.onExchange`, which launches ingest on the node
 * scope. Post-exchange assertions must await the effect rather than assert
 * (or fixed-delay) immediately; these suites shipped with bare asserts and
 * never ran (`:simulator:test` didn't compile on main), so the races were
 * never observed.
 *
 * **Timeout FAILS the test** (changed 2026-10-11). The original version returned
 * silently when [condition] never held and left "the caller's own assertion
 * [to] report the failure state". That only works where the awaited condition
 * IS the asserted property; at the ~30 call sites where it is a *precondition*
 * (a crumb laid, a seed propagated, a scheduler queue filled) a silent timeout
 * let the test run on against a state it was never written for. The observed
 * cost: `PerPeerRoutingTest` failed on 2 of 4 runs on an unmodified tree in a
 * 4-core container — different sub-assertion each time, none of them the actual
 * property under test. The failure mode is worse in the other direction, where
 * a timed-out precondition makes an absence assertion pass for free. A loud
 * timeout is the only honest behaviour: either the effect arrived, or the test
 * says which wait expired.
 *
 * The default budget is deliberately generous (a passing wait costs only as long
 * as the effect actually takes, so a long ceiling is nearly free) — it exists to
 * catch a hang, not to measure latency. Don't tighten it to "make the suite
 * fast"; that just re-buys the flakiness under CI load.
 */
suspend fun awaitUntil(
    timeoutMs: Long = 10_000,
    /** What is being waited for, named in the timeout message. Worth the few words. */
    what: String? = null,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        delay(10)
    }
    // One last read: the loop can exit on the deadline between a poll and the
    // effect landing, and a timeout message for an effect that DID arrive would
    // be its own false signal.
    if (condition()) return
    throw AssertionError(
        "awaitUntil timed out after ${timeoutMs}ms waiting for " + (what ?: "the awaited effect") +
            " — the precondition this test needs never happened, so anything asserted " +
            "after this point would be measuring the wrong state."
    )
}
