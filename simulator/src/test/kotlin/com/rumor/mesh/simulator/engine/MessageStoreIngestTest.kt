package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.simulator.data.InMemoryContactRepository
import com.rumor.mesh.simulator.data.InMemoryMessageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §2 regression — ingest ordering must verify before it commits.
 *
 * The censorship primitive: recording an id into the dedup filter BEFORE
 * signature verification let a bad-signature forgery carrying a target's real
 * id permanently blackhole the genuine message. The fix reorders ingest to
 * check-only dedup → verify sig → verify identity → rate-limit → commit.
 */
class MessageStoreIngestTest {

    private fun store() = MessageStore(
        InMemoryMessageRepository(),
        InMemoryContactRepository(),
        DuplicateFilter(),
    )

    private val kp = CryptoManager.generateEd25519KeyPair()
    private val userId = CryptoManager.publicKeyToUserId(kp.publicKeyBytes)

    /** A validly-signed broadcast from [senderId] (defaults to the real userId). */
    private fun MessageStore.signed(id: String, senderId: String = userId): RumorMessage {
        val unsigned = RumorMessage(
            id = id,
            senderId = senderId,
            senderPublicKey = kp.publicKeyBytes.toBase64(),
            sequenceNumber = 1,
            sentAtMs = 1_000L,
            type = MessageType.BROADCAST,
            hopsToLive = 7,
            payload = MessagePayload(ContentType.TEXT, "hello"),
            signature = "",
        )
        return unsigned.copy(
            signature = CryptoManager.sign(signableBytes(unsigned), kp.privateKeyBytes).toBase64(),
        )
    }

    @Test
    fun `a bad-signature forgery does not blackhole the genuine message with the same id`() = runBlocking {
        val store = store()
        val id = "a".repeat(32)
        val genuine = store.signed(id)
        // Attacker copies the target's real id but mangles the signature.
        val forged = genuine.copy(signature = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")

        assertFalse("forged message must be rejected", store.ingest(forged))
        assertTrue("genuine message must still ingest — not dropped as a duplicate", store.ingest(genuine))
        // And a true replay of the genuine message is now a duplicate.
        assertFalse("second copy of the genuine message is a duplicate", store.ingest(genuine))
    }

    @Test
    fun `a message whose senderId does not hash from its pubkey is rejected`() = runBlocking {
        val store = store()
        // Valid Ed25519 signature (signed with our key) but senderId claims a
        // different identity than SHA-256(pubkey) — an impersonation attempt and
        // the per-sender-rate-limit bypass. Must be dropped at the identity check.
        val impersonation = store.signed("b".repeat(32), senderId = "deadbeef".repeat(8))
        assertFalse(store.ingest(impersonation))
    }

    @Test
    fun `a well-formed signed message ingests once`() = runBlocking {
        val store = store()
        assertTrue(store.ingest(store.signed("c".repeat(32))))
    }
}
