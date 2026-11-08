package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.scheduling.MessageScheduler
import com.rumor.mesh.core.data.memory.InMemoryContactRepository
import com.rumor.mesh.core.data.memory.InMemoryScheduledMessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O22 native scheduling. Confirms one-shot and recurring schedules
 * advance/expire correctly through MessageScheduler.fireDue, including
 * the burst-suppression logic that skips past-due intervals on a
 * long-paused device.
 */
class MessageSchedulerTest {

    @Test
    fun `one-shot broadcast fires once and is deleted`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val node = SimNode(0, scope)
        val repo = InMemoryScheduledMessageRepository()
        val scheduler = MessageScheduler(
            scheduledRepo = repo,
            gossipEngine = node.gossipEngine,
            contactRepo = InMemoryContactRepository(),
            scope = scope,
        )
        val id = scheduler.scheduleBroadcast("hello", fireAtMs = 1000L)
        assertNotNull(repo.getById(id))

        // Not yet due.
        scheduler.fireDue(nowMs = 500L)
        assertNotNull("not due yet — schedule must persist", repo.getById(id))

        // Now due.
        scheduler.fireDue(nowMs = 1500L)
        assertNull("one-shot schedule must be deleted after firing", repo.getById(id))
        // The gossip engine should hold the resulting broadcast.
        node.flushSchedulerToRepo()
        assertTrue(
            "engine must have stored the fired broadcast",
            node.knownMessages().any { it.payload?.content == "hello" },
        )
    }

    @Test
    fun `recurring with finite count fires N times then is deleted`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val node = SimNode(0, scope)
        val repo = InMemoryScheduledMessageRepository()
        val scheduler = MessageScheduler(
            scheduledRepo = repo,
            gossipEngine = node.gossipEngine,
            contactRepo = InMemoryContactRepository(),
            scope = scope,
        )
        val id = scheduler.scheduleRecurringBroadcast(
            text = "tick",
            firstFireAtMs = 1000L,
            intervalMs = 100L,
            count = 3,
        )

        // First fire.
        scheduler.fireDue(1000L)
        assertNotNull("not exhausted", repo.getById(id))
        assertEquals(2, repo.getById(id)!!.remainingFires)
        assertEquals(1100L, repo.getById(id)!!.fireAtMs)

        // Second fire.
        scheduler.fireDue(1100L)
        assertEquals(1, repo.getById(id)!!.remainingFires)

        // Third fire — exhausts the schedule.
        scheduler.fireDue(1200L)
        assertNull("recurrence exhausted at remainingFires=0", repo.getById(id))
    }

    @Test
    fun `unlimited recurrence skips past intervals to avoid burst on resume`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val node = SimNode(0, scope)
        val repo = InMemoryScheduledMessageRepository()
        val scheduler = MessageScheduler(
            scheduledRepo = repo,
            gossipEngine = node.gossipEngine,
            contactRepo = InMemoryContactRepository(),
            scope = scope,
        )
        val id = scheduler.scheduleRecurringBroadcast(
            text = "heartbeat",
            firstFireAtMs = 1000L,
            intervalMs = 100L,
            count = -1,
        )

        // Device "wakes up" with simTime jumped forward to 5000L — i.e. 40
        // missed intervals. Should fire ONCE and skip ahead, not fire 40 times.
        scheduler.fireDue(nowMs = 5000L)
        val s = repo.getById(id)
        assertNotNull(s)
        // The next fireAtMs should be in the future relative to 5000L,
        // not at 1100L (which would mean we'd fire again immediately).
        assertTrue("next fire must be in the future after resume", s!!.fireAtMs > 5000L)
        assertEquals(-1, s.remainingFires)
    }

    @Test
    fun `cancel removes the schedule`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val node = SimNode(0, scope)
        val repo = InMemoryScheduledMessageRepository()
        val scheduler = MessageScheduler(
            scheduledRepo = repo,
            gossipEngine = node.gossipEngine,
            contactRepo = InMemoryContactRepository(),
            scope = scope,
        )
        val id = scheduler.scheduleBroadcast("never-fires", fireAtMs = 10_000L)
        assertNotNull(repo.getById(id))
        scheduler.cancel(id)
        assertNull(repo.getById(id))
    }

    /**
     * O240 — a recurring row whose count is 0 (the model default) or any negative
     * other than -1 must fire once and end, not wrap to "unlimited". Inserted into
     * the repo directly, as a stored row would be, so the compose-side check below
     * can't be what makes it pass.
     */
    @Test
    fun `a recurring row with no remaining fires ends instead of recurring forever`() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val node = SimNode(0, scope)
            val repo = InMemoryScheduledMessageRepository()
            val scheduler = MessageScheduler(
                scheduledRepo = repo,
                gossipEngine = node.gossipEngine,
                contactRepo = InMemoryContactRepository(),
                scope = scope,
            )
            for ((id, remaining) in listOf("zero" to 0, "neg" to -5)) {
                repo.upsert(com.rumor.mesh.core.model.ScheduledMessage(
                    id = id,
                    type = com.rumor.mesh.core.model.MessageType.BROADCAST,
                    contentText = "once-$id",
                    fireAtMs = 1000L,
                    intervalMs = 100L,
                    remainingFires = remaining,
                ))
            }
            scheduler.fireDue(1000L)
            assertNull("remainingFires=0 must end after firing, not become unlimited", repo.getById("zero"))
            assertNull("a negative count other than -1 must end too", repo.getById("neg"))
            node.flushSchedulerToRepo()
            assertTrue(
                "it still fires the once it was due",
                node.knownMessages().any { it.payload?.content == "once-zero" },
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scheduling a recurring broadcast with count 0 is refused`() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val scheduler = MessageScheduler(
                scheduledRepo = InMemoryScheduledMessageRepository(),
                gossipEngine = SimNode(0, scope).gossipEngine,
                contactRepo = InMemoryContactRepository(),
                scope = scope,
            )
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                runBlocking { scheduler.scheduleRecurringBroadcast("x", 1000L, 100L, count = 0) }
            }
        } finally {
            scope.cancel()
        }
    }
}
