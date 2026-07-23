package com.rumor.mesh.core.trust

/**
 * O135(4) — the web-of-trust admission frontier (SSB `hops` semantics ported to
 * Rumor's model; see `sybil_research.md`).
 *
 * Given signed vouch edges (voucher → vouchee), a local seed (self), a set of
 * blocked userIds, and a hop budget N, [frontier] returns the set of userIds
 * admitted into the seed's trust view: everyone reachable from the seed by
 * following vouch edges within N hops, EXCEPT any node that is blocked (and its
 * entire downstream subtree — a blocked node is never traversed, so no one it
 * vouched for is admitted *via that node*).
 *
 * Why this bounds sybils (the SybilLimit `O(log n) per attack edge` result):
 * minting identities is free, but a sybil enters the frontier only via a REAL
 * vouch from someone already in the seed's frontier — an "attack edge." The
 * attacker's admitted count is bounded by the attack edges they persuade honest
 * users to form, NOT by keys minted. **Block the voucher and the whole sybil
 * subtree drops out** (block-the-signer severs the subtree), because a blocked
 * node is not traversed.
 *
 * **Advogato-inoculation (research §3 / Ruderman's break):** a node's remaining
 * reach is `hops - depthFromSeed`, computed HERE from the seed outward — it is
 * NEVER inherited from or asserted by the voucher. A "confused" high-reach node
 * cannot grant its vouchees its own reach; they sit one hop deeper by
 * construction. This is why the frontier can't be inflated by tricking one
 * well-connected node.
 *
 * **Block model (Rumor decision, overrides SSB):** [blocked] is the SEED's own
 * blocks + the seed's subscribed blocklists — NOT blocks auto-inherited through
 * the vouch graph (see O135 / O78). Which blocks prune is the seed's granular
 * choice; SSB's forced transitive block is deliberately not ported.
 *
 * Pure function, no I/O — the wire layer (signed FriendVouch/Block messages,
 * gossip propagation, projection cache) is a separate integration concern.
 */
object TrustGraph {

    /**
     * @param seed the local identity (always admitted implicitly as the root;
     *   not included in the returned set unless something vouches for it).
     * @param vouches voucher userId → the set of userIds they vouch for.
     * @param blocked userIds the seed refuses to traverse or admit.
     * @param hops maximum vouch-chain length from the seed (SSB default 2).
     */
    fun frontier(
        seed: String,
        vouches: Map<String, Set<String>>,
        blocked: Set<String> = emptySet(),
        hops: Int = 2,
    ): Set<String> {
        if (hops < 1) return emptySet()
        val admitted = LinkedHashSet<String>()
        // BFS from the seed; depth is computed here (Advogato-inoculation), so a
        // node's reach is structural, never wire-asserted.
        var frontierAtDepth: Set<String> = setOf(seed)
        var depth = 0
        val visited = hashSetOf(seed)
        while (depth < hops && frontierAtDepth.isNotEmpty()) {
            val next = LinkedHashSet<String>()
            for (voucher in frontierAtDepth) {
                // A blocked voucher is never traversed → its subtree is severed.
                if (voucher != seed && voucher in blocked) continue
                for (vouchee in vouches[voucher].orEmpty()) {
                    if (vouchee in blocked) continue          // never admit a blocked node
                    if (vouchee == seed) continue
                    if (visited.add(vouchee)) {
                        admitted.add(vouchee)
                        next.add(vouchee)
                    }
                }
            }
            frontierAtDepth = next
            depth++
        }
        return admitted
    }
}
