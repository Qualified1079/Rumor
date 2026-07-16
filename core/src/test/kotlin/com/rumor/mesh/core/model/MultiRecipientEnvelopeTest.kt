package com.rumor.mesh.core.model

import com.rumor.mesh.core.wire.WireJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiRecipientEnvelopeTest {

    private fun wrap(recipient: String) = KeyWrap(
        recipientId = recipient,
        wrappedKey = "wk-$recipient",
        wrapIv = "iv-$recipient",
    )

    private fun envelope(wraps: List<KeyWrap>) = MultiRecipientEnvelope(
        roomRoutingTag = "rt-abc",
        senderId = "alice",
        senderPublicKey = "alice-pk",
        senderEphemeralPublic = "eph-pub",
        contentCiphertext = "ct",
        contentIv = "civ",
        keyWraps = wraps,
        signature = "sig",
    )

    // ── Wire shape ────────────────────────────────────────────────────────────

    @Test fun `envelope JSON round-trips`() {
        val original = envelope(listOf(wrap("bob"), wrap("charlie")))
        val decoded = WireJson.decodeFromString<MultiRecipientEnvelope>(
            WireJson.encodeToString(original)
        )
        assertEquals(original, decoded)
    }

    @Test fun `KeyWrap JSON round-trips`() {
        val original = wrap("bob")
        val decoded = WireJson.decodeFromString<KeyWrap>(WireJson.encodeToString(original))
        assertEquals(original, decoded)
    }

    // ── Signable bytes ────────────────────────────────────────────────────────

    @Test fun `signable bytes include the domain tag prefix`() {
        val bytes = multiRecipientEnvelopeSignableBytes(
            roomRoutingTag = "rt",
            senderId = "s",
            senderPublicKey = "spk",
            senderEphemeralPublic = "eph",
            contentCiphertext = "ct",
            contentIv = "civ",
            keyWraps = emptyList(),
        ).decodeToString()
        assertTrue(bytes.startsWith("rumor-room-envelope-v1:"),
            "domain-tag prefix is the anti-cross-protocol-replay marker; sig over this " +
                "byte sequence cannot be lifted into any other v1 sig scope.")
    }

    @Test fun `key-wrap order does not affect signable bytes (sorted by recipientId)`() {
        // Relay permuting the keyWraps list must NOT change the canonical
        // byte sequence the signature covers. The sort-by-recipientId in the
        // signable-bytes helper makes the signature stable across any
        // recipient ordering the sender chose.
        val a = multiRecipientEnvelopeSignableBytes(
            "rt", "s", "spk", "eph", "ct", "civ",
            keyWraps = listOf(wrap("bob"), wrap("alice"), wrap("charlie")),
        )
        val b = multiRecipientEnvelopeSignableBytes(
            "rt", "s", "spk", "eph", "ct", "civ",
            keyWraps = listOf(wrap("charlie"), wrap("alice"), wrap("bob")),
        )
        assertTrue(a.contentEquals(b),
            "sorting by recipientId means relay-permutation cannot produce a " +
                "canonically-different signable sequence.")
    }

    @Test fun `adding a recipient changes signable bytes (relay cannot extend)`() {
        val base = multiRecipientEnvelopeSignableBytes(
            "rt", "s", "spk", "eph", "ct", "civ",
            keyWraps = listOf(wrap("bob")),
        )
        val extended = multiRecipientEnvelopeSignableBytes(
            "rt", "s", "spk", "eph", "ct", "civ",
            keyWraps = listOf(wrap("bob"), wrap("attacker")),
        )
        assertFalse(base.contentEquals(extended),
            "a relay appending 'attacker' to the recipient list must break the sig " +
                "(otherwise they could inject themselves as a member after the fact).")
    }

    @Test fun `removing a recipient changes signable bytes (relay cannot trim)`() {
        val full = multiRecipientEnvelopeSignableBytes(
            "rt", "s", "spk", "eph", "ct", "civ",
            keyWraps = listOf(wrap("bob"), wrap("alice")),
        )
        val trimmed = multiRecipientEnvelopeSignableBytes(
            "rt", "s", "spk", "eph", "ct", "civ",
            keyWraps = listOf(wrap("alice")),
        )
        assertFalse(full.contentEquals(trimmed))
    }

    @Test fun `changing contentCiphertext changes signable bytes`() {
        val a = multiRecipientEnvelopeSignableBytes(
            "rt", "s", "spk", "eph", "ct-a", "civ", listOf(wrap("bob")),
        )
        val b = multiRecipientEnvelopeSignableBytes(
            "rt", "s", "spk", "eph", "ct-b", "civ", listOf(wrap("bob")),
        )
        assertFalse(a.contentEquals(b))
    }

    @Test fun `changing the ephemeral pub changes signable bytes`() {
        val a = multiRecipientEnvelopeSignableBytes(
            "rt", "s", "spk", "eph-1", "ct", "civ", listOf(wrap("bob")),
        )
        val b = multiRecipientEnvelopeSignableBytes(
            "rt", "s", "spk", "eph-2", "ct", "civ", listOf(wrap("bob")),
        )
        assertFalse(a.contentEquals(b),
            "if the ephemeral pub is changed by a relay, every key wrap that " +
                "depended on DH against it must mismatch — the sig must catch this.")
    }

    @Test fun `changing the routing tag changes signable bytes`() {
        val a = multiRecipientEnvelopeSignableBytes(
            "rt-1", "s", "spk", "eph", "ct", "civ", listOf(wrap("bob")),
        )
        val b = multiRecipientEnvelopeSignableBytes(
            "rt-2", "s", "spk", "eph", "ct", "civ", listOf(wrap("bob")),
        )
        assertFalse(a.contentEquals(b))
    }

    @Test fun `empty recipient list still produces stable signable bytes`() {
        // Edge case — a sender composing for a room with zero current
        // members. The envelope should still sign cleanly so the sender
        // has a signed record. (Whether the protocol allows
        // zero-recipient envelopes is a policy decision the relay layer
        // would enforce; the wire format permits the shape.)
        val bytes = multiRecipientEnvelopeSignableBytes(
            "rt", "s", "spk", "eph", "ct", "civ", emptyList(),
        )
        assertTrue(bytes.isNotEmpty())
        assertTrue(bytes.decodeToString().startsWith("rumor-room-envelope-v1:"))
    }
}
