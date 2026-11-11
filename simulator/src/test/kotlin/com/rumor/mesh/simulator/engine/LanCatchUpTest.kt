package com.rumor.mesh.simulator.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A node that meets a peer holding more than one offer batch must end up with
 * all of it, over real sockets and the real `GossipSession`. The offer is capped
 * at 200 before the peer's summary is known, and the cap keeps the freshest by
 * sender clock, so on the bloom/id-list path (stores under the RBSR threshold)
 * nothing older than the freshest 200 was ever sent: the second round offered
 * the same 200, the peer already had them, and the session carried nothing.
 */
class LanCatchUpTest {

    private val authors = 8
    private val perAuthor = 75          // under the 100/s per-sender ingest budget
    private val total = authors * perAuthor

    private suspend fun seedHolder(h: LanMeshHarness, scope: CoroutineScope): List<String> {
        val ids = mutableListOf<String>()
        for (a in 0 until authors) {
            val author = SimNode(100 + a, scope)
            repeat(perAuthor) { i ->
                val msg = author.gossipEngine.composeBroadcast("backlog-$a-$i")!!
                h.nodes[0].seedMessage(msg)
                ids += msg.id
            }
        }
        return ids
    }

    private suspend fun heldBy(h: LanMeshHarness, node: Int, ids: List<String>) =
        ids.count { h.nodes[node].messageRepo.getById(it) != null }

    @Test
    fun aLateJoinerReceivesEverythingItsPeerHoldsNotOnlyTheFreshestBatch() {
        val h = LanMeshHarness(n = 2, seed = 21)
        val authorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            runBlocking {
                val ids = seedHolder(h, authorScope)
                assertEquals("precondition: the holder has the whole backlog", total, heldBy(h, 0, ids))
                h.start(edges = listOf(0 to 1))

                var got = 0
                val deadline = System.currentTimeMillis() + 150_000
                while (System.currentTimeMillis() < deadline) {
                    got = heldBy(h, 1, ids)
                    if (got == total) break
                    delay(1_000)
                }
                println("LAN-CATCHUP received=$got of $total")
                // The first batch alone proves the wire works; the claim is the rest.
                assertTrue("harness not ready: not even one batch crossed", got >= 200)
                assertEquals("the late joiner stopped at the freshest batch", total, got)
            }
        } finally {
            h.stop()
            authorScope.cancel()
        }
    }
}
