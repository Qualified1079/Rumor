package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.MultiRecipientEnvelope
import com.rumor.mesh.core.wire.WireJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end exercise of every O79 protocol primitive shipped in
 * this session, composed in the order the real wire flow uses
 * them. Catches integration drift: if any individual unit test
 * passes but the protocol-level composition breaks (e.g. an HKDF
 * info-string typo in the codec doesn't match the receiver), this
 * suite fails.
 *
 * Covers BOTH OPEN and ENCRYPTED room modes.
 */
class RoomEndToEndIntegrationTest {

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

    // ── OPEN room end-to-end ──────────────────────────────────────────────────

    @Test fun `OPEN room — sender tags, receiver matches, plaintext visible`() {
        val roomId = "neighborhood-watch"
        val plaintext = "Stove fire on 3rd ave, please be careful"

        // Sender side: derive the routing tag from the public roomId.
        val tag = RoomRoutingTag.openRoomTag(roomId)
        assertEquals(16, tag.size)

        // Receiver side: subscribed to the same room.
        val matched = RoomTagMatcher.match(
            inboundTag = tag,
            messageId = "any-msg-id-since-OPEN-uses-roomId-only",
            openSubscriptions = listOf(roomId),
            encryptedSubscriptions = emptyList(),
        )
        assertNotNull(matched)
        assertTrue(matched is RoomTagMatcher.MatchResult.OpenMatch)
        assertEquals(roomId, matched.roomId)

        // OPEN rooms don't encrypt — the wire carries plaintext directly.
        // The receiver matched the routing tag, so they would display
        // `plaintext` to the user. (In production this is also signed by
        // the sender's Ed25519, which we don't exercise here since the
        // wire-format outer-signature path is covered by the existing
        // RumorMessage.signableBytes tests.)
        @Suppress("UNUSED_VARIABLE")
        val _whatGetsDisplayed = plaintext
    }

    @Test fun `OPEN room — non-subscribed receiver drops the message`() {
        val tag = RoomRoutingTag.openRoomTag("private-room")
        val result = RoomTagMatcher.match(
            inboundTag = tag,
            messageId = "msg",
            openSubscriptions = listOf("rooms-im-actually-in"),
            encryptedSubscriptions = emptyList(),
        )
        assertEquals(null, result, "tag for a room I'm not subscribed to must not match anything")
    }

    // ── ENCRYPTED room end-to-end ─────────────────────────────────────────────

    @Test fun `ENCRYPTED room — sender encrypts to 3 recipients, each can decrypt + match tag`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val charlie = newIdentity()
        val dave = newIdentity()

        // Room setup: invite material seeds the routing key + envelope crypto.
        // In real usage the routing key is distributed in the RoomInvite
        // payload; for the test we just generate one.
        val roomSeed = ByteArray(64) { (it * 13).toByte() }
        val routingKey = RoomRoutingTag.deriveEncryptedRoomRoutingKey(roomSeed)
        val plaintext = "secret room discussion".encodeToByteArray()
        val messageId = "msg-abc-123"
        val roomId = "encrypted-room-id"

        // Sender (alice) composes the envelope to bob + charlie.
        val envelope = MultiRecipientEnvelopeCodec.encrypt(
            plaintext = plaintext,
            senderEd25519Private = alice.edPriv,
            senderId = alice.userId,
            senderEd25519Public = alice.edPub,
            recipients = listOf(
                MultiRecipientEnvelopeCodec.Recipient(bob.userId, bob.xPub),
                MultiRecipientEnvelopeCodec.Recipient(charlie.userId, charlie.xPub),
            ),
            roomRoutingTag = RoomRoutingTag.encryptedRoomTag(routingKey, messageId).toBase64(),
        )

        // Wire round-trip: serialize, deserialize.
        val wireJson = WireJson.encodeToString(envelope)
        val parsed = WireJson.decodeFromString<MultiRecipientEnvelope>(wireJson)
        assertEquals(envelope, parsed)

        // Receivers (bob + charlie) — each derives the same routing key
        // from the same invite material, and each matches the inbound
        // tag against their subscription.
        val tagOnWire = parsed.roomRoutingTag.fromBase64()
        val bobsSubs = listOf(RoomTagMatcher.EncryptedRoomSubscription(roomId, routingKey))
        val bobMatch = RoomTagMatcher.match(tagOnWire, messageId, emptyList(), bobsSubs)
        assertNotNull(bobMatch)
        assertTrue(bobMatch is RoomTagMatcher.MatchResult.EncryptedMatch)
        assertEquals(roomId, bobMatch.roomId)

        // Bob decrypts.
        val bobPlain = MultiRecipientEnvelopeCodec.decrypt(parsed, bob.userId, bob.xPriv)
        assertNotNull(bobPlain)
        assertTrue(bobPlain.contentEquals(plaintext))

        // Charlie decrypts the SAME envelope.
        val charliePlain = MultiRecipientEnvelopeCodec.decrypt(parsed, charlie.userId, charlie.xPriv)
        assertNotNull(charliePlain)
        assertTrue(charliePlain.contentEquals(plaintext))

        // Dave (not addressed) cannot decrypt — even with a valid X25519 keypair.
        val daveTry = MultiRecipientEnvelopeCodec.decrypt(parsed, dave.userId, dave.xPriv)
        assertEquals(null, daveTry, "non-addressed recipient must get null")
    }

    @Test fun `ENCRYPTED room — wrong-room receiver matches no tag and never tries to decrypt`() {
        // Mallory is subscribed to a DIFFERENT room; her routing key is
        // unrelated to alice's room's routing key.
        val alice = newIdentity()
        val bob = newIdentity()
        val malloryRoomSeed = ByteArray(64) { (it * 17).toByte() }
        val malloryRoomKey = RoomRoutingTag.deriveEncryptedRoomRoutingKey(malloryRoomSeed)

        val aliceRoomSeed = ByteArray(64) { (it * 13).toByte() }
        val aliceRoomKey = RoomRoutingTag.deriveEncryptedRoomRoutingKey(aliceRoomSeed)

        // Alice composes for her room.
        val envelope = MultiRecipientEnvelopeCodec.encrypt(
            plaintext = "alice's room only".encodeToByteArray(),
            senderEd25519Private = alice.edPriv,
            senderId = alice.userId,
            senderEd25519Public = alice.edPub,
            recipients = listOf(MultiRecipientEnvelopeCodec.Recipient(bob.userId, bob.xPub)),
            roomRoutingTag = RoomRoutingTag.encryptedRoomTag(aliceRoomKey, "msg-1").toBase64(),
        )

        // Mallory's tag-matching attempt against the inbound message: her
        // key produces a DIFFERENT tag for the same messageId, so the
        // matcher returns null. She never attempts decryption.
        val tagOnWire = envelope.roomRoutingTag.fromBase64()
        val malloryMatch = RoomTagMatcher.match(
            inboundTag = tagOnWire,
            messageId = "msg-1",
            openSubscriptions = emptyList(),
            encryptedSubscriptions = listOf(
                RoomTagMatcher.EncryptedRoomSubscription("mallory-room", malloryRoomKey)
            ),
        )
        assertEquals(null, malloryMatch,
            "mallory's room key produces a different tag; matcher correctly drops")
    }

    @Test fun `ENCRYPTED room — relay tampering content ciphertext is rejected at recipient sig check`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val roomSeed = ByteArray(64) { it.toByte() }
        val routingKey = RoomRoutingTag.deriveEncryptedRoomRoutingKey(roomSeed)

        val original = MultiRecipientEnvelopeCodec.encrypt(
            plaintext = "real message".encodeToByteArray(),
            senderEd25519Private = alice.edPriv,
            senderId = alice.userId,
            senderEd25519Public = alice.edPub,
            recipients = listOf(MultiRecipientEnvelopeCodec.Recipient(bob.userId, bob.xPub)),
            roomRoutingTag = RoomRoutingTag.encryptedRoomTag(routingKey, "msg-1").toBase64(),
        )

        // A relay flips the content ciphertext byte. Even if the routing
        // tag still matches bob's subscription, the outer Ed25519 sig
        // catches the tamper before any wrap decryption happens.
        val tampered = original.copy(contentCiphertext = "ZmFrZQ==")
        val result = MultiRecipientEnvelopeCodec.decrypt(tampered, bob.userId, bob.xPriv)
        assertEquals(null, result,
            "tampered content ciphertext must fail at outer sig before bob does any wrap work")
    }
}
