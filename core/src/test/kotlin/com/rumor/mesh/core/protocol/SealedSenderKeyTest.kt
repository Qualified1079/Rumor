package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SealedSenderKeyTest {

    private data class Id(val priv: ByteArray, val pub: ByteArray, val userId: String)

    private fun newId(): Id {
        val (priv, pub) = CryptoManager.generateEd25519KeyPair()
        return Id(priv, pub, CryptoManager.publicKeyToUserId(pub))
    }

    @Test fun `both sides derive the same tag key`() {
        val alice = newId()
        val bob = newId()
        val aliceSide = SealedSenderKey.derive(alice.userId, alice.priv, bob.userId, bob.pub)
        val bobSide = SealedSenderKey.derive(bob.userId, bob.priv, alice.userId, alice.pub)
        assertTrue(aliceSide.contentEquals(bobSide))
        assertEquals(32, aliceSide.size)
    }

    @Test fun `different contact pairs produce different keys`() {
        val alice = newId()
        val bob = newId()
        val carol = newId()
        val ab = SealedSenderKey.derive(alice.userId, alice.priv, bob.userId, bob.pub)
        val ac = SealedSenderKey.derive(alice.userId, alice.priv, carol.userId, carol.pub)
        assertFalse(ab.contentEquals(ac))
    }

    @Test fun `tag is deterministic across repeated derivations`() {
        val alice = newId()
        val bob = newId()
        val k1 = SealedSenderKey.derive(alice.userId, alice.priv, bob.userId, bob.pub)
        val k2 = SealedSenderKey.derive(alice.userId, alice.priv, bob.userId, bob.pub)
        assertTrue(k1.contentEquals(k2))
    }

    @Test fun `tag for produces matching tags on both sides`() {
        val alice = newId()
        val bob = newId()
        val aliceKey = SealedSenderKey.derive(alice.userId, alice.priv, bob.userId, bob.pub)
        val bobKey = SealedSenderKey.derive(bob.userId, bob.priv, alice.userId, alice.pub)
        val msgId = "msg-abc-123"
        val aliceTag = SealedSenderTag.tagFor(aliceKey, msgId)
        val bobTag = SealedSenderTag.tagFor(bobKey, msgId)
        assertTrue(aliceTag.contentEquals(bobTag))
    }
}
