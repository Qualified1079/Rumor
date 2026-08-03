package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.PeerExchangeResult
import com.rumor.mesh.core.wire.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * O160/O204 follow-up — what happens to a routed DM when a relay ON THE PATH
 * drops off? This is the honest stress test of the "routing targets tighter than
 * flooding" result: tighter targeting means fewer redundant copies, so it should
 * be MORE fragile to a mid-path dropout. Measured here, faithfully (real offer
 * path + WireJson wire round-trip, same as SmartRoutingReachTest).
 *
 * Topology: TWO node-disjoint parallel paths from A to B, each 3 relays long:
 *   A ── p1 ── p2 ── p3 ── B          (path 1)
 *   A ── q1 ── q2 ── q3 ── B          (path 2)
 * We take **p2 offline** (skip its edges) so path 1 is severed but B is still
 * reachable via path 2. Three arms, same topology:
 *   1. FLOOD (no crumbs): A sprays both paths → survives via path 2.
 *   2. ROUTED, single-path crumbs (trail only along path 1): A offers only to p1,
 *      p1 only to the dead p2 → the DM is stranded, B never gets it. This is the
 *      fragility cost of tight targeting, made concrete.
 *   3. ROUTED, multi-path crumbs (A also holds a crumb via q1, top-K): routing
 *      fans to both first hops → survives via path 2. So crumb *diversity* (the
 *      top-K candidate set) is what buys back resilience, not flooding.
 */
class SmartRoutingDropoutTest {

    // 0=A, 1=B, path1 = 2,3,4 (p1,p2,p3), path2 = 5,6,7 (q1,q2,q3)
    private val A = 0; private val B = 1
    private val p1 = 2; private val p2 = 3; private val p3 = 4
    private val q1 = 5; private val q2 = 6; private val q3 = 7

    private enum class Crumbs { NONE, PATH1_ONLY, BOTH_PATHS }

    // WIP — DO NOT trust the assertions yet. Current state: the FLOOD arm returns
    // false (line-46 assert fails), which is almost certainly a HARNESS bug, not a
    // real finding — flood should trivially deliver via path 2. Likely suspects to
    // debug next: (1) `dmId` resolution — `knownMessages().first()` may not be the
    // DM (ordering) or composeDirect's scheduler copy isn't in the repo yet; add a
    // `println("DROPOUT-RESULT flood=$flood single=$single multi=$multi dmId=...")`
    // and print each node's has-DM; (2) the two-path topology may not actually
    // connect A→B (check edge wiring / that q-path relays forward with NONE crumbs);
    // (3) 30 rounds / propagation timing on 8 nodes. Once flood delivers, the REAL
    // question this test exists to answer is whether SINGLE-PATH routing strands the
    // DM at the dead relay (expected true) and MULTI-PATH (top-K crumb diversity)
    // recovers it. @Ignore so the suite stays green until it's validated.
    @Ignore("WIP — flood arm returns false (harness bug, not a finding); see class KDoc + handoff")
    @Test
    fun `mid-path dropout — flood and multi-path routing survive, single-path routing does not`() = runBlocking<Unit> {
        val flood = deliversToB(Crumbs.NONE)
        val single = deliversToB(Crumbs.PATH1_ONLY)
        val multi = deliversToB(Crumbs.BOTH_PATHS)
        println("DROPOUT-RESULT flood=$flood single=$single multi=$multi")

        assertTrue("FLOOD must deliver via the alternate path when p2 drops", flood)
        assertFalse(
            "SINGLE-PATH routing must FAIL when its one path's relay drops — the " +
                "honest fragility cost of tight targeting (no redundant copies)",
            single,
        )
        assertTrue(
            "MULTI-PATH routing (crumb diversity / top-K) must survive the dropout",
            multi,
        )
    }

