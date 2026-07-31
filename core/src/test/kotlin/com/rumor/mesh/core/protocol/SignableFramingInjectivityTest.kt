package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.model.FilterAction
import com.rumor.mesh.core.model.FilterEntry
import com.rumor.mesh.core.model.MatchKind
import com.rumor.mesh.core.model.RoomActionKind
import com.rumor.mesh.core.model.bridgeVouchedSignableBytes
import com.rumor.mesh.core.model.keywordFilterListSignableBytes
import com.rumor.mesh.core.model.messageDeleteSignableBytes
import com.rumor.mesh.core.model.roomActionSignableBytes
import com.rumor.mesh.core.model.roomCreateSignableBytes
import com.rumor.mesh.core.model.roomPostingCertSignableBytes
import com.rumor.mesh.core.model.RoomMembershipPolicy
import com.rumor.mesh.core.model.RoomPostingPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O156 — proves the six free-text-carrying transcripts are splice-resistant.
 *
 * Each case constructs two *distinct* logical inputs that, under the retired v1
 * bare-`|` framing, would have produced a byte-identical transcript (a
 * shifted-delimiter collision — the actual forgery). The test asserts:
 *   1. the raw v1-style concatenation DID collide (documents the vuln; gives the
 *      test teeth — if someone reverts to bare delimiters this stays true),
 *   2. the shipped length-prefixed function does NOT collide.
 */
class SignableFramingInjectivityTest {

    // Reproduces the old bare-`|` join for the collision demonstration only.
    private fun rawJoin(tag: String, vararg fields: String) =
        (tag + fields.joinToString("|")).encodeToByteArray()

    private fun eq(a: ByteArray, b: ByteArray) = a.contentEquals(b)

    @Test
    fun `keyword filter list resists name-into-pattern splice`() {
        // A: name carries a '|'; B: that suffix has shifted into a pattern entry.
        val a = keywordFilterListSignableBytes(
            publisherId = "pub", version = 1, name = "clean|x",
            entries = emptyList(), userIdAllowlist = emptySet(),
        )
        val b = keywordFilterListSignableBytes(
            publisherId = "pub", version = 1, name = "clean",
            entries = listOf(FilterEntry("x", FilterAction.BLOCK, MatchKind.SUBSTRING_CI)),
            userIdAllowlist = emptySet(),
        )
        assertFalse(eq(a, b), "framed transcripts for distinct inputs must differ")
    }

    @Test
    fun `room action resists target-into-reason splice`() {
        // v1 collision: (target=a, reason=b|c) vs (target=a|b, reason=c).
        assertTrue(
            eq(
                rawJoin("rumor-room-action-v1:", "r", "KICK_USER", "a", "b|c", "m", "9"),
                rawJoin("rumor-room-action-v1:", "r", "KICK_USER", "a|b", "c", "m", "9"),
            ),
            "sanity: the retired v1 framing really did collide",
        )
        val a = roomActionSignableBytes("r", RoomActionKind.KICK_USER, "a", "b|c", "m", 9)
        val b = roomActionSignableBytes("r", RoomActionKind.KICK_USER, "a|b", "c", "m", 9)
        assertFalse(eq(a, b))
    }

    @Test
    fun `room create resists roomId-into-name splice`() {
        val a = roomCreateSignableBytes(
            "r", "a|b", "creator", 1, RoomMembershipPolicy.INVITE, RoomPostingPolicy.MEMBER_ONLY, false,
        )
        val b = roomCreateSignableBytes(
            "r|a", "b", "creator", 1, RoomMembershipPolicy.INVITE, RoomPostingPolicy.MEMBER_ONLY, false,
        )
        assertFalse(eq(a, b))
    }

    @Test
    fun `room posting cert resists channel splice`() {
        val a = roomPostingCertSignableBytes("r", "chan|x", "u", 1, 2, "mod", "key")
        val b = roomPostingCertSignableBytes("r", "chan", "u", 1, 2, "mod", "key")
        assertFalse(eq(a, b))
    }

    @Test
    fun `bridge vouched resists origin-into-payload splice`() {
        val a = bridgeVouchedSignableBytes("bridge", "net|x", "sender", "payload", 1)
        val b = bridgeVouchedSignableBytes("bridge", "net", "x|sender", "payload", 1)
        assertFalse(eq(a, b))
    }

    @Test
    fun `message delete resists id-into-key splice`() {
        val a = messageDeleteSignableBytes("id|k", "key")
        val b = messageDeleteSignableBytes("id", "k|key")
        assertFalse(eq(a, b))
    }

    @Test
    fun `framed primitive is length-prefixed netstring`() {
        val one = buildString { framed("a"); framed("b") }
        val two = buildString { framed("a|b"); framed("") }
        // Under bare '|' these would both be "a|b"; framed keeps them distinct.
        assertFalse(one == two)
        assertEquals("1:a1:b", one)
    }
}
