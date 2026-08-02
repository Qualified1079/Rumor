package com.rumor.mesh.simulator.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Top-of-ladder fidelity: DM delivery over REAL [com.rumor.mesh.core.transport.lan.LanTransport]
 * (real TCP + GossipSession wire) across in-process real-engine nodes, with a
 * deterministic line topology (mDNS off) that forces a multi-hop RELAY.
 *
 * Slow by nature — LanTransport gossips every 10 s, so a 2-hop carry is tens of
 * seconds of wall clock. This is the real-wire backbone the other sliders will
 * layer onto; it proves the transport + session + relay path end-to-end, which
 * the SimTransport-backed [MeshHarness] structurally cannot.
 */
class LanMeshHarnessTest {

    @Test
    fun dmRelaysOverRealLanTransportThroughMiddleNode() {
        val h = LanMeshHarness(n = 3, seed = 1)
        try {
            runBlocking {
                // Line: 0 — 1 — 2. Node 0 and node 2 are NOT wired directly, so a
                // DM 0->2 can only arrive by node 1 relaying it over real TCP.
                h.start(edges = listOf(0 to 1, 1 to 2))

                val oneHop = h.sendDm(from = 0, to = 1, text = "direct neighbour")!!
                val twoHop = h.sendDm(from = 0, to = 2, text = "must be relayed by node 1")!!

                // 1-hop control: proves the wire works at all (fast).
                assertTrue("1-hop DM never arrived over real LanTransport",
                    h.awaitDelivered(oneHop, to = 1, timeoutMs = 40_000))

                // The real test: 2-hop delivery requires node 1 to relay real
                // ciphertext it is not the recipient of, over real sockets.
                assertTrue("2-hop DM was not relayed to node 2 over real LanTransport",
                    h.awaitDelivered(twoHop, to = 2, timeoutMs = 40_000))

                // And node 1 (the relay) really carried it in its own store.
                assertTrue("relay node 1 should hold the ciphertext it forwarded",
                    h.nodes[1].messageRepo.getById(twoHop) != null)
            }
        } finally {
            h.stop()
        }
    }

    /**
     * O202 duty-cycle property on real code: a DM sent while the recipient is
     * OFFLINE is carried by the relay over real TCP and delivered once the
     * recipient comes back online — store-and-forward across a duty-cycled
     * device, the exact O55 case ("meet you today, your recipient next week").
     */
    @Test
    fun dmCarriedOverRealWireUntilOfflineRecipientReturns() {
        val h = LanMeshHarness(n = 3, seed = 2)
        try {
            runBlocking {
                h.start(edges = listOf(0 to 1, 1 to 2))
                h.setOnline(2, up = false)                       // recipient goes dark

                val id = h.sendDm(from = 0, to = 2, text = "carry me until node 2 is back")!!

                // Relay (node 1) picks it up over real wire while node 2 is offline.
                assertTrue("relay should carry the DM while recipient is offline",
                    h.awaitDelivered(id, to = 1, timeoutMs = 30_000))

                // Negative control: recipient is offline, so it has NOT received it.
                delay(2_000)
                assertFalse("offline recipient must not have the DM yet",
                    h.nodes[2].messageRepo.getById(id) != null)

                // Recipient returns → the carried ciphertext is delivered over real TCP.
                h.setOnline(2, up = true)
                assertTrue("DM not delivered after the offline recipient returned",
                    h.awaitDelivered(id, to = 2, timeoutMs = 40_000))
            }
        } finally {
            h.stop()
        }
    }

    /**
     * Dead/hostile relay over real wire. Diamond 0—{1,2}—3 with node 1 refusing
     * to forward: the DM must still reach node 3 by routing around it through
     * the honest node 2. Real-mesh resilience to a non-relaying node, on real TCP.
     */
    @Test
    fun dmRoutesAroundHostileRelayOverRealWire() {
        val h = LanMeshHarness(n = 4, seed = 3, hostile = setOf(1))
        try {
            runBlocking {
                h.start(edges = listOf(0 to 1, 1 to 3, 0 to 2, 2 to 3))
                val id = h.sendDm(from = 0, to = 3, text = "route around node 1")!!
                assertTrue("DM should reach node 3 via honest node 2 despite hostile node 1",
                    h.awaitDelivered(id, to = 3, timeoutMs = 40_000))
            }
        } finally {
            h.stop()
        }
    }

    /**
     * Negative control proving the hostile relay actually blocks: line 0—1—2
     * with node 1 refusing to forward and NO alternate path. Node 1 absorbs the
     * ciphertext (it still receives) but never offers it onward, so node 2 never
     * gets it. (Pairs with the diamond test above — same defect, opposite outcome.)
     */
    @Test
    fun hostileRelayWithNoAlternatePathBlocksDelivery() {
        val h = LanMeshHarness(n = 3, seed = 4, hostile = setOf(1))
        try {
            runBlocking {
                h.start(edges = listOf(0 to 1, 1 to 2))
                val id = h.sendDm(from = 0, to = 2, text = "should be swallowed by node 1")!!

                // The hostile node still absorbs it (proves it reached the block).
                assertTrue("hostile relay should have received the DM it refuses to forward",
                    h.awaitDelivered(id, to = 1, timeoutMs = 30_000))
                // ...but never forwards it: node 2 stays empty even after ample rounds.
                delay(15_000)
                assertFalse("hostile relay must not forward — node 2 should never receive it",
                    h.nodes[2].messageRepo.getById(id) != null)
            }
        } finally {
            h.stop()
        }
    }

    /**
     * Byzantine flood over real wire: the relay node floods the mesh with junk
     * broadcasts (past the per-exchange offer cap), and a legitimate DM must
     * still reach its recipient — real-transport starvation resistance.
     */
    @Test
    fun legitDmSurvivesAByzantineFloodOverRealWire() {
        val h = LanMeshHarness(n = 3, seed = 5)
        try {
            runBlocking {
                h.start(edges = listOf(0 to 1, 1 to 2))
                h.flood(from = 1, count = 250)                   // relay floods junk (> the 200 offer cap)
                val id = h.sendDm(from = 0, to = 2, text = "must survive the flood")!!
                assertTrue("legit DM starved by the flood — never reached node 2",
                    h.awaitDelivered(id, to = 2, timeoutMs = 75_000))
            }
        } finally {
            h.stop()
        }
    }
}
