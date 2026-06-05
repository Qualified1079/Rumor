package com.rumor.mesh.core.model

import com.rumor.mesh.core.wire.WireJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RoomTest {

    @Test fun `RoomCreate round-trips through WireJson`() {
        val original = RoomCreate(
            roomId = "abc123",
            name = "neighborhood",
            createdBy = "alice",
            createdAtMs = 1_000_000L,
            membershipPolicy = RoomMembershipPolicy.OPEN,
            postingPolicy = RoomPostingPolicy.ANYONE_WITH_MOD_REMOVAL,
            allowMedia = true,
            signature = "sig-placeholder",
        )
        val json = WireJson.encodeToString(original)
        val decoded = WireJson.decodeFromString<RoomCreate>(json)
        assertEquals(original, decoded)
    }

    @Test fun `RoomInvite round-trips`() {
        val original = RoomInvite(
            roomId = "room1",
            invitedUserId = "bob",
            encryptedRoomKey = "ct-base64",
            expiresAtMs = 5_000L,
            moderatorId = "alice",
            moderatorSignature = "sig",
        )
        val decoded = WireJson.decodeFromString<RoomInvite>(WireJson.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test fun `RoomAction round-trips with null reason`() {
        val original = RoomAction(
            roomId = "room1",
            kind = RoomActionKind.KICK_USER,
            target = "bob",
            reason = null,
            moderatorId = "alice",
            takenAtMs = 0L,
            moderatorSignature = "sig",
        )
        val decoded = WireJson.decodeFromString<RoomAction>(WireJson.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test fun `signable bytes are stable and unique to roomCreate`() {
        val a = roomCreateSignableBytes("r1", "n", "alice", 0L,
            RoomMembershipPolicy.OPEN, RoomPostingPolicy.MEMBER_ONLY, allowMedia = false)
        val b = roomCreateSignableBytes("r1", "n", "alice", 0L,
            RoomMembershipPolicy.OPEN, RoomPostingPolicy.MEMBER_ONLY, allowMedia = true)
        // Single-field diff (allowMedia) produces different bytes.
        assertFalse(a.contentEquals(b))
    }

    @Test fun `signable bytes domain tags are distinct across the three types`() {
        val createBytes = roomCreateSignableBytes("r", "n", "u", 0L,
            RoomMembershipPolicy.OPEN, RoomPostingPolicy.MEMBER_ONLY, false).decodeToString()
        val inviteBytes = roomInviteSignableBytes("r", "u", "ct", 0L, "m").decodeToString()
        val actionBytes = roomActionSignableBytes("r", RoomActionKind.KICK_USER,
            "u", null, "m", 0L).decodeToString()
        // Domain-tag separation — sig over one struct cannot be lifted to another.
        assertEquals(true, createBytes.startsWith("rumor-room-create-v1:"))
        assertEquals(true, inviteBytes.startsWith("rumor-room-invite-v1:"))
        assertEquals(true, actionBytes.startsWith("rumor-room-action-v1:"))
    }
}
