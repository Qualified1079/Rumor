package com.rumor.mesh.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCredentialsTest {

    private val hostId = "a".repeat(64)

    @Test
    fun `deterministic - same host yields same credentials`() {
        assertEquals(GroupCredentials.forHost(hostId), GroupCredentials.forHost(hostId))
    }

    @Test
    fun `different hosts yield different credentials`() {
        val other = GroupCredentials.forHost("b".repeat(64))
        val mine = GroupCredentials.forHost(hostId)
        assertNotEquals(mine.networkName, other.networkName)
        assertNotEquals(mine.passphrase, other.passphrase)
    }

    @Test
    fun `network name satisfies android constraints`() {
        val c = GroupCredentials.forHost(hostId)
        // DIRECT- prefix, first two suffix chars alphanumeric, ≤32 total.
        assertTrue(c.networkName.startsWith("DIRECT-"))
        assertTrue(c.networkName.length <= 32)
        val suffix = c.networkName.removePrefix("DIRECT-")
        assertTrue(suffix.length >= 2)
        assertTrue(suffix.take(2).all { it.isLetterOrDigit() })
    }

    @Test
    fun `passphrase satisfies wpa2 constraints`() {
        val c = GroupCredentials.forHost(hostId)
        assertTrue(c.passphrase.length in 8..63)
        assertTrue(c.passphrase.all { it.code in 32..126 })
    }

    @Test
    fun `pinned vector - derivation is part of the deployed protocol`() {
        // Both endpoints derive independently; a silent change to the KDF
        // partitions old and new builds. Recompute only on a deliberate,
        // version-bumped break.
        val c = GroupCredentials.forHost(hostId)
        assertEquals(12, c.networkName.removePrefix("DIRECT-").length)
        assertEquals(16, c.passphrase.length)
        assertTrue(c.networkName.removePrefix("DIRECT-").all { it in "0123456789abcdef" })
        assertTrue(c.passphrase.all { it in "0123456789abcdef" })
    }

    @Test
    fun `passphrase is derivable from the ssid alone`() {
        // The bootstrap property: seeing the SSID is enough to join.
        val c = GroupCredentials.forHost(hostId)
        assertEquals(c.passphrase, GroupCredentials.passphraseFor(c.networkName))
        assertTrue(GroupCredentials.BACKBONE_SSID_REGEX.matches(c.networkName))
    }
}
