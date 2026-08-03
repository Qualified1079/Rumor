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
import org.junit.Test

/**
 * O160/O204 follow-up — does breadcrumb routing (a) save bandwidth vs flooding
 * and (b) stay as reliable as flooding when a relay ON THE PATH drops off, by
 * falling back to flood where the trail breaks?
 *
 * Measured faithfully (real `messagesForExchange` + `deliverExchange` offer path,
 * every offer round-tripped through `WireJson` to strip `@Transient` `intendedPeers`
 * exactly as a socket would — see SmartRoutingReachTest for why `SimTransport`
 * cannot model routing). Two metrics per arm: **delivered?** (B has the DM) and
 * **touched** (# nodes that received it = bandwidth proxy; re-offers are
 * summary-suppressed on the real wire, so nodes-reached ≈ payload transmissions).
 *
 * Topology — one straight crumb chain A→m1→m2→m3→B, a BYPASS around m2 (m1─r─m3),
 * and bystander leaves hung off the interior relays so flood has extra to spray:
 *
 *        L1a L1b            L3a L3b
 *          \ /                \ /
 *   A ──── m1 ──── m2 ──── m3 ──── B
 *           \______ r ______/          (bypass: m1─r─m3)
 *
 * m2 is taken OFFLINE. B is still reachable via the bypass m1─r─m3.
 *
 * Three arms:
 *   1. FLOOD (no crumbs): delivers via the bypass, sprays every bystander. Reliable, expensive.
 *   2. ROUTED, naive single crumb (straight line only; m1's ONE crumb is the dead m2):
 *      m1 faithfully offers only to the dead m2 and — critically — does NOT fall
 *      back to flood, because it still HAS a crumb (a stale one is not "no route").
 *      The DM is STRANDED at m1. Cheap but BROKEN. This is the honest fragility of
 *      tight targeting: the current fallback is "flood if no crumb", not "flood if
 *      the crumb target is unreachable", and a node can't cheaply tell m2 is dead.
 *   3. ROUTED, crumb diversity (m1 caches BOTH next-hops toward B: m2 AND r — a
 *      top-K trail, all from locally-available cache): m1 still offers to the live
 *      r, r has no crumb so it floods onward to m3, m3's crumb carries it to B.
 *      Delivers AND still skips every bystander. Reliable AND cheap.
 *
 * Conclusion the arms encode: crumb *diversity* (keeping >1 cached next-hop), not
 * flooding, is what buys back delivery-robustness while preserving the bandwidth
 * win. A single stale crumb is strictly worse than flooding under a mid-path drop.
 */
class SmartRoutingDropoutTest {

    // 0=A 1=B 2=m1 3=m2(dead) 4=m3 5=r(bypass) 6,7=leaves off m1 8,9=leaves off m3
    private val A = 0; private val B = 1
    private val m1 = 2; private val m2 = 3; private val m3 = 4; private val r = 5
    private val leaves = listOf(6, 7, 8, 9)

    private enum class Crumbs { NONE, SINGLE, DIVERSE }
    private data class Arm(val delivered: Boolean, val touched: Int)

    @Test
    fun `mid-path dropout — flood and crumb-diverse routing deliver, a single stale crumb strands`() = runBlocking<Unit> {
        val flood = run(Crumbs.NONE)
        val single = run(Crumbs.SINGLE)
        val diverse = run(Crumbs.DIVERSE)
        println("DROPOUT-RESULT flood=$flood single=$single diverse=$diverse")

        // Positive control: plain flood must deliver via the bypass and — being
        // flood — must spray the bystanders too (high bandwidth).
        assertTrue("FLOOD must deliver via the bypass when m2 drops", flood.delivered)
        assertTrue("FLOOD must spray bystanders (high touch)", flood.touched >= 8)

        // The fragility: a naive single (now-stale) crumb strands the DM at m1 —
        // strictly WORSE than flooding under a mid-path drop.
        assertFalse(
            "A single stale crumb must STRAND the DM (m1 offers only to the dead m2, " +
                "never falls back to flood) — the honest cost of tight targeting",
            single.delivered,
        )

        // The variation that works, using only locally-cached alternates: crumb
        // diversity delivers AND stays cheaper than flood (bystanders untouched).
        assertTrue("crumb-diverse routing must deliver via the cached bypass hop", diverse.delivered)
        assertTrue(
            "crumb-diverse routing must be cheaper than flood " +
                "(diverse=${diverse.touched} < flood=${flood.touched})",
            diverse.touched < flood.touched,
        )
    }

    private suspend fun run(crumbs: Crumbs): Arm {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val n = (0..9).map { SimNode(it, scope) }
            registerContact(n[A], n[B])

            val allEdges = listOf(
                A to m1, m1 to m2, m2 to m3, m3 to B,   // straight chain
                m1 to r, r to m3,                       // bypass around m2
                m1 to 6, m1 to 7, m3 to 8, m3 to 9,     // bystander leaves
            )
            val liveEdges = allEdges.filterNot { it.first == m2 || it.second == m2 }

            when (crumbs) {
                Crumbs.NONE -> {}
                Crumbs.SINGLE -> {
                    n[A].breadcrumbs.record(n[B].userId, n[m1].userId)
                    n[m1].breadcrumbs.record(n[B].userId, n[m2].userId)   // single crumb → the dead node
                    n[m3].breadcrumbs.record(n[B].userId, n[B].userId)
                }
                Crumbs.DIVERSE -> {
                    n[A].breadcrumbs.record(n[B].userId, n[m1].userId)
                    n[m1].breadcrumbs.record(n[B].userId, n[m2].userId)   // top-K: the (dead) straight hop
                    n[m1].breadcrumbs.record(n[B].userId, n[r].userId)    //   AND the live bypass hop
                    n[m3].breadcrumbs.record(n[B].userId, n[B].userId)
                }
            }
            if (crumbs != Crumbs.NONE) delay(50)

            val dmId = n[A].gossipEngine.composeDirect(
                recipientId = n[B].userId,
                recipientPublicKey = n[B].identityProvider.identity.value!!.publicKeyBytes,
                text = "survive?",
            )?.id ?: error("composeDirect null")

            repeat(30) {
                for ((x, y) in liveEdges) {
                    realExchange(n[x], n[y]); realExchange(n[y], n[x])
                }
                delay(5)
            }
            delay(40)

            val touched = (0..9).count { n[it].messageRepo.getById(dmId) != null }
            return Arm(delivered = n[B].messageRepo.getById(dmId) != null, touched = touched)
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
