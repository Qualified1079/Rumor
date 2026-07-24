package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.Clock
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.data.memory.InMemoryContactRepository
import com.rumor.mesh.core.data.memory.InMemoryMessageRepository
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.MessageStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O16 per-sender ingest token bucket, plus the persist=false / ingestOwn paths.
 * These are the DoS-control and local-echo behaviors of MessageStore that the
 * §2-ordering suite (MessageStoreIngestTest) doesn't touch. Time is injected so
 * the window boundaries are exact.
 *
 * INGEST_BUDGET_PER_SEC / INGEST_WINDOW_MS are private to MessageStore; this
 * suite pins their observable effect (100 messages/sec/sender, 1s window).
 */
class MessageStoreRateLimitTest {

    private class FakeClock(var t: Long) : Clock {
        override fun now(): Long = t
    }

    private val BUDGET = 100

    private fun store(clock: Clock) = MessageStore(
        InMemoryMessageRepository(),
        InMemoryContactRepository(),
        DuplicateFilter(),
        clock = clock,
    )

    private fun signed(
        store: MessageStore,
        id: String,
        kp: CryptoManager.Ed25519KeyPair,
    ): RumorMessage {
        val unsigned = RumorMessage(
            id = id,
            senderId = CryptoManager.publicKeyToUserId(kp.publicKeyBytes),
            senderPublicKey = kp.publicKeyBytes.toBase64(),
            sequenceNumber = 1,
            sentAtMs = 1_000L,
            type = MessageType.BROADCAST,
            hopsToLive = 7,
            payload = MessagePayload(ContentType.TEXT, "hello"),
            signature = "",
        )
        return unsigned.copy(
            signature = CryptoManager.sign(store.signableBytes(unsigned), kp.privateKeyBytes).toBase64(),
        )
    }

    @Test
    fun `sender is rate-limited past the per-second budget`() = runBlocking {
        val clock = FakeClock(0L)
        val store = store(clock)
        val kp = CryptoManager.generateEd25519KeyPair()

        // Exactly BUDGET distinct messages within one window all ingest.
        repeat(BUDGET) { i ->
            assertTrue("message $i within budget must ingest", store.ingest(signed(store, "a$i", kp)))
        }
        // The next one, same sender, same window, is rate-limited.
        assertFalse("over-budget message must be dropped", store.ingest(signed(store, "over", kp)))
        assertEquals("the drop increments the rate-limited counter", 1L, store.rateLimitedCount)
    }

    @Test
    fun `a new window refills the sender budget`() = runBlocking {
        val clock = FakeClock(0L)
        val store = store(clock)
        val kp = CryptoManager.generateEd25519KeyPair()

        repeat(BUDGET) { i -> store.ingest(signed(store, "a$i", kp)) }
        assertFalse(store.ingest(signed(store, "over", kp)))

        // Advance past the 1s window — the bucket resets.
        clock.t += 1_000
        assertTrue("a message in the next window ingests again", store.ingest(signed(store, "next", kp)))
    }

    @Test
    fun `rate limiting is per-sender - one spammer does not starve another`() = runBlocking {
        val clock = FakeClock(0L)
        val store = store(clock)
        val spammer = CryptoManager.generateEd25519KeyPair()
        val victim = CryptoManager.generateEd25519KeyPair()

        repeat(BUDGET) { i -> store.ingest(signed(store, "s$i", spammer)) }
        assertFalse("spammer is now over budget", store.ingest(signed(store, "sover", spammer)))
        // A different sender in the same window is unaffected.
        assertTrue("victim's message must ingest despite the spammer", store.ingest(signed(store, "v0", victim)))
    }

    @Test
    fun `a rate-limited message is not blackholed - it ingests in a later window`() = runBlocking {
        // The message id must NOT be burned into the dedup set on a rate-limit
        // drop (that would be the same censorship class as the §2 pre-verify bug).
        val clock = FakeClock(0L)
        val store = store(clock)
        val kp = CryptoManager.generateEd25519KeyPair()

        repeat(BUDGET) { i -> store.ingest(signed(store, "a$i", kp)) }
        val late = signed(store, "late", kp)
        assertFalse("dropped by rate limit in this window", store.ingest(late))

        clock.t += 1_000
        assertTrue("the very same message ingests once the window refills", store.ingest(late))
    }

    @Test
    fun `persist=false records the id as seen but stores nothing`() = runBlocking {
        val store = store(FakeClock(0L))
        val kp = CryptoManager.generateEd25519KeyPair()
        val msg = signed(store, "ephem", kp)

        assertTrue("persist=false still returns new", store.ingest(msg, persist = false))
        assertNull("nothing is stored", store.getById(msg.id))
        assertFalse("but the id is recorded as seen — a re-ingest is a duplicate", store.ingest(msg, persist = false))
    }

    @Test
    fun `ingestOwn stores without verification and dedups`() = runBlocking {
        val store = store(FakeClock(0L))
        val kp = CryptoManager.generateEd25519KeyPair()
        // Deliberately garbage signature — ingestOwn must not verify (we just signed it).
        val own = signed(store, "own", kp).copy(signature = "not-a-real-signature")

        store.ingestOwn(own)
        assertEquals("stored despite the bad signature", own.id, store.getById(own.id)?.id)
        // Second call is a no-op (dedup) — no exception, still one copy.
        store.ingestOwn(own)
        assertEquals(own.id, store.getById(own.id)?.id)
    }
}
