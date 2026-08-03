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
 * Real-`GossipEngine` validation of the O198/liveness routing prototype
 * (`GossipEngine.livenessRouting`), the sim-recommended fix from
 * `RoutingBandwidthSweepTest`. Same diamond as [SmartRoutingDropoutTest], SINGLE
 * (now-stale) crumb chain A→m1→m2→m3→B with m2 killed and a live bypass m1─r─m3:
 *
 *  - liveness OFF (baseline): m1 holds one crumb → the dead m2 → it offers only
 *    there and never falls back → the DM STRANDS (reproduces the dropout finding).
 *  - liveness ON: m2 is never ONLINE in m1's [OnlineStatusTracker] (it never
 *    exchanges), so m1's only crumb candidate is not live → m1 floods → the DM
 *    reaches B via the bypass. The A/B on the real engine confirms the abstract
 *    model's LIVE result.
 */
class SmartRoutingLivenessTest {

    private val A = 0; private val B = 1
    private val m1 = 2; private val m2 = 3; private val m3 = 4; private val r = 5

    @Test
    fun `liveness fallback rescues a stale-crumb mid-path dropout that strands without it`() = runBlocking<Unit> {
        val stranded = deliversToB(liveness = false)
        val rescued = deliversToB(liveness = true)
        println("LIVENESS-RESULT off=$stranded on=$rescued")
        assertFalse("baseline: a single stale crumb strands the DM at m1", stranded)
        assertTrue("liveness ON floods when the only crumb next-hop isn't live → delivers", rescued)
    }

    private suspend fun deliversToB(liveness: Boolean): Boolean {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val n = (0..5).map { SimNode(it, scope) }
            n.forEach { it.gossipEngine.livenessRouting = liveness }
            registerContact(n[A], n[B])

            val allEdges = listOf(A to m1, m1 to m2, m2 to m3, m3 to B, m1 to r, r to m3)
            val liveEdges = allEdges.filterNot { it.first == m2 || it.second == m2 }

            // Single straight-line crumb trail — m1's only crumb is the (dead) m2.
            n[A].breadcrumbs.record(n[B].userId, n[m1].userId)
            n[m1].breadcrumbs.record(n[B].userId, n[m2].userId)
            n[m3].breadcrumbs.record(n[B].userId, n[B].userId)
            delay(50)

            val dmId = n[A].gossipEngine.composeDirect(
                recipientId = n[B].userId,
                recipientPublicKey = n[B].identityProvider.identity.value!!.publicKeyBytes,
                text = "survive?",
            )?.id ?: error("composeDirect null")

            repeat(30) {
                for ((x, y) in liveEdges) { realExchange(n[x], n[y]); realExchange(n[y], n[x]) }
                delay(5)
            }
            delay(40)
            return n[B].messageRepo.getById(dmId) != null
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
