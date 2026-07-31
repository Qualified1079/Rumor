package com.rumor.mesh.node

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.time.HlcTimestamp
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * O182 — first `:node` test. Beyond the obvious coverage, it exists so `:node`
 * is compiled + exercised in CI (O163): the node wires `:core` classes
 * positionally in `main()`, so a breaking `:core` signature change would
 * otherwise slip through until someone ran the node by hand (the same shape as
 * the documented ":app JUnit4 tests silently never ran" incident).
 */
class NodeIdentityProviderTest {

    private fun tempDir() = Files.createTempDirectory("rumor-node-test").toFile().also { it.deleteOnExit() }

    @Test
    fun `generates a well-formed identity on first run`() {
        val provider = NodeIdentityProvider(tempDir())
        val id = provider.identity.value
        assertNotNull("fresh dir must yield an identity", id)
        assertTrue(provider.isUnlocked)
        // Identity binding: userId is the fingerprint of the public key.
        assertEquals(
            "userId must derive from the public key",
            CryptoManager.publicKeyToUserId(id!!.publicKeyBytes),
            id.userId,
        )
        assertEquals(32, id.publicKeyBytes.size)
        assertEquals(32, id.privateKeyBytes.size)
    }

    @Test
    fun `identity is stable across restarts on the same data dir`() {
        val dir = tempDir()
        val first = NodeIdentityProvider(dir).identity.value!!
        // A second provider over the same dir simulates a node restart — it must
        // load the persisted seed, not mint a fresh identity (a new userId every
        // boot would look like a brand-new peer, useless as an instrument).
        val second = NodeIdentityProvider(dir).identity.value!!
        assertEquals(first.userId, second.userId)
        assertEquals(first.deviceId, second.deviceId)
        assertTrue(first.publicKeyBytes.contentEquals(second.publicKeyBytes))
        assertTrue(first.privateKeyBytes.contentEquals(second.privateKeyBytes))
    }

    // O180: the unencrypted seed file must never be group/other-readable, and
    // must be owner-only from the moment it exists (no create-then-chmod window).
    @Test
    fun `seed file is owner-only after generation`() {
        val dir = tempDir()
        NodeIdentityProvider(dir)
        val seedFile = File(dir, "identity.properties").toPath()
        val store = Files.getFileStore(seedFile)
        assumeTrue("POSIX permissions unsupported on this FS", store.supportsFileAttributeView("posix"))
        val perms = Files.getPosixFilePermissions(seedFile)
        assertFalse("seed must not be group-readable", perms.contains(PosixFilePermission.GROUP_READ))
        assertFalse("seed must not be other-readable", perms.contains(PosixFilePermission.OTHERS_READ))
        assertTrue("owner must retain read", perms.contains(PosixFilePermission.OWNER_READ))
    }

    @Test
    fun `FileHlcStore defaults to zero then round-trips`() {
        val dir = tempDir()
        val store = FileHlcStore(dir)
        assertEquals(HlcTimestamp(0L, 0L), store.load())
        store.save(HlcTimestamp(1_720_000_000_000L, 42L))
        // A fresh store over the same dir reads back the persisted stamp.
        assertEquals(HlcTimestamp(1_720_000_000_000L, 42L), FileHlcStore(dir).load())
    }
}
