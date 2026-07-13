package com.rumor.mesh.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the exact key flow the production DM path uses (O91):
 *
 * Compose (GossipEngine.composeDirect):
 *   ephemeral X25519 keypair; shared = X25519(eph.priv, ed25519PubToX25519Pub(recipient Ed25519 pub))
 * Decrypt (ThreadViewModel.decryptPayload):
 *   shared = X25519(ed25519PrivToX25519Priv(local Ed25519 seed), eph.pub)
 *
 * If either conversion is dropped or the KDF diverges, these fail — this is the
 * property that was broken twice on-device: PBKDF2 crashed with "password empty",
 * and Ed25519 bytes were fed to X25519 unconverted.
 */
class Ed25519DmRoundtripTest {

    @Test
    fun `dm shared secret matches between composer and recipient`() {
        val recipient = CryptoManager.generateEd25519KeyPair()
        val ephemeral = CryptoManager.generateX25519KeyPair()

        val composerShared = CryptoManager.x25519Agreement(
            ephemeral.privateKeyBytes,
            Ed25519ToX25519.ed25519PubToX25519Pub(recipient.publicKeyBytes),
        )
        val recipientShared = CryptoManager.x25519Agreement(
            Ed25519ToX25519.ed25519PrivToX25519Priv(recipient.privateKeyBytes),
            ephemeral.publicKeyBytes,
        )

        assertArrayEquals(composerShared, recipientShared)
        assertEquals(32, composerShared.size)
    }

    @Test
    fun `full dm encrypt-decrypt round trip`() {
        val recipient = CryptoManager.generateEd25519KeyPair()
        val ephemeral = CryptoManager.generateX25519KeyPair()
        val plaintext = "the mesh remembers".toByteArray(Charsets.UTF_8)

        val ct = CryptoManager.aesGcmEncrypt(
            plaintext,
            CryptoManager.x25519Agreement(
                ephemeral.privateKeyBytes,
                Ed25519ToX25519.ed25519PubToX25519Pub(recipient.publicKeyBytes),
            ),
        )
        val decrypted = CryptoManager.aesGcmDecrypt(
            ct,
            CryptoManager.x25519Agreement(
                Ed25519ToX25519.ed25519PrivToX25519Priv(recipient.privateKeyBytes),
                ephemeral.publicKeyBytes,
            ),
        )

        assertArrayEquals(plaintext, decrypted)
    }
}