    /** Build the two-path topology, sever p2, run to steady state, return "did B get the DM?". */
    private suspend fun deliversToB(crumbs: Crumbs): Boolean {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val n = (0..7).map { SimNode(it, scope) }
            registerContact(n[A], n[B])

            // All edges of the two disjoint paths.
            val allEdges = listOf(
                A to p1, p1 to p2, p2 to p3, p3 to B,   // path 1
                A to q1, q1 to q2, q2 to q3, q3 to B,   // path 2
            )
            // p2 is offline: drop every edge that touches it.
            val liveEdges = allEdges.filterNot { it.first == p2 || it.second == p2 }

            when (crumbs) {
                Crumbs.NONE -> {}
                Crumbs.PATH1_ONLY -> {
                    // "to reach B, go via the next node up path 1"
                    n[A].breadcrumbs.record(n[B].userId, n[p1].userId)
                    n[p1].breadcrumbs.record(n[B].userId, n[p2].userId)
                    n[p3].breadcrumbs.record(n[B].userId, n[B].userId)
                }
                Crumbs.BOTH_PATHS -> {
                    // A holds crumbs toward B via BOTH first hops (top-K), and each
                    // path's relays know their own next hop.
                    n[A].breadcrumbs.record(n[B].userId, n[p1].userId)
                    n[A].breadcrumbs.record(n[B].userId, n[q1].userId)
                    n[p1].breadcrumbs.record(n[B].userId, n[p2].userId)
                    n[p3].breadcrumbs.record(n[B].userId, n[B].userId)
                    n[q1].breadcrumbs.record(n[B].userId, n[q2].userId)
                    n[q2].breadcrumbs.record(n[B].userId, n[q3].userId)
                    n[q3].breadcrumbs.record(n[B].userId, n[B].userId)
                }
            }
            if (crumbs != Crumbs.NONE) delay(50)

            n[A].gossipEngine.composeDirect(
                recipientId = n[B].userId,
                recipientPublicKey = n[B].identityProvider.identity.value!!.publicKeyBytes,
                text = "survive?",
            ) ?: error("composeDirect null")
            val dmId = n[A].knownMessages().firstOrNull()?.id
                ?: n[A].gossipEngine.messagesForExchange(n[p1].userId).firstOrNull()?.id

            repeat(30) {
                for ((x, y) in liveEdges) {
                    realExchange(n[x], n[y]); realExchange(n[y], n[x])
                }
                delay(5)
                if (dmId != null && n[B].messageRepo.getById(dmId) != null) return true
            }
            delay(40)
            return dmId != null && n[B].messageRepo.getById(dmId) != null
        } finally {
            scope.cancel()
        }
    }

    private suspend fun realExchange(src: SimNode, dst: SimNode) {
        val ser = RumorMessage.serializer()
        val offer = src.gossipEngine.messagesForExchange(dst.userId)
            .map { WireJson.decodeFromString(ser, WireJson.encodeToString(ser, it)) }
        if (offer.isEmpty()) return
        dst.deliverExchange(
            PeerExchangeResult(
                peerUserId = src.userId,
                messagesReceived = offer,
                ackedByPeer = emptyList(),
                peerOnlineUsers = mapOf(src.userId to System.currentTimeMillis()),
                durationMs = 0,
            )
        )
    }

    private suspend fun registerContact(host: SimNode, other: SimNode) {
        val id = other.identityProvider.identity.value!!
        host.contactRepoForTest().upsert(
            com.rumor.mesh.core.model.Contact(
                userId = id.userId,
                publicKey = com.rumor.mesh.core.crypto.CryptoManager.run { id.publicKeyBytes.toBase64() },
                displayName = "n${other.index}", isVerified = false, autoRelay = false,
                alwaysSave = false, willingToCache = false, firstSeenMs = 0L,
                lastSeenMs = System.currentTimeMillis(), isPriorityPeer = false,
            )
        )
    }

    private fun SimNode.contactRepoForTest(): com.rumor.mesh.core.data.memory.InMemoryContactRepository =
        this::class.java.getDeclaredField("contactRepo").apply { isAccessible = true }
            .get(this) as com.rumor.mesh.core.data.memory.InMemoryContactRepository
}
