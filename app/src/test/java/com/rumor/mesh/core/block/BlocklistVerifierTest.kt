package com.rumor.mesh.core.block

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.Blocklist
import com.rumor.mesh.core.model.BlocklistDiff
import com.rumor.mesh.core.model.blocklistDiffSignableBytes
import com.rumor.mesh.core.model.blocklistSignableBytes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlocklistVerifierTest {

    private lateinit var keyPair: CryptoManager.Ed25519KeyPair
    private lateinit var publisherId: String

    @Before
    fun setup() {
        keyPair = CryptoManager.generateEd25519KeyPair()
        publisherId = CryptoManager.publicKeyToUserId(keyPair.publicKeyBytes)
    }

    // ── Snapshot (Blocklist) ──────────────────────────────────────────────────

    private fun signedSnapshot(
        id: String = publisherId,
        version: Long = 1L,
        entries: List<String> = listOf("user-a", "user-b"),
        pubKey: ByteArray = keyPair.publicKeyBytes,
        privKey: ByteArray = keyPair.privateKeyBytes,
    ): Pair<Blocklist, ByteArray> {
        val signable = blocklistSignableBytes(id, version, entries)
        val sig = CryptoManager.sign(signable, privKey).toBase64()
        return Blocklist(id, version, entries, sig) to pubKey
    }

    @Test
    fun `valid snapshot verifies`() {
        val (snapshot, pubKey) = signedSnapshot()
        assertTrue(BlocklistVerifier.verifySnapshot(snapshot, pubKey))
    }

    @Test
    fun `snapshot with wrong public key fails`() {
        val (snapshot, _) = signedSnapshot()
        val otherKey = CryptoManager.generateEd25519KeyPair()
        assertFalse(BlocklistVerifier.verifySnapshot(snapshot, otherKey.publicKeyBytes))
    }

    @Test
    fun `snapshot with tampered entry fails`() {
        val (snapshot, pubKey) = signedSnapshot(entries = listOf("user-a", "user-b"))
        val tampered = snapshot.copy(entries = listOf("user-a", "user-b", "injected"))
        assertFalse(BlocklistVerifier.verifySnapshot(tampered, pubKey))
    }

    @Test
    fun `snapshot with wrong publisherId fails`() {
        val (snapshot, pubKey) = signedSnapshot()
        val tampered = snapshot.copy(publisherId = "not-the-real-id")
        assertFalse(BlocklistVerifier.verifySnapshot(tampered, pubKey))
    }

    @Test
    fun `snapshot with tampered version fails`() {
        val (snapshot, pubKey) = signedSnapshot(version = 1L)
        val tampered = snapshot.copy(version = 2L)
        assertFalse(BlocklistVerifier.verifySnapshot(tampered, pubKey))
    }

    @Test
    fun `snapshot with garbled signature returns false`() {
        val (snapshot, pubKey) = signedSnapshot()
        val garbled = snapshot.copy(signature = "not-valid-base64!!!")
        assertFalse(BlocklistVerifier.verifySnapshot(garbled, pubKey))
    }

    @Test
    fun `empty entry list still verifies`() {
        val (snapshot, pubKey) = signedSnapshot(entries = emptyList())
        assertTrue(BlocklistVerifier.verifySnapshot(snapshot, pubKey))
    }

    // ── Diff (BlocklistDiff) ──────────────────────────────────────────────────

    private fun signedDiff(
        id: String = publisherId,
        fromVersion: Long = 1L,
        toVersion: Long = 2L,
        added: List<String> = listOf("user-c"),
        removed: List<String> = listOf("user-a"),
        pubKey: ByteArray = keyPair.publicKeyBytes,
        privKey: ByteArray = keyPair.privateKeyBytes,
    ): Pair<BlocklistDiff, ByteArray> {
        val signable = blocklistDiffSignableBytes(id, fromVersion, toVersion, added, removed)
        val sig = CryptoManager.sign(signable, privKey).toBase64()
        return BlocklistDiff(id, fromVersion, toVersion, added, removed, sig) to pubKey
    }

    @Test
    fun `valid diff verifies`() {
        val (diff, pubKey) = signedDiff()
        assertTrue(BlocklistVerifier.verifyDiff(diff, pubKey))
    }

    @Test
    fun `diff with wrong public key fails`() {
        val (diff, _) = signedDiff()
        val otherKey = CryptoManager.generateEd25519KeyPair()
        assertFalse(BlocklistVerifier.verifyDiff(diff, otherKey.publicKeyBytes))
    }

    @Test
    fun `diff with tampered added list fails`() {
        val (diff, pubKey) = signedDiff()
        val tampered = diff.copy(added = diff.added + "injected-user")
        assertFalse(BlocklistVerifier.verifyDiff(tampered, pubKey))
    }

    @Test
    fun `diff with tampered removed list fails`() {
        val (diff, pubKey) = signedDiff()
        val tampered = diff.copy(removed = diff.removed + "injected-removal")
        assertFalse(BlocklistVerifier.verifyDiff(tampered, pubKey))
    }

    @Test
    fun `diff with toVersion not greater than fromVersion fails`() {
        val (diff, pubKey) = signedDiff(fromVersion = 5L, toVersion = 5L)
        assertFalse(BlocklistVerifier.verifyDiff(diff, pubKey))
    }

    @Test
    fun `diff with toVersion less than fromVersion fails`() {
        // Sign with the backward versions so signature is technically valid.
        val (diff, pubKey) = signedDiff(fromVersion = 5L, toVersion = 3L)
        assertFalse(BlocklistVerifier.verifyDiff(diff, pubKey))
    }

    @Test
    fun `diff with wrong publisherId fails`() {
        val (diff, pubKey) = signedDiff()
        val tampered = diff.copy(publisherId = "wrong-id")
        assertFalse(BlocklistVerifier.verifyDiff(tampered, pubKey))
    }

    @Test
    fun `empty added and removed lists still verify`() {
        val (diff, pubKey) = signedDiff(added = emptyList(), removed = emptyList())
        assertTrue(BlocklistVerifier.verifyDiff(diff, pubKey))
    }
}
