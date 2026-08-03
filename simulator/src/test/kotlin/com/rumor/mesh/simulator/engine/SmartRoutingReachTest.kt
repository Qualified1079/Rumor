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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O160 delivery-level validation — what does breadcrumb routing REALLY buy?
 *
 * **First finding (documented, not a bug in this test): routing does NOT extend
 * reach.** A hop budget (`hopsToLive`) would cap flood reach — but in the current
 * implementation it does not gate reach at all: `MessageStore.ingest` stores a
 * message at its *as-received* `hopsToLive`, and the durable store-backfill
 * (`offerable`, re-offered by `messagesForExchange`) re-offers that stored copy
 * as long as `hopsToLive > 0`. The per-hop decrement lives only on the one-shot
 * scheduler relay copy, which the backfill overrides — so both flooded and routed
 * DMs reach the entire dedup-bounded connected component. (Confirmed empirically:
 * on a plain 24-node line the *flooded* DM reached the far end just like the
 * routed one.) This is a PROPERTY OF THE PROTOCOL, surfaced by driving the REAL
 * offer path, not a limitation of the simulated wire — filed as O204.
 *
 * **What routing actually buys: TARGETING.** `messagesForExchange` offers a
 * relayed DM only to breadcrumb candidates for its recipient (or floods when no
 * crumb exists — same fallback the relay path uses). So a DM WITH a crumb trail
 * travels a narrow path to the recipient; a DM WITHOUT one floods every node.
 * That is the real, honest benefit — less bandwidth, fewer nodes seeing the
 * ciphertext (O27/O58) — and this test proves it on a topology where the crumb
 * path is a strict subset of the component: a backbone line A…B with leaf nodes
 * hanging off each backbone hop. Both arms deliver to B; the routed arm leaves
 * the leaves untouched, the flood arm sprays all of them.
 *
 * Driven through the REAL `GossipEngine.messagesForExchange` + `deliverExchange`
 * path (the same code the phone/`:node` run), NOT `SimTransport` (which offers
 * the raw repo snapshot, ignoring `intendedPeers`, the `offerable` filter, and
 * the decrement — it cannot model routing targeting at all).
 */
class SmartRoutingReachTest {

    private val backbone = 10          // A = backbone[0], B = backbone[9]
    private val leavesPerHop = 2       // leaves hung off each interior backbone node

    @Test
    fun `routing delivers to B along a narrow path, flooding sprays the whole component`() = runBlocking<Unit> {
        val routed = run(layCrumbs = true)
        val flood = run(layCrumbs = false)

        // Both must actually deliver to B — routing that dropped the message
        // would "touch fewer nodes" trivially and meaninglessly. (Measured:
        // routed touched 10 = the backbone path exactly; flood touched all 26.)
        assertTrue("routed arm must deliver to B", routed.reachedB)
        assertTrue("flood arm must deliver to B", flood.reachedB)

        // The point: routing touches far fewer nodes than flooding.
        assertTrue(
            "routing must touch strictly fewer nodes than flooding " +
                "(routed=${routed.touched}, flood=${flood.touched})",
            routed.touched < flood.touched,
        )
        // Routing should stay close to the backbone length; flooding should hit
        // essentially the whole component (backbone + all leaves).
        val total = backbone + (backbone - 2) * leavesPerHop
        assertTrue(
            "routed touch (${routed.touched}) should be near the backbone length ($backbone)",
            routed.touched <= backbone + 2,
        )
        assertTrue(
            "flood touch (${flood.touched}) should cover most of the $total-node component",
            flood.touched >= total - 2,
        )
    }

