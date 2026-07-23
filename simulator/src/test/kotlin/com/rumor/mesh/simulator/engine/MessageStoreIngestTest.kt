package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.data.memory.InMemoryContactRepository
import com.rumor.mesh.core.data.memory.InMemoryMessageRepository
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

    /**
     * O144 belt-and-suspenders: even with a VALID v2 signature over its own
     * bytes, a broadcast that carries an encryptedPayload is anomalous (no
     * honest composeBroadcast produces one) and the shape guard drops it before
     * store/relay. This is independent of the v2 framing that already makes the
     * splice's signature fail to verify (see the dedicated v2 test below) — the
     * shape check catches any future path that might produce the odd shape.
     */
    @Test
    fun `a broadcast carrying an encryptedPayload is dropped even when validly signed`() = runBlocking {
        val store = store()
        val odd = RumorMessage(
            id = "d".repeat(32),
            senderId = userId,
            senderPublicKey = kp.publicKeyBytes.toBase64(),
            sequenceNumber = 1,
            sentAtMs = 1_000L,
            type = MessageType.BROADCAST,
            hopsToLive = 7,
            payload = MessagePayload(ContentType.TEXT, "hel"),
            encryptedPayload = "lo",
            signature = "",
        )
        // Sign it properly under v2 so it passes the signature + identity checks.
        val signed = odd.copy(
            signature = CryptoManager.sign(store.signableBytes(odd), kp.privateKeyBytes).toBase64(),
        )
        assertFalse("broadcast carrying encryptedPayload must be dropped (O144 shape guard)", store.ingest(signed))
    }

    @Test
    fun `O144 v2 framing makes the broadcast splice structurally impossible - signature no longer verifies`() = runBlocking {
        val store = store()
        // content="hello" honest; splice content="hel", encryptedPayload="lo".
        // Under v1 (no delimiters) the transcript was byte-identical, so the sig
        // verified. Under v2 length-prefixing, framed("hello") = "5:hello" but
        // framed("hel")+framed("lo") = "3:hel2:lo" — different bytes, so the
        // honest signature must NOT verify over the spliced message.
        val honest = RumorMessage(
            id = "f".repeat(32),
            senderId = userId,
            senderPublicKey = kp.publicKeyBytes.toBase64(),
            sequenceNumber = 1,
            sentAtMs = 1_000L,
            type = MessageType.BROADCAST,
            hopsToLive = 7,
            payload = MessagePayload(ContentType.TEXT, "hello"),
            signature = "",
        )
        val sig = CryptoManager.sign(store.signableBytes(honest), kp.privateKeyBytes).toBase64()
        val spliced = honest.copy(
            payload = MessagePayload(ContentType.TEXT, "hel"),
            encryptedPayload = "lo",
        )
        assertFalse(
            "v2 framing must make the splice transcript differ — sig cannot verify",
            CryptoManager.verify(store.signableBytes(spliced), sig.fromBase64(), kp.publicKeyBytes),
        )
        // And the honest broadcast round-trips under v2.
        assertTrue("honest broadcast verifies + ingests under v2", store.ingest(honest.copy(signature = sig)))
    }

    @Test
    fun `a message with a malformed recipientId is dropped`() = runBlocking {
        val store = store()
        val honest = RumorMessage(
            id = "e".repeat(32),
            senderId = userId,
            senderPublicKey = kp.publicKeyBytes.toBase64(),
            sequenceNumber = 1,
            sentAtMs = 1_000L,
            type = MessageType.DIRECT,
            hopsToLive = 15,
            encryptedPayload = "ct",
            recipientId = "not-a-valid-64-hex-userid",
            signature = "",
        )
        val signed = honest.copy(
            signature = CryptoManager.sign(store.signableBytes(honest), kp.privateKeyBytes).toBase64(),
        )
        assertFalse("malformed recipientId must be dropped (splice guard)", store.ingest(signed))
    }
}
