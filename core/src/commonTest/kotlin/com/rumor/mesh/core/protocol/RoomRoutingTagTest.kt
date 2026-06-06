package com.rumor.mesh.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomRoutingTagTest {

    // ── Open-room tags ────────────────────────────────────────────────────────

    @Test fun `open-room tag is 16 bytes`() {
        assertEquals(16, RoomRoutingTag.openRoomTag("any-room-id").size)
    }

    @Test fun `open-room tag is deterministic`() {
        val a = RoomRoutingTag.openRoomTag("room-1")
        val b = RoomRoutingTag.openRoomTag("room-1")
        assertTrue(a.contentEquals(b))
    }

    @Test fun `different room ids produce different open-room tags`() {
        val a = RoomRoutingTag.openRoomTag("room-1")
        val b = RoomRoutingTag.openRoomTag("room-2")
        assertFalse(a.contentEquals(b))
    }

    @Test fun `open-room tag does not leak plaintext roomId`() {
        // Sanity check: the tag is a hash truncation; you cannot
        // recover "secret-room-id" from the 16-byte output without
        // brute force.
        val tag = RoomRoutingTag.openRoomTag("secret-room-id")
        // Just confirm the tag bytes don't contain the input as ASCII.
        // This isn't a cryptographic guarantee — it's a guard against
        // a future "simplification" that swaps the hash for a literal
        // truncation of roomId, which would defeat the whole point.
        val tagText = tag.decodeToString()
        assertFalse(tagText.contains("secret"),
            "open-room tag must be a hash, not a literal slice of roomId")
    }

    // ── Encrypted-room tags ───────────────────────────────────────────────────

    @Test fun `encrypted-room tag is 16 bytes`() {
        val key = ByteArray(32) { 0x42 }
        assertEquals(16, RoomRoutingTag.encryptedRoomTag(key, "msg-id").size)
    }

    @Test fun `encrypted-room tag is deterministic for same key + same messageId`() {
        val key = ByteArray(32) { 0x42 }
        val a = RoomRoutingTag.encryptedRoomTag(key, "msg-1")
        val b = RoomRoutingTag.encryptedRoomTag(key, "msg-1")
        assertTrue(a.contentEquals(b),
            "sender and recipient compute the same tag from the same key + messageId")
    }

    @Test fun `different messageIds produce different tags (per-message, not per-room)`() {
        val key = ByteArray(32) { 0x42 }
        val a = RoomRoutingTag.encryptedRoomTag(key, "msg-1")
        val b = RoomRoutingTag.encryptedRoomTag(key, "msg-2")
        assertFalse(a.contentEquals(b),
            "the whole point of per-message tags — a relay can't cluster " +
                "messages from the same room across time")
    }

    @Test fun `different routing keys produce different tags for the same messageId`() {
        val k1 = ByteArray(32) { 0x42 }
        val k2 = ByteArray(32) { 0x55 }
        val a = RoomRoutingTag.encryptedRoomTag(k1, "msg-1")
        val b = RoomRoutingTag.encryptedRoomTag(k2, "msg-1")
        assertFalse(a.contentEquals(b))
    }

    // ── Routing-key derivation ────────────────────────────────────────────────

    @Test fun `deriveEncryptedRoomRoutingKey produces 32 bytes`() {
        assertEquals(32, RoomRoutingTag.deriveEncryptedRoomRoutingKey(ByteArray(64)).size)
    }

    @Test fun `deriveEncryptedRoomRoutingKey is deterministic`() {
        val seed = ByteArray(64) { it.toByte() }
        val a = RoomRoutingTag.deriveEncryptedRoomRoutingKey(seed)
        val b = RoomRoutingTag.deriveEncryptedRoomRoutingKey(seed)
        assertTrue(a.contentEquals(b))
    }

    @Test fun `derived routing key is domain-separated from raw seed`() {
        // The whole point of HKDF here — the routing key MUST differ
        // from any naive 'use the seed directly' use, so that other
        // keys derived from the same seed with different info strings
        // don't collide with the routing key.
        val seed = ByteArray(64) { it.toByte() }
        val derived = RoomRoutingTag.deriveEncryptedRoomRoutingKey(seed)
        assertFalse(derived.contentEquals(seed.copyOf(32)),
            "derived routing key must be domain-separated from raw seed prefix")
    }

    @Test fun `OPEN and ENCRYPTED routing schemes do not collide`() {
        // A relay that holds a key matching the SHA-256-of-roomId output
        // for some OPEN room must NOT accidentally decrypt or route an
        // ENCRYPTED message as if it were that OPEN room's tag, or vice
        // versa. Domain-tag separation in the two derivations
        // ('rumor-room-route-v1:' vs 'rumor-room-msg-tag-v1:')
        // guarantees this. Here we just check the two schemes produce
        // different bytes for analogous inputs.
        val roomId = "shared-name"
        val openTag = RoomRoutingTag.openRoomTag(roomId)
        // Concoct a routing key that hashes the same string and see if
        // the encrypted tag for some messageId could collide. Empirical
        // check — the domain tags ensure no.
        val encKey = RoomRoutingTag.deriveEncryptedRoomRoutingKey(roomId.encodeToByteArray())
        val encTag = RoomRoutingTag.encryptedRoomTag(encKey, "any-msg")
        assertFalse(openTag.contentEquals(encTag),
            "OPEN and ENCRYPTED routing tags use different domain tags to " +
                "prevent cross-scheme collision")
    }
}
