package com.rumor.mesh.core.trust

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O135(2) — Ostra per-edge credit vs a global spam bucket, under the canonical
 * attack: one honest "traitor" the victim friended, with a swarm of sybils
 * behind that single attack edge, all spamming. The question the research posed
 * (`sybil_research.md` §4): does the finer-grained per-edge model localize the
 * damage better than the coarse global bucket?
 *
 * Delivery paths are receiver-anchored trust chains:
 *   sybil_i:  victim → traitor → sybil_i   (all share the victim→traitor edge)
 *   honest:   victim → honestFriend → goodPerson
 */
class EdgeCreditScenarioTest {

    private val CREDIT = 10
    private val SYBILS = 200

    private fun sybilPath(i: Int) = listOf("victim", "traitor", "sybil$i")
    private val honestPath = listOf("victim", "honestFriend", "goodPerson")

    @Test
    fun `Ostra localizes the damage — the attack edge exhausts, honest edges keep delivering`() {
        val ledger = EdgeCreditLedger(initialCredit = CREDIT)

        // The victim marks sybil spam as it arrives. Each mark debits the shared
        // victim→traitor edge; after CREDIT marks that edge is dead.
        var sybilDelivered = 0
        for (i in 1..SYBILS) {
            if (ledger.canDeliver(sybilPath(i))) {
                sybilDelivered++
                ledger.markSpam(sybilPath(i))
            }
        }

        // Total sybil spam that got through is bounded by the edge credit (~CREDIT),
        // NOT by the number of sybils — 200 identities, but the one attack edge
        // caps them.
        assertTrue("sybil spam ($sybilDelivered) must be bounded by the attack-edge credit ($CREDIT)",
            sybilDelivered <= CREDIT)

        // CRUCIAL: the honest path is on DIFFERENT edges, so it still delivers.
        assertTrue("honest traffic must be unaffected by the sybil flood", ledger.canDeliver(honestPath))
    }

    @Test
    fun `global bucket bounds total spam too, but inflicts collateral on honest senders`() {
        val bucket = GlobalSpamBucket(budget = CREDIT)

        var sybilDelivered = 0
        for (i in 1..SYBILS) {
            if (bucket.canDeliver()) { sybilDelivered++; bucket.markSpam() }
        }

        // Same headline bound...
        assertTrue("global bucket also caps total spam", sybilDelivered <= CREDIT)

        // ...but the bucket is now EMPTY, so an honest sender is throttled too —
        // the collateral damage Ostra avoids. This is the whole difference.
        assertTrue("the shared bucket is drained by the sybils → honest senders collateral-throttled",
            !bucket.canDeliver())
    }

    @Test
    fun `NEGATIVE CONTROL - before any spam marks, BOTH models deliver everything`() {
        // Proof the bound in the main tests comes from the mechanism, not from a
        // path that never delivered in the first place.
        val ledger = EdgeCreditLedger(initialCredit = CREDIT)
        val bucket = GlobalSpamBucket(budget = CREDIT)
        assertTrue(ledger.canDeliver(sybilPath(1)))
        assertTrue(ledger.canDeliver(honestPath))
        assertTrue(bucket.canDeliver())
    }
}
