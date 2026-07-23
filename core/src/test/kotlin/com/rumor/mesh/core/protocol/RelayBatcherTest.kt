package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.Clock
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O130(a): a relayed message must never be released before its own randomized
 * deadline, even when a flush sweep fires in between — the exact
 * "arrived-just-before-flush = zero delay" leak the old single-window design
 * had. Uses a fake clock so the deadline comparison is deterministic and
 * independent of real coroutine timing.
 */
class RelayBatcherTest {

    private class FakeClock(@Volatile var t: Long) : Clock {
        override fun now(): Long = t
    }

    private var seq = 0L
    private fun msg(id: String) = RumorMessage(
        id = id,
        senderId = "sender",
        senderPublicKey = "",
        sequenceNumber = seq++,
        sentAtMs = 0L,
        type = MessageType.BROADCAST,
        hopsToLive = 5,
        payload = MessagePayload(ContentType.TEXT, "x"),
        signature = "sig",
    )

    @Test
    fun `message is not released before its deadline even as sweeps fire`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val clock = FakeClock(1000)
        val flushed = CopyOnWriteArrayList<String>()
        // spreadMs = 1 → abs(rand) % 1 == 0, so releaseAt = now + minWindow deterministically.
        val batcher = RelayBatcher(scope, minWindowMs = 20, spreadMs = 1, clock = clock) { b ->
            b.forEach { flushed.add(it.id) }
        }

        batcher.add(msg("m1")) // releaseAt = 1020, clock frozen at 1000
        delay(120)             // ~6 sweeps at 20ms — none may release m1
        assertTrue(flushed.isEmpty(), "must NOT release before the deadline (the O130a leak)")

        clock.t = 1020         // deadline reached
        delay(60)              // next sweep releases it
        assertTrue(flushed.contains("m1"), "released once its deadline passed")

        scope.cancel()
    }

    @Test
    fun `messages past deadline flush together in one batch`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val clock = FakeClock(500)
        val batches = CopyOnWriteArrayList<List<String>>()
        val batcher = RelayBatcher(scope, minWindowMs = 20, spreadMs = 1, clock = clock) { b ->
            batches.add(b.map { it.id })
        }

        batcher.add(msg("a"))
        batcher.add(msg("b"))
        batcher.add(msg("c")) // all releaseAt = 520
        clock.t = 600         // all past deadline
        delay(60)

        val all = batches.flatten()
        assertEquals(setOf("a", "b", "c"), all.toSet(), "all three released")
        assertTrue(batches.any { it.size >= 2 }, "released as a batch, not one-per-message")

        scope.cancel()
    }
}
