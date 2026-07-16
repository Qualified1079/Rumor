package com.rumor.mesh.core.crypto

import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Verification of `Ed25519ToX25519` via the strongest property the
 * conversion can have without external test vectors: the round-
 * trip-DH-secrets-match property.
 *
 * If converting Ed25519 keypairs to X25519 keypairs via this code
 * is internally consistent with itself, then `X25519(alice_x_priv,
 * bob_x_pub)` must equal `X25519(bob_x_priv, alice_x_pub)` — the
 * standard symmetric property of Diffie-Hellman.
 *
 * The test also verifies the documented gap in
 * `Ed25519AsX25519RoundtripTest` is closed when we run THROUGH
 * the conversion — i.e. derived shared secrets match, in
 * contrast to the broken state where raw Ed25519 bytes are fed
 * to X25519Agreement directly.
 */
class Ed25519ToX25519ConversionTest {

    @Test
    fun `round-trip DH on converted keys produces matching shared secrets`() {
        // Two Ed25519 keypairs, simulating two real Rumor users.
        val alice = CryptoManager.generateEd25519KeyPair()
        val bob = CryptoManager.generateEd25519KeyPair()

        // Convert both sides to X25519.
        val aliceX25519Priv = Ed25519ToX25519.ed25519PrivToX25519Priv(alice.privateKeyBytes)
        val aliceX25519Pub  = Ed25519ToX25519.ed25519PubToX25519Pub(alice.publicKeyBytes)
        val bobX25519Priv   = Ed25519ToX25519.ed25519PrivToX25519Priv(bob.privateKeyBytes)
        val bobX25519Pub    = Ed25519ToX25519.ed25519PubToX25519Pub(bob.publicKeyBytes)

        // Standard DH on both sides.
        val sharedAB = CryptoManager.x25519Agreement(aliceX25519Priv, bobX25519Pub)
        val sharedBA = CryptoManager.x25519Agreement(bobX25519Priv, aliceX25519Pub)

        assertTrue(
            sharedAB.contentEquals(sharedBA),
            "Converted keys must produce matching DH shared secrets. " +
                "If this fails, the derivation is internally inconsistent.\n" +
                "alice-side: ${sharedAB.toBase64()}\nbob-side:   ${sharedBA.toBase64()}"
        )
    }

    @Test
    fun `derived X25519 private is exactly 32 bytes`() {
        val ed = CryptoManager.generateEd25519KeyPair()
        val x = Ed25519ToX25519.ed25519PrivToX25519Priv(ed.privateKeyBytes)
        assertEquals(32, x.size)
        // X25519 clamping: bits 0,1,2 of byte 0 cleared, bit 7 of byte 31 cleared, bit 6 set.
        assertEquals(0, x[0].toInt() and 0x07)
        assertEquals(0, x[31].toInt() and 0x80)
        assertTrue(x[31].toInt() and 0x40 != 0)
    }

    @Test
    fun `derived X25519 public is exactly 32 bytes`() {
        val ed = CryptoManager.generateEd25519KeyPair()
        val x = Ed25519ToX25519.ed25519PubToX25519Pub(ed.publicKeyBytes)
        assertEquals(32, x.size)
    }

    @Test
    fun `derivation is deterministic`() {
        // Same input -> same output every call (no internal RNG).
        val ed = CryptoManager.generateEd25519KeyPair()
        val priv1 = Ed25519ToX25519.ed25519PrivToX25519Priv(ed.privateKeyBytes)
        val priv2 = Ed25519ToX25519.ed25519PrivToX25519Priv(ed.privateKeyBytes)
        assertTrue(priv1.contentEquals(priv2))
        val pub1 = Ed25519ToX25519.ed25519PubToX25519Pub(ed.publicKeyBytes)
        val pub2 = Ed25519ToX25519.ed25519PubToX25519Pub(ed.publicKeyBytes)
        assertTrue(pub1.contentEquals(pub2))
    }

    @Test
    fun `wrong-size input is rejected`() {
        assertFails { Ed25519ToX25519.ed25519PrivToX25519Priv(ByteArray(31)) }
        assertFails { Ed25519ToX25519.ed25519PrivToX25519Priv(ByteArray(33)) }
        assertFails { Ed25519ToX25519.ed25519PubToX25519Pub(ByteArray(31)) }
        assertFails { Ed25519ToX25519.ed25519PubToX25519Pub(ByteArray(33)) }
    }

    @Test
    fun `multiple distinct keypairs produce distinct conversions`() {
        // Sanity guard against a refactor that accidentally returns
        // a constant.
        val a = Ed25519ToX25519.ed25519PrivToX25519Priv(CryptoManager.generateEd25519KeyPair().privateKeyBytes)
        val b = Ed25519ToX25519.ed25519PrivToX25519Priv(CryptoManager.generateEd25519KeyPair().privateKeyBytes)
        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun `AEAD round-trip works on the derived shared secret`() {
        // Realistic-usage smoke test: derive a shared secret via the
        // conversion, encrypt + decrypt through it.
        val alice = CryptoManager.generateEd25519KeyPair()
        val bob = CryptoManager.generateEd25519KeyPair()
        val aliceX25519Priv = Ed25519ToX25519.ed25519PrivToX25519Priv(alice.privateKeyBytes)
        val bobX25519Pub = Ed25519ToX25519.ed25519PubToX25519Pub(bob.publicKeyBytes)
        val bobX25519Priv = Ed25519ToX25519.ed25519PrivToX25519Priv(bob.privateKeyBytes)
        val aliceX25519Pub = Ed25519ToX25519.ed25519PubToX25519Pub(alice.publicKeyBytes)

        val sharedAB = CryptoManager.x25519Agreement(aliceX25519Priv, bobX25519Pub)
        val sharedBA = CryptoManager.x25519Agreement(bobX25519Priv, aliceX25519Pub)

        val plain = "DM from alice to bob".encodeToByteArray()
        val ct = CryptoManager.aesGcmEncrypt(plain, sharedAB)
        val recovered = CryptoManager.aesGcmDecrypt(ct, sharedBA)
        assertTrue(recovered.contentEquals(plain),
            "If the round-trip works, the conversion is good enough to wire into " +
                "composeDirect / decryptPayload to close the O91 gap.")
    }
}
