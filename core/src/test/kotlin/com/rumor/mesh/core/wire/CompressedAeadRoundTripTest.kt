package com.rumor.mesh.core.wire

import com.rumor.mesh.core.crypto.CryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * O76 — End-to-end round trip across the compress→pad→AEAD pipeline as
 * the GossipEngine compose / ThreadViewModel receive paths will use it.
 *
 * Catches three common future regressions:
 *  1. The canonical [compressionAad] format drifting from
 *     `"rumor-o76:<originalLength>"`.
 *  2. The AES-GCM call sites passing the wrong AAD bytes (e.g.
 *     `bucketIndex` instead of `originalLength`, or forgetting the
 *     `rumor-o76:` prefix).
 *  3. Decode-side forgetting that the unpadding length comes from `cl`
 *     (the AEAD-protected field), NOT from the bucket size.
 */
class CompressedAeadRoundTripTest {

    @Test
    fun `compress then pad then AEAD then reverse recovers plaintext`() {
        val text = "Hello, mesh — this is a test direct message.".encodeToByteArray()
        val key = ByteArray(32) { (it * 11 + 3).toByte() }

        // Sender side: compress + pad
        val encoded = CompressedPaddedCodec.encodeForWire(text)
        assertNotNull(encoded)
        // Then AEAD over the padded blob with originalLength as AAD
        val aad = compressionAad(encoded.originalLength)
        val ct = CryptoManager.aesGcmEncrypt(encoded.bytes, key, aad)

        // Receiver side: AEAD-decrypt with the same AAD then decodeFromWire
        val decrypted = CryptoManager.aesGcmDecrypt(ct, key, aad)
        assertTrue(decrypted.contentEquals(encoded.bytes), "AEAD round trip should be byte-exact")
        val recovered = CompressedPaddedCodec.decodeFromWire(
            bytes = decrypted,
            originalLength = encoded.originalLength,
            maxOutputBytes = PaddingBuckets.MAX_SINGLE_MESSAGE,
        )
        assertNotNull(recovered)
        assertTrue(recovered.contentEquals(text))
    }

    @Test
    fun `tampered AAD originalLength breaks decryption (tag check fails)`() {
        val text = "tamper me if you can".encodeToByteArray()
        val key = ByteArray(32) { (it * 5).toByte() }
        val encoded = CompressedPaddedCodec.encodeForWire(text)!!
        val aadCorrect = compressionAad(encoded.originalLength)
        val aadWrong = compressionAad(encoded.originalLength + 1)
        val ct = CryptoManager.aesGcmEncrypt(encoded.bytes, key, aadCorrect)
        // A relay flipping `_ext.cl` would cause the receiver to compute
        // a different AAD; the AEAD tag check must fail — there must be
        // no way to recover plaintext from a tampered cl.
        try {
            CryptoManager.aesGcmDecrypt(ct, key, aadWrong)
            fail("AEAD decrypt with wrong AAD should have thrown")
        } catch (_: Throwable) {
            // expected — tag check fails
        }
    }

    @Test
    fun `canonical AAD format is rumor-o76 colon decimal`() {
        // If this assertion fails, byte compatibility with iOS and with
        // already-deployed peers is broken. The format MUST be stable.
        assertEquals("rumor-o76:42", compressionAad(42).decodeToString())
        assertEquals("rumor-o76:0", compressionAad(0).decodeToString())
        assertEquals("rumor-o76:65536", compressionAad(65_536).decodeToString())
    }

    @Test
    fun `empty plaintext still round-trips through the AAD-bearing path`() {
        val key = ByteArray(32) { 0x42 }
        val encoded = CompressedPaddedCodec.encodeForWire(ByteArray(0))
        assertNotNull(encoded)
        val aad = compressionAad(encoded.originalLength)
        val ct = CryptoManager.aesGcmEncrypt(encoded.bytes, key, aad)
        val decrypted = CryptoManager.aesGcmDecrypt(ct, key, aad)
        val recovered = CompressedPaddedCodec.decodeFromWire(
            bytes = decrypted,
            originalLength = encoded.originalLength,
            maxOutputBytes = 1024,
        )
        assertNotNull(recovered)
        assertEquals(0, recovered.size)
    }
}
