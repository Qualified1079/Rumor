package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageDeletePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.messageDeleteSignableBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class MessageDeleteVerifierTest {

    private data class Keypair(val priv: ByteArray, val pub: ByteArray, val userId: String)

    private fun keypair(): Keypair {
        val (priv, pub) = CryptoManager.generateEd25519KeyPair()
        return Keypair(priv, pub, CryptoManager.publicKeyToUserId(pub))
    }

    private fun targetDm(senderId: String, recipientId: String, id: String = "m1"): RumorMessage =
        RumorMessage(
            id = id,
            senderId = senderId,
            senderPublicKey = "pk",
            sequenceNumber = 1L,
            sentAtMs = 0L,
            type = MessageType.DIRECT,
            hopsToLive = 5,
            encryptedPayload = "ct",
            recipientId = recipientId,
            signature = "sig",
        )

    private fun signedDelete(issuer: Keypair, messageId: String): MessageDeletePayload {
        val issuerPubB64 = issuer.pub.toBase64()
        val sig = CryptoManager.sign(messageDeleteSignableBytes(messageId, issuerPubB64), issuer.priv)
        return MessageDeletePayload(
            messageId = messageId,
            issuerPublicKey = issuerPubB64,
            signature = sig.toBase64(),
        )
    }

    @Test fun `sender of the DM may delete`() = runTest {
        val alice = keypair()
        val bob = keypair()
        val dm = targetDm(senderId = alice.userId, recipientId = bob.userId)
        val payload = signedDelete(alice, dm.id)
        val r = MessageDeleteVerifier.verify(payload) { id -> if (id == dm.id) dm else null }
        assertTrue(r is MessageDeleteVerifier.Result.Authorized)
        assertEquals(dm.id, (r as MessageDeleteVerifier.Result.Authorized).target?.id)
    }

    @Test fun `recipient of the DM may delete`() = runTest {
        val alice = keypair()
        val bob = keypair()
        val dm = targetDm(senderId = alice.userId, recipientId = bob.userId)
        val payload = signedDelete(bob, dm.id)
        val r = MessageDeleteVerifier.verify(payload) { dm }
        assertTrue(r is MessageDeleteVerifier.Result.Authorized)
    }

    @Test fun `third party is rejected`() = runTest {
        val alice = keypair()
        val bob = keypair()
        val charlie = keypair()
        val dm = targetDm(senderId = alice.userId, recipientId = bob.userId)
        val payload = signedDelete(charlie, dm.id)
        val r = MessageDeleteVerifier.verify(payload) { dm }
        assertTrue(r is MessageDeleteVerifier.Result.Rejected, "Charlie is neither sender nor recipient")
    }

    @Test fun `tampered messageId fails signature verify`() = runTest {
        val alice = keypair()
        val bob = keypair()
        val dm = targetDm(senderId = alice.userId, recipientId = bob.userId, id = "m1")
        val payload = signedDelete(alice, "m1").copy(messageId = "m2")
        val r = MessageDeleteVerifier.verify(payload) { id -> if (id == "m2") dm.copy(id = "m2") else null }
        assertTrue(r is MessageDeleteVerifier.Result.Rejected)
    }

    @Test fun `bad signature is rejected`() = runTest {
        val alice = keypair()
        val bob = keypair()
        val dm = targetDm(senderId = alice.userId, recipientId = bob.userId)
        val payload = signedDelete(alice, dm.id).copy(signature = "not-a-valid-sig!!!")
        val r = MessageDeleteVerifier.verify(payload) { dm }
        assertTrue(r is MessageDeleteVerifier.Result.Rejected)
    }

    @Test fun `target missing is authorized but no-op`() = runTest {
        val alice = keypair()
        val payload = signedDelete(alice, "gone")
        val r = MessageDeleteVerifier.verify(payload) { null }
        assertTrue(r is MessageDeleteVerifier.Result.Authorized,
            "Sig verifies; relay may have already purged or never seen — safe no-op")
        assertNull((r as MessageDeleteVerifier.Result.Authorized).target)
    }

    @Test fun `bad base64 pubkey is rejected`() = runTest {
        val payload = MessageDeletePayload(
            messageId = "m1",
            issuerPublicKey = "%%% not base64 %%%",
            signature = "AA==",
        )
        val r = MessageDeleteVerifier.verify(payload) { null }
        assertTrue(r is MessageDeleteVerifier.Result.Rejected)
    }
}