    @Test
    fun `flood reach on a line is not bounded by the 15-hop budget (O204)`() = runBlocking<Unit> {
        // Faithful line (wire round-trip), NO crumbs → pure flood. If hopsToLive
        // bounded reach we'd stop near index 15; measure where it actually stops.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val n = 24
            val line = (0 until n).map { SimNode(it, scope) }
            val a = line[0]; val b = line[n - 1]
            registerContact(a, b)
            val dm = a.gossipEngine.composeDirect(
                recipientId = b.userId,
                recipientPublicKey = b.identityProvider.identity.value!!.publicKeyBytes,
                text = "flood",
            )!!
            repeat(n + 10) {
                for (i in 0 until n - 1) { realExchange(line[i], line[i + 1]); realExchange(line[i + 1], line[i]) }
                delay(5)
            }
            delay(60)
            val furthest = (0 until n).lastOrNull { line[it].messageRepo.getById(dm.id) != null } ?: -1
            println("O204-REACH floodFurthestIndex=$furthest budget=15")
            assertTrue(
                "O204: flood reached index $furthest — the store-backfill re-offers " +
                    "stored copies at their as-received hopsToLive, so the per-hop " +
                    "decrement (scheduler-only) does not bound reach",
                furthest > 15,
            )
        } finally {
            scope.cancel()
        }
    }

    private data class Outcome(val reachedB: Boolean, val touched: Int)

    private suspend fun run(layCrumbs: Boolean): Outcome {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            // Node 0..backbone-1 are the backbone; the rest are leaves.
            val bb = (0 until backbone).map { SimNode(it, scope) }
            val leaves = ArrayList<SimNode>()
            val edges = ArrayList<Pair<SimNode, SimNode>>()
            for (i in 0 until backbone - 1) edges.add(bb[i] to bb[i + 1])
            var idx = backbone
            for (k in 1 until backbone - 1) {
                repeat(leavesPerHop) {
                    val leaf = SimNode(idx++, scope)
                    leaves.add(leaf)
                    edges.add(bb[k] to leaf)
                }
            }
            val all = bb + leaves
            val a = bb[0]
            val b = bb[backbone - 1]
            registerContact(a, b)

            if (layCrumbs) {
                for (k in 1 until backbone - 1) {
                    bb[k].breadcrumbs.record(targetUserId = b.userId, fromPeerId = bb[k + 1].userId)
                }
                delay(50)  // let the async persistent breadcrumb upserts settle
            }

            val dm = a.gossipEngine.composeDirect(
                recipientId = b.userId,
                recipientPublicKey = b.identityProvider.identity.value!!.publicKeyBytes,
                text = "reach",
            ) ?: error("composeDirect returned null")

            repeat(backbone + leaves.size + 10) {
                for ((x, y) in edges) {
                    realExchange(x, y)
                    realExchange(y, x)
                }
                delay(5)
            }
            delay(60)

            val touched = all.count { it.messageRepo.getById(dm.id) != null }
            return Outcome(reachedB = b.messageRepo.getById(dm.id) != null, touched = touched)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun realExchange(src: SimNode, dst: SimNode) {
        // Round-trip each offered message through the real wire codec, exactly as
        // a socket transport would. Critically this strips @Transient fields —
        // above all `intendedPeers`, the sender's LOCAL routing decision — so the
        // receiver re-derives its own next-hop instead of inheriting the previous
        // hop's. (Passing the live object, as SimTransport does, leaks that state
        // and wedges routed delivery after two hops.)
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
        val otherIdentity = other.identityProvider.identity.value!!
        host.contactRepoForTest().upsert(
            com.rumor.mesh.core.model.Contact(
                userId = otherIdentity.userId,
                publicKey = com.rumor.mesh.core.crypto.CryptoManager.run { otherIdentity.publicKeyBytes.toBase64() },
                displayName = "n${other.index}",
                isVerified = false,
                autoRelay = false,
                alwaysSave = false,
                willingToCache = false,
                firstSeenMs = 0L,
                lastSeenMs = System.currentTimeMillis(),
                isPriorityPeer = false,
            )
        )
    }

    private fun SimNode.contactRepoForTest(): com.rumor.mesh.core.data.memory.InMemoryContactRepository =
        this::class.java.getDeclaredField("contactRepo").apply { isAccessible = true }
            .get(this) as com.rumor.mesh.core.data.memory.InMemoryContactRepository
}
