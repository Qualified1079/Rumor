package com.rumor.mesh.core.model

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityRotationTest {

    @Test
    fun `signable bytes have stable domain prefix`() {
        val bytes = identityRotationSignableBytes(
            oldUserId = "old",
            newUserId = "new",
            newPublicKey = "pub",
            authorizedAtMs = 123L,
        )
        val str = String(bytes, Charsets.UTF_8)
        assertTrue("must carry the domain tag", str.startsWith("rumor-identity-rotation-v1:"))
        assertTrue(str.contains("old"))
        assertTrue(str.contains("new"))
        assertTrue(str.contains("pub"))
        assertTrue(str.contains("123"))
    }

    @Test
    fun `signable bytes deterministically encode all four fields in fixed order`() {
        // Changing any field should change the bytes. Bound to detect accidental
        // field-order changes that would break signatures.
        val base = identityRotationSignableBytes("a", "b", "c", 1L)
        assertFalse(base.contentEquals(identityRotationSignableBytes("x", "b", "c", 1L)))
        assertFalse(base.contentEquals(identityRotationSignableBytes("a", "x", "c", 1L)))
        assertFalse(base.contentEquals(identityRotationSignableBytes("a", "b", "x", 1L)))
        assertFalse(base.contentEquals(identityRotationSignableBytes("a", "b", "c", 2L)))
    }

    @Test
    fun `continuity signature by old key verifies under old public key`() {
        // The whole point: a rotation is authorized iff the holder of the old
        // key signs the transcript. The recipient already has the old pubkey
        // from the contact record and verifies locally.
        val oldKey = CryptoManager.generateEd25519KeyPair()
        val newKey = CryptoManager.generateEd25519KeyPair()
        val oldUserId = CryptoManager.publicKeyToUserId(oldKey.publicKeyBytes)
        val newUserId = CryptoManager.publicKeyToUserId(newKey.publicKeyBytes)
        val authAt = 1_000_000L

        val signable = identityRotationSignableBytes(
            oldUserId = oldUserId,
            newUserId = newUserId,
            newPublicKey = newKey.publicKeyBytes.toBase64(),
            authorizedAtMs = authAt,
        )
        val sig = CryptoManager.sign(signable, oldKey.privateKeyBytes)

        assertTrue(
            "rotation must verify under the OLD public key",
            CryptoManager.verify(signable, sig, oldKey.publicKeyBytes),
        )
        assertFalse(
            "rotation must NOT verify under the new public key",
            CryptoManager.verify(signable, sig, newKey.publicKeyBytes),
        )
    }

    @Test
    fun `domain tag prevents cross-protocol replay against blocklist signature`() {
        // A signature produced for a blocklist publish must not verify as an
        // identity-rotation signature, even if an attacker substitutes the same
        // bytes. The domain prefix makes the signable scopes disjoint.
        val key = CryptoManager.generateEd25519KeyPair()
        val blocklistBytes = blocklistSignableBytes("pub", 1, listOf("a", "b"))
        val sig = CryptoManager.sign(blocklistBytes, key.privateKeyBytes)

        val rotationBytes = identityRotationSignableBytes("old", "new", "pubkey", 1L)
        assertEquals(false, CryptoManager.verify(rotationBytes, sig, key.publicKeyBytes))
    }
}
