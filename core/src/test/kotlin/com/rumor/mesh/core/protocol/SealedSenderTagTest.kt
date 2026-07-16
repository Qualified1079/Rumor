package com.rumor.mesh.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SealedSenderTagTest {

    @Test fun `same key + same messageId yields same tag (both peers can derive)`() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val a = SealedSenderTag.tagFor(key, "msg-id-123")
        val b = SealedSenderTag.tagFor(key, "msg-id-123")
        assertTrue(a.contentEquals(b))
    }

    @Test fun `different messageIds yield different tags`() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val a = SealedSenderTag.tagFor(key, "m1")
        val b = SealedSenderTag.tagFor(key, "m2")
        assertFalse(a.contentEquals(b))
    }

    @Test fun `different keys yield different tags for the same messageId`() {
        val k1 = ByteArray(32) { (it * 7).toByte() }
        val k2 = ByteArray(32) { (it * 11).toByte() }
        val a = SealedSenderTag.tagFor(k1, "m")
        val b = SealedSenderTag.tagFor(k2, "m")
        assertFalse(a.contentEquals(b))
    }

    @Test fun `tag is always 32 bytes`() {
        val key = ByteArray(32) { 0x42 }
        assertEquals(32, SealedSenderTag.tagFor(key, "").size)
        assertEquals(32, SealedSenderTag.tagFor(key, "a").size)
        assertEquals(32, SealedSenderTag.tagFor(key, "a".repeat(10_000)).size)
    }

    @Test fun `domain tag prefix means raw-id HMAC does not collide`() {
        // A tag computed with the SealedSenderTag domain prefix must differ
        // from a raw HMAC over just the messageId — protects against a future
        // protocol introducing a sibling tag (e.g. for ACKs or DELETEs)
        // accidentally colliding with the DM-recipient tag in an observer's
        // dictionary attack.
        val key = ByteArray(32) { 0x42 }
        val withPrefix = SealedSenderTag.tagFor(key, "m1")
        val raw = com.rumor.mesh.core.crypto.HmacSha256.mac(key, "m1".encodeToByteArray())
        assertFalse(withPrefix.contentEquals(raw),
            "domain-tag prefix must produce a different MAC than raw-id HMAC")
    }
}
