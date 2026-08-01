package com.rumor.mesh.simulator.engine

import kotlinx.coroutines.runBlocking
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
}
