package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiRecipientEnvelopeCodecTest {

    private data class Identity(
        val userId: String,
        val edPriv: ByteArray, val edPub: ByteArray,
        val xPriv: ByteArray, val xPub: ByteArray,
    )

    private fun newIdentity(): Identity {
        val (edPriv, edPub) = CryptoManager.generateEd25519KeyPair()
        val x = CryptoManager.generateX25519KeyPair()
        return Identity(
            userId = CryptoManager.publicKeyToUserId(edPub),
            edPriv = edPriv, edPub = edPub,
            xPriv = x.privateKeyBytes, xPub = x.publicKeyBytes,
        )
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test fun `single-recipient round-trip`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val plaintext = "Hello, Bob".encodeToByteArray()

        val envelope = MultiRecipientEnvelopeCodec.encrypt(
            plaintext = plaintext,
            senderEd25519Private = alice.edPriv,
            senderId = alice.userId,
            senderEd25519Public = alice.edPub,
            recipients = listOf(MultiRecipientEnvelopeCodec.Recipient(bob.userId, bob.xPub)),
            roomRoutingTag = "rt",
        )

        val decrypted = MultiRecipientEnvelopeCodec.decrypt(envelope, bob.userId, bob.xPriv)
        assertNotNull(decrypted)
        assertTrue(decrypted.contentEquals(plaintext))
    }

    @Test fun `multi-recipient round-trip — every member can decrypt`() {
        val alice = newIdentity()
        val recipients = (1..5).map { newIdentity() }
        val plaintext = "broadcast to 5".encodeToByteArray()

        val envelope = MultiRecipientEnvelopeCodec.encrypt(
            plaintext = plaintext,
            senderEd25519Private = alice.edPriv,
            senderId = alice.userId,
            senderEd25519Public = alice.edPub,
            recipients = recipients.map { MultiRecipientEnvelopeCodec.Recipient(it.userId, it.xPub) },
            roomRoutingTag = "rt",
        )

        for (r in recipients) {
            val decrypted = MultiRecipientEnvelopeCodec.decrypt(envelope, r.userId, r.xPriv)
            assertNotNull(decrypted, "recipient ${r.userId.take(8)}… should decrypt")
            assertTrue(decrypted.contentEquals(plaintext))
        }
    }

    @Test fun `envelope has one key wrap per recipient (sorted by userId on the wire when signed)`() {
        val alice = newIdentity()
        val recipients = (1..3).map { newIdentity() }
        val envelope = MultiRecipientEnvelopeCodec.encrypt(
            "p".encodeToByteArray(),
            alice.edPriv, alice.userId, alice.edPub,
            recipients.map { MultiRecipientEnvelopeCodec.Recipient(it.userId, it.xPub) },
            "rt",
        )
        assertEquals(3, envelope.keyWraps.size)
        // Each recipient's userId appears exactly once.
        val ids = envelope.keyWraps.map { it.recipientId }.toSet()
        assertEquals(recipients.map { it.userId }.toSet(), ids)
    }

    // ── Negative cases ────────────────────────────────────────────────────────

    @Test fun `non-recipient cannot decrypt`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val charlie = newIdentity()
        val envelope = MultiRecipientEnvelopeCodec.encrypt(
            "to bob only".encodeToByteArray(),
            alice.edPriv, alice.userId, alice.edPub,
            listOf(MultiRecipientEnvelopeCodec.Recipient(bob.userId, bob.xPub)),
            "rt",
        )
        // Charlie has the wrong userId AND no key wrap addressed to him.
        val result = MultiRecipientEnvelopeCodec.decrypt(envelope, charlie.userId, charlie.xPriv)
        assertNull(result, "charlie has no key wrap; must return null")
    }

    @Test fun `correct userId but wrong static private cannot decrypt (key-wrap AEAD fails)`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val mallory = newIdentity()  // a different X25519 keypair
        val envelope = MultiRecipientEnvelopeCodec.encrypt(
            "p".encodeToByteArray(),
            alice.edPriv, alice.userId, alice.edPub,
            listOf(MultiRecipientEnvelopeCodec.Recipient(bob.userId, bob.xPub)),
            "rt",
        )
        // Use bob's userId but mallory's static private — wrap AEAD must fail.
        val result = MultiRecipientEnvelopeCodec.decrypt(envelope, bob.userId, mallory.xPriv)
        assertNull(result, "wrong private key under correct userId must fail AEAD")
    }

    @Test fun `tampered signature is rejected before any wrap decryption`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val envelope = MultiRecipientEnvelopeCodec.encrypt(
            "p".encodeToByteArray(),
            alice.edPriv, alice.userId, alice.edPub,
            listOf(MultiRecipientEnvelopeCodec.Recipient(bob.userId, bob.xPub)),
            "rt",
        )
        // Flip the signature.
        val tampered = envelope.copy(signature = "AA==")
        val result = MultiRecipientEnvelopeCodec.decrypt(tampered, bob.userId, bob.xPriv)
        assertNull(result, "bad sig must fail before wrap decryption")
    }

    @Test fun `relay appending a forged key wrap fails the signature check`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val attacker = newIdentity()
        val envelope = MultiRecipientEnvelopeCodec.encrypt(
            "p".encodeToByteArray(),
            alice.edPriv, alice.userId, alice.edPub,
            listOf(MultiRecipientEnvelopeCodec.Recipient(bob.userId, bob.xPub)),
            "rt",
        )
        // Attacker tries to inject themselves by appending a slot to the
        // keyWraps list — even with a plausible-looking wrap, the sig over
        // the canonical bytes (which now include the attacker's slot)
        // mismatches alice's actual signature.
        val tampered = envelope.copy(keyWraps = envelope.keyWraps + com.rumor.mesh.core.model.KeyWrap(
            recipientId = attacker.userId,
            wrappedKey = "AAAA",
            wrapIv = "AAAA",
        ))
        assertNull(
            MultiRecipientEnvelopeCodec.decrypt(tampered, bob.userId, bob.xPriv),
            "extending the recipient list breaks the signature; bob's decrypt must fail"
        )
        // Attacker themselves trying to use their forged slot also fails
        // (signature check happens before any wrap work).
        assertNull(
            MultiRecipientEnvelopeCodec.decrypt(tampered, attacker.userId, attacker.xPriv),
            "attacker's forged slot is meaningless: sig check fails before they touch it"
        )
    }

    @Test fun `relay tampering content ciphertext fails signature check`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val envelope = MultiRecipientEnvelopeCodec.encrypt(
            "original".encodeToByteArray(),
            alice.edPriv, alice.userId, alice.edPub,
            listOf(MultiRecipientEnvelopeCodec.Recipient(bob.userId, bob.xPub)),
            "rt",
        )
        val tampered = envelope.copy(contentCiphertext = "TamperedAA==")
        assertNull(
            MultiRecipientEnvelopeCodec.decrypt(tampered, bob.userId, bob.xPriv),
            "tampered content ciphertext must fail the outer sig check"
        )
    }

    @Test fun `forward secrecy — same plaintext + same recipients produces different ciphertext on each call`() {
        // Each envelope uses fresh ephemeral + fresh content key, so the
        // ciphertext bytes must differ even when inputs are identical. This
        // proves the per-envelope randomness is real (and that we're not
        // somehow caching ephemerals across calls).
        val alice = newIdentity()
        val bob = newIdentity()
        val plaintext = "same message".encodeToByteArray()
        val recips = listOf(MultiRecipientEnvelopeCodec.Recipient(bob.userId, bob.xPub))

        val e1 = MultiRecipientEnvelopeCodec.encrypt(plaintext, alice.edPriv, alice.userId, alice.edPub, recips, "rt")
        val e2 = MultiRecipientEnvelopeCodec.encrypt(plaintext, alice.edPriv, alice.userId, alice.edPub, recips, "rt")

        kotlin.test.assertNotEquals(e1.contentCiphertext, e2.contentCiphertext,
            "fresh content key per envelope means same plaintext yields different ct")
        kotlin.test.assertNotEquals(e1.senderEphemeralPublic, e2.senderEphemeralPublic,
            "fresh ephemeral keypair per envelope")
        // Both decrypt to the same plaintext (proves both round-trip independently).
        assertTrue(MultiRecipientEnvelopeCodec.decrypt(e1, bob.userId, bob.xPriv)!!.contentEquals(plaintext))
        assertTrue(MultiRecipientEnvelopeCodec.decrypt(e2, bob.userId, bob.xPriv)!!.contentEquals(plaintext))
    }
}
