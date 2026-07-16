package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.PrekeyPublish
import com.rumor.mesh.core.model.prekeyPublishSignableBytes
import kotlin.test.Test
import kotlin.test.assertTrue

class PrekeyVerifierTest {

    private data class Identity(val priv: ByteArray, val pub: ByteArray, val userId: String, val pubB64: String)

    private fun newIdentity(): Identity {
        val (priv, pub) = CryptoManager.generateEd25519KeyPair()
        return Identity(priv, pub, CryptoManager.publicKeyToUserId(pub), pub.toBase64())
    }

    private fun signedPublish(id: Identity, prekeyB64: String = ByteArray(32) { 0x42 }.toBase64(),
                              from: Long = 100L, to: Long = 200L,
                              overrideSignedFromMs: Long? = null): PrekeyPublish {
        val signed = prekeyPublishSignableBytes(
            id.userId, id.pubB64, prekeyB64,
            overrideSignedFromMs ?: from, to,
        )
        val sig = CryptoManager.sign(signed, id.priv).toBase64()
        return PrekeyPublish(
            publisherId = id.userId,
            publisherPublicKey = id.pubB64,
            prekeyPublic = prekeyB64,
            validFromMs = from,
            validToMs = to,
            signature = sig,
        )
    }

    @Test fun `valid prekey is accepted`() {
        val id = newIdentity()
        val r = PrekeyVerifier.verify(signedPublish(id))
        assertTrue(r is PrekeyVerifier.Result.Accepted)
    }

    @Test fun `tampered prekey rejected (sig over original key)`() {
        val id = newIdentity()
        val pub = signedPublish(id)
        val tampered = pub.copy(prekeyPublic = ByteArray(32) { 0x55 }.toBase64())
        assertTrue(PrekeyVerifier.verify(tampered) is PrekeyVerifier.Result.Rejected)
    }

    @Test fun `tampered validity window rejected`() {
        val id = newIdentity()
        val pub = signedPublish(id)
        val extended = pub.copy(validToMs = pub.validToMs + 1_000_000L)
        assertTrue(PrekeyVerifier.verify(extended) is PrekeyVerifier.Result.Rejected,
            "Relay extending validity window must break the sig")
    }

    @Test fun `tampered publisherId rejected (does not hash from pubkey)`() {
        val id = newIdentity()
        val pub = signedPublish(id)
        val swapped = pub.copy(publisherId = "0".repeat(64))
        assertTrue(PrekeyVerifier.verify(swapped) is PrekeyVerifier.Result.Rejected)
    }

    @Test fun `tampered publisherPublicKey rejected (userId mismatch then sig fail)`() {
        val id = newIdentity()
        val other = newIdentity()
        val pub = signedPublish(id)
        val swapped = pub.copy(publisherPublicKey = other.pubB64)
        assertTrue(PrekeyVerifier.verify(swapped) is PrekeyVerifier.Result.Rejected,
            "Swapping in another peer's pubkey must fail the userId-binding check")
    }

    @Test fun `bad base64 fields are rejected`() {
        val id = newIdentity()
        val pub = signedPublish(id).copy(publisherPublicKey = "%%%")
        assertTrue(PrekeyVerifier.verify(pub) is PrekeyVerifier.Result.Rejected)
    }

    @Test fun `inverted validity window rejected`() {
        val id = newIdentity()
        // Re-sign with inverted window to pass sig check then trigger
        // the window-sanity check.
        val signed = prekeyPublishSignableBytes(id.userId, id.pubB64,
            ByteArray(32) { 0x42 }.toBase64(), validFromMs = 200L, validToMs = 100L)
        val sig = CryptoManager.sign(signed, id.priv).toBase64()
        val publish = PrekeyPublish(
            publisherId = id.userId,
            publisherPublicKey = id.pubB64,
            prekeyPublic = ByteArray(32) { 0x42 }.toBase64(),
            validFromMs = 200L,
            validToMs = 100L,
            signature = sig,
        )
        val r = PrekeyVerifier.verify(publish)
        assertTrue(r is PrekeyVerifier.Result.Rejected)
    }
}

// Note: signable-bytes test fixture uses `from` and `validToMs` arg names — the
// signing function expects positional (publisherId, publisherPublicKey,
// prekeyPublic, validFromMs, validToMs). The arg name `validToMs` in the call
// above is explicit for readability; the earlier `from = 200L` matches
// `validFromMs` positionally — both are required to be present for the helper
// since we're invoking it positionally past the first three args.
