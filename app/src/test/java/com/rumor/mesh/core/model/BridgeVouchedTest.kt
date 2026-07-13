package com.rumor.mesh.core.model

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeVouchedTest {

    @Test
    fun `signable bytes carry the domain tag`() {
        val bytes = bridgeVouchedSignableBytes(
            bridgeUserId = "bridge",
            originNetwork = "meshtastic",
            originSenderId = "meshtastic:abc",
            payload = "hello",
            receivedAtMs = 1000L,
        )
        val s = String(bytes, Charsets.UTF_8)
        assertTrue("domain tag prefix", s.startsWith("rumor-bridge-vouched-v1:"))
        assertTrue(s.contains("bridge"))
        assertTrue(s.contains("meshtastic"))
        assertTrue(s.contains("meshtastic:abc"))
        assertTrue(s.contains("hello"))
        assertTrue(s.contains("1000"))
    }

    @Test
    fun `signable bytes are sensitive to every field`() {
        val base = bridgeVouchedSignableBytes("bridge", "net", "sender", "payload", 1L)
        assertFalse(base.contentEquals(bridgeVouchedSignableBytes("X", "net", "sender", "payload", 1L)))
        assertFalse(base.contentEquals(bridgeVouchedSignableBytes("bridge", "X", "sender", "payload", 1L)))
        assertFalse(base.contentEquals(bridgeVouchedSignableBytes("bridge", "net", "X", "payload", 1L)))
        assertFalse(base.contentEquals(bridgeVouchedSignableBytes("bridge", "net", "sender", "X", 1L)))
        assertFalse(base.contentEquals(bridgeVouchedSignableBytes("bridge", "net", "sender", "payload", 2L)))
    }

    @Test
    fun `outer bridge signature verifies under bridge public key`() {
        val bridge = CryptoManager.generateEd25519KeyPair()
        val bridgeUserId = CryptoManager.publicKeyToUserId(bridge.publicKeyBytes)
        val signable = bridgeVouchedSignableBytes(
            bridgeUserId = bridgeUserId,
            originNetwork = "meshcore",
            originSenderId = "meshcore:foo",
            payload = "hi from foreign",
            receivedAtMs = 42L,
        )
        val sig = CryptoManager.sign(signable, bridge.privateKeyBytes)
        assertTrue("verifies under bridge pubkey",
            CryptoManager.verify(signable, sig, bridge.publicKeyBytes))

        // Same content claimed by a different bridge does NOT verify under
        // the original bridge's pubkey — anti-impersonation.
        val otherBridge = CryptoManager.generateEd25519KeyPair()
        assertFalse("must not verify under a different bridge's pubkey",
            CryptoManager.verify(signable, sig, otherBridge.publicKeyBytes))
    }

    @Test
    fun `domain prevents cross-protocol replay against an identity-rotation signature`() {
        val key = CryptoManager.generateEd25519KeyPair()
        val rotationBytes = identityRotationSignableBytes("old", "new", "pubkey", 1L)
        val sig = CryptoManager.sign(rotationBytes, key.privateKeyBytes)
        val vouchedBytes = bridgeVouchedSignableBytes("bridge", "net", "sender", "payload", 1L)
        assertEquals(false, CryptoManager.verify(vouchedBytes, sig, key.publicKeyBytes))
    }

    @Test
    fun `payload can be serialized and round-tripped through WireJson`() {
        val original = BridgeVouchedPayload(
            originNetwork = "meshtastic",
            originSenderId = "meshtastic:abc",
            originSignatureIfAny = "deadbeef".toByteArray().toBase64(),
            originContentType = ContentType.TEXT,
            payload = "Hello from the LoRa side",
            receivedAtMs = 99_999L,
        )
        val json = com.rumor.mesh.core.wire.WireJson.encodeToString(
            BridgeVouchedPayload.serializer(),
            original,
        )
        val parsed = com.rumor.mesh.core.wire.WireJson.decodeFromString(
            BridgeVouchedPayload.serializer(),
            json,
        )
        assertEquals(original, parsed)
    }
}
