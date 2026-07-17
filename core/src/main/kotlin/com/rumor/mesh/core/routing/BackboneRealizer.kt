package com.rumor.mesh.core.routing

import com.rumor.mesh.core.model.UserMode

/**
 * O98 Phase 3b — orient the planner's undirected backbone into radio roles.
 *
 * [PersistencePlanner] emits undirected edges; Wi-Fi Direct realizes an edge as
 * a group with exactly one Group Owner. The radio constraints this must respect:
 *
 *  - a device hosts at most ONE group (one GO role);
 *  - a client attaches to exactly ONE group;
 *  - a device is never GO and client simultaneously (concurrent P2P-GO +
 *    P2P-client is not supported on the hardware we target).
 *
 * So the backbone is decomposed into stars: hubs host, their backbone
 * neighbours join. Like the planner, this is a pure deterministic function of
 * its input — every node that holds the same links + modes computes the same
 * role assignment, so the host brings its group up and its clients dial in
 * with [GroupCredentials]-derived credentials without any election traffic.
 *
 * Edges the star decomposition cannot realize (a node that would need to be in
 * two groups, or host while already a client — e.g. the far end of a 4-chain)
 * are reported in [Realization.dropped]. They are NOT an error: the legacy
 * negotiated-connect flow still covers them opportunistically, and the view's
 * recency decay re-plans around persistent failures. This is the documented
 * limitation of "client is in exactly one group" — the planner's tree shape
 * keeps it rare (hubs are capacity-ranked, so stars are the common case).
 */
object BackboneRealizer {

    sealed interface Role {
        /** Bring up an autonomous group; [clients] are expected to dial in. */
        data class Host(val clients: Set<String>) : Role
        /** Join [hostUserId]'s group using derived credentials. */
        data class Client(val hostUserId: String) : Role
        /** Not part of any realizable backbone star. */
        data object None : Role
    }

    data class Realization(
        private val clientOf: Map<String, String>,
        val realized: Set<Link>,
        val dropped: Set<Link>,
    ) {
        private val clientsOfHost: Map<String, Set<String>> =
            clientOf.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() }

        fun roleOf(userId: String): Role = when {
            userId in clientsOfHost -> Role.Host(clientsOfHost.getValue(userId))
            userId in clientOf -> Role.Client(clientOf.getValue(userId))
            else -> Role.None
        }
    }

    /**
     * Decompose [links] into host/client stars. [modes] supplies capacity
     * ranking; endpoints absent from it (view churn during hysteresis hold)
     * default to MOBILE, the conservative rank.
     */
    fun realize(links: Set<Link>, modes: Map<String, UserMode>): Realization {
        val neighbors = HashMap<String, MutableList<String>>()
        for (l in links) {
            neighbors.getOrPut(l.a) { mutableListOf() }.add(l.b)
            neighbors.getOrPut(l.b) { mutableListOf() }.add(l.a)
        }
        neighbors.values.forEach { it.sort() }

        fun cap(id: String) = PersistencePlanner.capacityFor(modes[id] ?: UserMode.MOBILE)

        // Hub ranking: capacity first (anchors host), backbone degree second
        // (centres of stars host), userId last as the deterministic tie-break.
        val ranked = neighbors.keys.sortedWith(
            compareByDescending<String> { cap(it) }
                .thenByDescending { neighbors.getValue(it).size }
                .thenBy { it }
        )

        val hosts = LinkedHashSet<String>()
        val clientOf = LinkedHashMap<String, String>()

        for (node in ranked) {
            if (node in clientOf) continue
            val unassigned = neighbors.getValue(node).filter { it !in hosts && it !in clientOf }
            if (unassigned.isNotEmpty()) {
                // Claim every still-free neighbour as a client of this new star.
                // A would-be hub claimed here stops being a hub — that is the
                // "edge between two hubs → one joins the other" rule, and the
                // reason a claimed hub's own remaining edges land in [dropped].
                hosts += node
                unassigned.forEach { clientOf[it] = node }
            }
            // No unassigned neighbours: every edge of this node runs to another
            // star's client (a host neighbour would have claimed us when it was
            // processed) — unrealizable under one-group-per-client; stays None.
        }

        val realized = links.filterTo(LinkedHashSet()) { l ->
            clientOf[l.a] == l.b || clientOf[l.b] == l.a
        }
        return Realization(
            clientOf = clientOf,
            realized = realized,
            dropped = links.minus(realized),
        )
    }
}
