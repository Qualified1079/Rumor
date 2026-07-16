package com.rumor.mesh.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomTagMatcherTest {

    private fun encryptedSub(roomId: String): RoomTagMatcher.EncryptedRoomSubscription {
        // Distinct routing key per room — derive from the roomId for
        // determinism in tests; real subscriptions get the key from
        // the invite payload.
        val key = RoomRoutingTag.deriveEncryptedRoomRoutingKey(roomId.encodeToByteArray())
        return RoomTagMatcher.EncryptedRoomSubscription(roomId, key)
    }

    // ── OPEN matching ─────────────────────────────────────────────────────────

    @Test fun `matches an open subscription`() {
        val tag = RoomRoutingTag.openRoomTag("room-1")
        val result = RoomTagMatcher.match(
            inboundTag = tag,
            messageId = "any-msg",
            openSubscriptions = listOf("room-1", "room-2"),
            encryptedSubscriptions = emptyList(),
        )
        assertTrue(result is RoomTagMatcher.MatchResult.OpenMatch)
        assertEquals("room-1", result.roomId)
    }

    @Test fun `non-subscribed open room is dropped`() {
        val tag = RoomRoutingTag.openRoomTag("room-not-mine")
        val result = RoomTagMatcher.match(
            inboundTag = tag,
            messageId = "any-msg",
            openSubscriptions = listOf("room-a", "room-b"),
            encryptedSubscriptions = emptyList(),
        )
        assertNull(result)
    }

    // ── ENCRYPTED matching ────────────────────────────────────────────────────

    @Test fun `matches an encrypted subscription on per-message tag`() {
        val sub = encryptedSub("secret-room")
        val tag = RoomRoutingTag.encryptedRoomTag(sub.routingKey, "msg-id-123")
        val result = RoomTagMatcher.match(
            inboundTag = tag,
            messageId = "msg-id-123",
            openSubscriptions = emptyList(),
            encryptedSubscriptions = listOf(sub),
        )
        assertTrue(result is RoomTagMatcher.MatchResult.EncryptedMatch)
        assertEquals("secret-room", result.roomId)
    }

    @Test fun `encrypted match requires the correct messageId`() {
        val sub = encryptedSub("secret-room")
        val tag = RoomRoutingTag.encryptedRoomTag(sub.routingKey, "msg-id-123")
        // Try matching with a different messageId — the per-message tag
        // wouldn't recompute to the same bytes.
        val result = RoomTagMatcher.match(
            inboundTag = tag,
            messageId = "msg-id-DIFFERENT",
            openSubscriptions = emptyList(),
            encryptedSubscriptions = listOf(sub),
        )
        assertNull(result, "per-message tag requires same messageId on both sides")
    }

    @Test fun `non-subscribed encrypted room is dropped`() {
        val mySub = encryptedSub("my-room")
        val theirSub = encryptedSub("their-room")
        // Inbound was tagged with their key + some messageId.
        val tag = RoomRoutingTag.encryptedRoomTag(theirSub.routingKey, "msg")
        val result = RoomTagMatcher.match(
            inboundTag = tag,
            messageId = "msg",
            openSubscriptions = emptyList(),
            encryptedSubscriptions = listOf(mySub),
        )
        assertNull(result)
    }

    // ── Mixed and edge cases ──────────────────────────────────────────────────

    @Test fun `mixed subscriptions — OPEN match wins on cheaper path first`() {
        // Sanity: OPEN check runs before ENCRYPTED, so an inbound
        // tag that happens to match an OPEN sub gets reported as
        // OPEN even if an encrypted sub coincidentally exists.
        val openTag = RoomRoutingTag.openRoomTag("open-room")
        val encSub = encryptedSub("enc-room")
        val result = RoomTagMatcher.match(
            inboundTag = openTag,
            messageId = "msg",
            openSubscriptions = listOf("open-room"),
            encryptedSubscriptions = listOf(encSub),
        )
        assertTrue(result is RoomTagMatcher.MatchResult.OpenMatch)
    }

    @Test fun `empty subscription set returns null`() {
        val tag = RoomRoutingTag.openRoomTag("any")
        assertNull(RoomTagMatcher.match(
            inboundTag = tag, messageId = "msg",
            openSubscriptions = emptyList(),
            encryptedSubscriptions = emptyList(),
        ))
    }

    @Test fun `random bytes do not match any subscription`() {
        // Sanity guard against a future change that accidentally
        // makes matching return non-null for arbitrary inputs.
        val randomTag = ByteArray(16) { 0xAB.toByte() }
        val result = RoomTagMatcher.match(
            inboundTag = randomTag,
            messageId = "msg",
            openSubscriptions = listOf("a", "b", "c"),
            encryptedSubscriptions = listOf(encryptedSub("x"), encryptedSub("y")),
        )
        assertNull(result)
    }

    @Test fun `multiple open subscriptions — only the matching one wins`() {
        val tag = RoomRoutingTag.openRoomTag("room-c")
        val result = RoomTagMatcher.match(
            inboundTag = tag,
            messageId = "msg",
            openSubscriptions = listOf("room-a", "room-b", "room-c", "room-d"),
            encryptedSubscriptions = emptyList(),
        )
        assertTrue(result is RoomTagMatcher.MatchResult.OpenMatch)
        assertEquals("room-c", result.roomId)
    }

    @Test fun `multiple encrypted subscriptions — only the matching one wins`() {
        val subs = listOf("a", "b", "c", "d").map { encryptedSub(it) }
        val target = subs[2]  // 'c'
        val tag = RoomRoutingTag.encryptedRoomTag(target.routingKey, "msg-7")
        val result = RoomTagMatcher.match(
            inboundTag = tag,
            messageId = "msg-7",
            openSubscriptions = emptyList(),
            encryptedSubscriptions = subs,
        )
        assertTrue(result is RoomTagMatcher.MatchResult.EncryptedMatch)
        assertEquals("c", result.roomId)
    }
}
