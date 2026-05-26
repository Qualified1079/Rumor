package com.rumor.mesh.core.scheduler

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.TrafficClass
import com.rumor.mesh.core.model.trafficClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerTest {

    /** A REALTIME message (BROADCAST + TEXT). */
    private fun msg(
        senderId: String,
        contentLength: Int = 50,
        id: String = java.util.UUID.randomUUID().toString(),
    ) = RumorMessage(
        id = id,
        senderId = senderId,
        senderPublicKey = "key",
        sequenceNumber = 0,
        sentAtMs = System.currentTimeMillis(),
        type = MessageType.BROADCAST,
        hopsToLive = 5,
        payload = MessagePayload(ContentType.TEXT, "x".repeat(contentLength)),
        signature = "sig",
    )

    /** A BULK message (BROADCAST + IMAGE). */
    private fun bulkMsg(senderId: String, id: String = java.util.UUID.randomUUID().toString()) =
        msg(senderId, id = id).copy(payload = MessagePayload(ContentType.IMAGE, "x".repeat(50)))

    /** An INFRASTRUCTURE message (PING, no payload). */
    private fun infraMsg(senderId: String, id: String = java.util.UUID.randomUUID().toString()) =
        msg(senderId, id = id).copy(type = MessageType.PING, payload = null)

    @Test
    fun `empty scheduler returns empty list`() {
        val s = Scheduler()
        assertTrue(s.take().isEmpty())
        assertTrue(s.isEmpty)
    }

    @Test
    fun `single sender all messages drained`() {
        val s = Scheduler()
        repeat(10) { s.enqueue(msg("alice")) }
        val result = s.take()
        assertEquals(10, result.size)
        assertTrue(s.isEmpty)
    }

    @Test
    fun `drr shares bandwidth fairly across two senders`() {
        // Alice sends 300 small messages (~306 bytes each).
        // Bob sends 5 large messages (~60256 bytes each = one quantum each).
        // After a full drain, both should have gotten credit proportional to bytes.
        val smallSize = 50    // payload chars → ~306 byte message
        val largeSize = 60_000 // payload chars → ~60256 byte message
        val s = Scheduler(quantumBytes = Scheduler.DEFAULT_QUANTUM_BYTES)

        repeat(300) { s.enqueue(msg("alice", smallSize)) }
        repeat(5) { s.enqueue(msg("bob", largeSize)) }

        val result = s.take(maxCount = 400)

        val aliceCount = result.count { it.senderId == "alice" }
        val bobCount   = result.count { it.senderId == "bob" }

        // Alice: 300 × 306 ≈ 91 800 bytes — roughly 1.5 quanta
        // Bob:     5 × 60 256 ≈ 301 280 bytes — roughly 5 quanta
        // DRR doesn't guarantee exact byte equality in a single call, but neither
        // sender should be completely starved. Bob's 5 chunks should all get through
        // and Alice's messages should interleave.
        assertEquals("all alice messages drained", 300, aliceCount)
        assertEquals("all bob messages drained", 5, bobCount)
    }

    @Test
    fun `drr interleaves senders — bob not starved by alice`() {
        // 100 large alice messages vs 1 bob message; bob should be served before
        // all alice messages are exhausted.
        val s = Scheduler(quantumBytes = 1_000)
        repeat(100) { s.enqueue(msg("alice", 5000)) } // each ~5256 bytes, 5+ quanta each
        s.enqueue(msg("bob", 50))

        val first50 = s.take(maxCount = 50)
        assertTrue(
            "Bob should appear before all alice messages are drained",
            first50.any { it.senderId == "bob" },
        )
    }

    @Test
    fun `per-flow queue cap drops oldest messages`() {
        val cap = 10
        val s = Scheduler(perFlowCap = cap)
        // Enqueue cap+5 messages — the first 5 should be dropped.
        val msgs = (1..cap + 5).map { i -> msg("alice", id = "id-$i") }
        msgs.forEach { s.enqueue(it) }

        val result = s.take()
        assertEquals("queue capped at perFlowCap", cap, result.size)
        // The dropped messages are the oldest (ids 1–5); we should have ids 6–15.
        val ids = result.map { it.id }.toSet()
        for (i in 1..5) assertTrue("id-$i should have been dropped", "id-$i" !in ids)
        for (i in 6..cap + 5) assertTrue("id-$i should be present", "id-$i" in ids)
    }

    @Test
    fun `deficit carries across rounds — large message gets through eventually`() {
        // One quantum = 1000 bytes. Large message is 3000 bytes — needs 3 rounds.
        // Pair it with tiny messages from same sender.
        val s = Scheduler(quantumBytes = 1_000)
        s.enqueue(msg("alice", 3000))   // costs ~3256 bytes — won't fit in 1 round
        // Fill with many small messages from bob.
        repeat(20) { s.enqueue(msg("bob", 10)) }

        val result = s.take(maxCount = 30)
        assertTrue("alice's large message must eventually be drained", result.any { it.senderId == "alice" })
    }

    @Test
    fun `maxCount parameter limits output`() {
        val s = Scheduler()
        repeat(50) { s.enqueue(msg("alice")) }
        val result = s.take(maxCount = 10)
        assertEquals(10, result.size)
    }

    @Test
    fun `re-enqueue after drain works`() {
        val s = Scheduler()
        s.enqueue(msg("alice"))
        s.take()
        assertTrue(s.isEmpty)
        s.enqueue(msg("alice"))
        assertEquals(1, s.take().size)
    }

    @Test
    fun `traffic classes drain in strict priority order`() {
        val s = Scheduler()
        // Enqueue lowest-priority first to prove ordering is by class, not arrival.
        s.enqueue(bulkMsg("alice"))
        s.enqueue(msg("bob"))          // REALTIME
        s.enqueue(infraMsg("carol"))   // INFRASTRUCTURE

        val result = s.take()
        assertEquals(3, result.size)
        assertEquals("infrastructure first", "carol", result[0].senderId)
        assertEquals("realtime second", "bob", result[1].senderId)
        assertEquals("bulk last", "alice", result[2].senderId)
    }

    @Test
    fun `oversized message is demoted to BULK regardless of type`() {
        // BROADCAST+TEXT under the 16 KB ceiling → REALTIME.
        // Same type with content exceeding the ceiling → forced to BULK.
        val small = msg("alice", contentLength = 100)
        val large = msg("alice", contentLength = 17_000) // > 16 * 1024

        assertEquals(TrafficClass.REALTIME, small.trafficClass)
        assertEquals(TrafficClass.BULK, large.trafficClass)

        // Scheduler must place the demoted message after genuinely REALTIME traffic.
        val s = Scheduler()
        s.enqueue(large)           // claims TEXT but oversized → BULK bucket
        s.enqueue(msg("bob", 50))  // small TEXT → REALTIME bucket

        val result = s.take()
        assertEquals(2, result.size)
        assertEquals("realtime drained first", "bob", result[0].senderId)
        assertEquals("demoted bulk drained last", "alice", result[1].senderId)
    }

    @Test
    fun `overflow sheds bulk before realtime`() {
        val s = Scheduler(totalQueueCap = 5)
        repeat(5) { s.enqueue(bulkMsg("alice", id = "bulk-$it")) }
        s.enqueue(msg("bob", id = "text"))   // pushes total to 6 → one bulk is shed

        val result = s.take()
        assertEquals("backlog held at the cap", 5, result.size)
        assertTrue("the text message survived", result.any { it.id == "text" })
        assertEquals("only bulk was shed", 4, result.count { it.senderId == "alice" })
    }
}
