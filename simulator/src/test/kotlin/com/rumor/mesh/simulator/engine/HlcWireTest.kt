package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.Clock
import com.rumor.mesh.core.wire.hlcTimestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O95 wire adoption — the engine stamps `_ext.hlc` on compose, folds it on
 * verified receive, and a slow-clock replier's next message still sorts after
 * its cause (the 64-day-phone field case, now over the real engine + wire).
 */
class HlcWireTest {

    @Test
    fun `slow-clock replier composes above the received stamp`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val saneNow = 1_700_000_000_000
        val a = SimNode(0, scope, clock = Clock { saneNow })
        // B's wall clock is 64 days behind A's.
        val b = SimNode(1, scope, clock = Clock { saneNow - 64L * 24 * 3600 * 1000 })

        val cause = a.gossipEngine.composeBroadcast("hello")
        assertNotNull("compose must stamp _ext.hlc", cause!!.hlcTimestamp)
        a.flushSchedulerToRepo()

        SimTransport(a, b).exchange(kotlin.random.Random(1))
        awaitUntil { b.gossipEngine.hlc.current() >= cause.hlcTimestamp!! }
        assertTrue(
            "receive must fold the sender's stamp into B's clock",
            b.gossipEngine.hlc.current() >= cause.hlcTimestamp!!,
        )

        val reply = b.gossipEngine.composeBroadcast("reply")!!
        assertTrue(
            "B's reply (${reply.hlcTimestamp}) must sort after its cause (${cause.hlcTimestamp}) despite B's 64-day-slow wall clock",
            reply.hlcTimestamp!! > cause.hlcTimestamp!!,
        )
    }
}
