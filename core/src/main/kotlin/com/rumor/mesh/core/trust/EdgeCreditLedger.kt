package com.rumor.mesh.core.trust

/**
 * O135(2) alternative — Ostra-style per-edge spam credit (Mislove et al., NSDI
 * 2008; see `sybil_research.md` §4). A refinement of the coarse global/LRU
 * aggregate cap: instead of one shared budget everyone competes for, EACH trust
 * edge on the delivery path carries its own credit balance. Marking a message
 * spam debits every edge on its path; when any edge hits zero it can no longer
 * carry traffic.
 *
 * The point vs a global bucket: a sybil swarm behind one real "attack edge"
 * (a traitor a victim friended) all funnels through that ONE edge, so spam
 * marks exhaust *that edge* and nothing else — honest edges keep their credit.
 * A global bucket, by contrast, is drained by the sybil spam and then throttles
 * honest senders too (collateral). Ostra localizes the damage to the edge the
 * attacker actually compromised — the finer-grained cousin of O135(2)'s
 * identity-independent aggregate cap.
 *
 * Pure bookkeeping; no wire/IO. A path is the trust chain from the receiver to
 * the sender (receiver-anchored, so credit is the RECEIVER's local resource —
 * incoercible, per O135's "no global score").
 */
class EdgeCreditLedger(private val initialCredit: Int) {
    private val balance = HashMap<Pair<String, String>, Int>()

    private fun credit(edge: Pair<String, String>) = balance.getOrPut(edge) { initialCredit }

    private fun edgesOf(path: List<String>): List<Pair<String, String>> =
        path.zipWithNext()

    /** True iff every edge on [path] still has credit — i.e. the message can be delivered. */
    fun canDeliver(path: List<String>): Boolean = edgesOf(path).all { credit(it) > 0 }

    /** Debit one credit from every edge on [path] (call when the receiver marks it spam). */
    fun markSpam(path: List<String>) {
        for (e in edgesOf(path)) balance[e] = (credit(e) - 1).coerceAtLeast(0)
    }

    fun creditOn(a: String, b: String): Int = credit(a to b)
}

/**
 * The baseline it's compared against: a single shared budget for ALL inbound,
 * regardless of path. This is O135(2)'s coarse aggregate cap. It bounds total
 * spam but cannot distinguish the compromised edge from honest ones — so once a
 * sybil flood drains it, honest senders are throttled too.
 */
class GlobalSpamBucket(private var budget: Int) {
    fun canDeliver(): Boolean = budget > 0
    fun markSpam() { budget = (budget - 1).coerceAtLeast(0) }
    fun remaining(): Int = budget
}
