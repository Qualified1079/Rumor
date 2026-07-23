package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.policy.KnownSendersInboxFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * O135(1) — the "known peers only" inbox filter (the research's ship-first
 * defense) proven end-to-end against a live sybil flood.
 *
 * Setup: one honest **victim**, a small set of honest **friends** (whom the
 * victim has affirmatively friended), and a swarm of **sybils** (minted
 * identities the victim has never friended). Everyone floods broadcasts to the
 * victim through the real GossipEngine/SimTransport path.
 *
 * The two properties that matter, together:
 *  1. **Defense works:** with `friendedSendersOnly`, the victim's INBOX
 *     (`incomingMessages`) contains ONLY friend traffic — zero sybil, no matter
 *     how many identities the swarm mints. This is why "allowlist good keys"
 *     beats "blocklist bad keys" (futile against churn, the O27/O60 floor).
 *  2. **Relay invariant preserved:** the victim's STORE still holds every sybil
 *     broadcast (it relays them for the rest of the mesh) — the filter is
 *     display-layer only. A sybil flood costs the victim's *attention* nothing
 *     while the mesh keeps carrying the traffic.
 *
 * Also pins the BASELINE (permissive filter) so the contrast is explicit: with
 * no filter, every sybil reaches the inbox — the current undefended behavior,
 * and the regression this defense removes.
 */
class SybilFloodScenarioTest {

    // Node counts kept modest: each SimNode is a full engine graph, and the
    // whole sim suite runs concurrently — 12 sybils is plainly a "flood" for
    // the 0-leak property without OOMing heap under parallel load. Scopes are
    // cancelled at test end so nodes don't accrete across the suite.
    private val HONEST_FRIENDS = 3
    private val SYBILS = 12

    private suspend fun floodAt(victim: SimNode, senders: List<SimNode>) {
        for (s in senders) {
            s.gossipEngine.composeBroadcast("spam-or-ham from ${s.userId.take(6)}")
            s.flushSchedulerToRepo()
        }
        // Deliver every sender's broadcast to the victim (a few rounds so the
        // relay/ingest path fully runs).
        repeat(2) {
            for (s in senders) SimTransport(s, victim).exchange(Random(1))
        }
    }

    @Test
    fun `friended-only inbox filter hides a sybil flood while relay still carries it`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // index 0 = victim; 1..3 = friends; 4..28 = sybils.
        val victimIdx = 0
        val friends = (1..HONEST_FRIENDS).map { SimNode(it, scope) }
        val sybils = (HONEST_FRIENDS + 1 until HONEST_FRIENDS + 1 + SYBILS).map { SimNode(it, scope) }
        val friendIds = friends.map { it.userId }.toSet()

        val victim = SimNode(
            victimIdx, scope,
            inboxFilterOverride = KnownSendersInboxFilter(
                // The victim composes nothing in this test, so the self-allow
                // branch is never exercised — null is fine here.
                localUserId = { null },
                isFriended = { it in friendIds },
                initial = com.rumor.mesh.core.policy.InboxPolicy(friendedSendersOnly = true),
            ),
        )

        try {
            floodAt(victim, friends + sybils)

            // Inbox settles: friends' broadcasts arrive; sybils' do not.
            awaitUntil(timeoutMs = 5_000) {
                friendIds.all { it in victim.inboxSenderIds }
            }

            // 1. Defense: NO sybil sender reached the inbox.
            val sybilIds = sybils.map { it.userId }.toSet()
            val leakedSybils = victim.inboxSenderIds.intersect(sybilIds)
            assertTrue("sybils leaked into a friended-only inbox: $leakedSybils", leakedSybils.isEmpty())
            assertEquals("inbox should be exactly the friend set", friendIds, victim.inboxSenderIds.intersect(friendIds))

            // 2. Relay invariant: the STORE still holds sybil broadcasts (relayed
            //    for the mesh), even though the inbox hid them.
            val storedSenders = victim.knownMessages().map { it.senderId }.toSet()
            val storedSybils = storedSenders.intersect(sybilIds)
            assertTrue(
                "the filter must NOT stop storage/relay — expected sybil broadcasts in the store, saw ${storedSybils.size}",
                storedSybils.isNotEmpty(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `baseline permissive inbox lets every sybil through (the regression this removes)`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sybils = (1..SYBILS).map { SimNode(it, scope) }
        val victim = SimNode(0, scope) // default PermissiveInboxFilter

        try {
            floodAt(victim, sybils)

            val sybilIds = sybils.map { it.userId }.toSet()
            awaitUntil(timeoutMs = 5_000) { victim.inboxSenderIds.intersect(sybilIds).size >= SYBILS }
            assertEquals(
                "baseline: with no filter every sybil reaches the inbox",
                SYBILS, victim.inboxSenderIds.intersect(sybilIds).size,
            )
        } finally {
            scope.cancel()
        }
    }
}
