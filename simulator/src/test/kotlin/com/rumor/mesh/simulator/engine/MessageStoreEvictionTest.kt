package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.data.MessageRepository
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O121(e) — MessageStore size-cap eviction. Bounded storage is the load-bearing
 * survival property (O23/O55: months without OOM on scarce hardware), yet it had
 * no direct coverage. When the store exceeds `MAX_MESSAGES` (× the mode storage
 * boost) a fresh ingest sheds a fixed batch of the oldest non-always-save
 * messages. This pins the over-cap → evict / under-cap → no-evict branch cheaply,
 * via a repo that reports a synthetic count instead of inserting 50k rows.
 */
class MessageStoreEvictionTest {

    /** Reports a fixed [reportedCount] so the cap branch drives cheaply; records evict batch sizes. */
    private class CapProbeRepo(
        private val delegate: InMemoryMessageRepository,
        private val reportedCount: Int,
    ) : MessageRepository by delegate {
        val evictBatches = mutableListOf<Int>()
        override suspend fun count(): Int = reportedCount
        override suspend fun evictOldest(count: Int) { evictBatches.add(count); delegate.evictOldest(count) }
    }

    private val kp = CryptoManager.generateEd25519KeyPair()
    private val userId = CryptoManager.publicKeyToUserId(kp.publicKeyBytes)

    private fun MessageStore.signedBroadcast(id: String): RumorMessage {
        val unsigned = RumorMessage(
            id = id, senderId = userId, senderPublicKey = kp.publicKeyBytes.toBase64(),
            sequenceNumber = 1, sentAtMs = 1_000L, type = MessageType.BROADCAST, hopsToLive = 7,
            payload = MessagePayload(ContentType.TEXT, "hi"), signature = "",
        )
        return unsigned.copy(
            signature = CryptoManager.sign(signableBytes(unsigned), kp.privateKeyBytes).toBase64(),
        )
    }

    private fun storeWith(repo: MessageRepository) =
        MessageStore(repo, InMemoryContactRepository(), DuplicateFilter())

    @Test
    fun `ingest over the cap sheds one batch of the oldest messages`() = runBlocking {
        val repo = CapProbeRepo(InMemoryMessageRepository(), reportedCount = 50_001) // > MAX_MESSAGES
        val store = storeWith(repo)
        assertTrue(store.ingest(store.signedBroadcast("a".repeat(32))))
        // EVICT_BATCH (500) at default boost=1 — one shed per over-cap ingest.
        assertEquals(listOf(500), repo.evictBatches)
    }

    @Test
    fun `ingest under the cap never evicts`() = runBlocking {
        val repo = CapProbeRepo(InMemoryMessageRepository(), reportedCount = 10)
        val store = storeWith(repo)
        assertTrue(store.ingest(store.signedBroadcast("b".repeat(32))))
        assertTrue(repo.evictBatches.isEmpty())
    }
}
