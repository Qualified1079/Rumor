package com.rumor.mesh.core.crypto

import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pin of the **shape** of an underlying primitive constraint plus the
 * **wired fix** that makes the production DM path work despite that
 * constraint. Originally filed (O91) as a real bug — `composeDirect`
 * called `x25519Agreement` directly on raw Ed25519 pubkey bytes, with
 * BouncyCastle silently accepting any 32 bytes as a Montgomery
 * x-coordinate and producing mismatched shared secrets on the two
 * sides. Closed by routing every DM compose/decrypt site through the
 * Ed25519→X25519 conversion in [CryptoManager.ed25519ToX25519Public]
 * and [CryptoManager.ed25519ToX25519PrivateSeed].
 *
 * The primitive `x25519Agreement` is **still** curve-specific by
 * design — feeding it raw Ed25519 bytes (without conversion) still
 * mismatches. That's correct primitive behaviour; the contract is
 * "X25519 in, shared secret out." The fix lives one layer up where
 * callers convert before passing.
 *
 * This file pins both halves:
 *  - **`primitive gap`**: the raw `x25519Agreement` call on Ed25519
 *    bytes is documented broken — so a future regression that lets
 *    a caller drop the conversion is caught when this test starts
 *    *passing* the wrong assertion.
 *  - **`wired fix`**: round-tripping through the two new conversion
 *    wrappers produces matching shared secrets between any two real
 *    Ed25519 identities. This is the property that lets a DM
 *    actually decrypt on the receiver.
 *
 * **Cross-references:** O38 (receiver-side prekeys uses the same
 * conversion to derive the static X25519); O79
 * `MultiRecipientEnvelopeCodec` (the codec interface takes X25519
 * pubkeys directly; UI/test callers building recipient lists must
 * convert from contact pubkeys via [CryptoManager.ed25519ToX25519Public]);
 * `RoomSubscriptionProvider.localX25519StaticPrivate` in AppModule
 * / SimNode now returns the converted private from the unlocked
 * identity seed.
 */
class Ed25519AsX25519RoundtripTest {

    @Test
    fun `primitive gap — raw Ed25519 bytes through x25519Agreement do NOT round-trip`() {
        // Pinning the underlying primitive's curve-specific behaviour. This is
        // not a bug in `x25519Agreement` — it's working as specified, treating
        // every input as a Montgomery x-coordinate. The pin protects against a
        // future regression that "fixes" the primitive to auto-convert Ed25519
        // input (which would break the contract for callers that already pass
        // genuine X25519 bytes). Callers needing DH against an Ed25519 identity
        // MUST convert first via CryptoManager.ed25519ToX25519* — see the
        // `wired fix` test below for the round-trip property the fix provides.
        val bob = CryptoManager.generateEd25519KeyPair()
        val ephemeral = CryptoManager.generateX25519KeyPair()

        val aliceSideShared = CryptoManager.x25519Agreement(ephemeral.privateKeyBytes, bob.publicKeyBytes)
        val bobSideShared = CryptoManager.x25519Agreement(bob.privateKeyBytes, ephemeral.publicKeyBytes)

        assertFalse(
            aliceSideShared.contentEquals(bobSideShared),
            "If this starts failing (shared secrets MATCH), x25519Agreement was " +
                "modified to auto-convert Ed25519 input. That breaks the contract for " +
                "callers passing genuine X25519 bytes. Revert and route the conversion " +
                "through CryptoManager.ed25519ToX25519* at the call site instead.\n" +
                "Alice-side shared: ${aliceSideShared.toBase64()}\n" +
                "Bob-side shared:   ${bobSideShared.toBase64()}"
        )
    }

    @Test
    fun `wired fix — Ed25519 identities round-trip through the conversion wrappers`() {
        // The compose-side and decrypt-side paths in production go through these
        // two wrappers. Verifying they produce matching shared secrets at the
        // CryptoManager level proves the DM path will decrypt for real Ed25519
        // identities — independently of GossipEngine / ThreadViewModel wiring,
        // so a regression in either of those still leaves this assertion green
        // and the wrappers unbroken.
        val bob = CryptoManager.generateEd25519KeyPair()
        val ephemeral = CryptoManager.generateX25519KeyPair()

        // Sender side (mirror of GossipEngine.composeDirect):
        val bobX25519Pub = CryptoManager.ed25519ToX25519Public(bob.publicKeyBytes)
        val senderShared = CryptoManager.x25519Agreement(ephemeral.privateKeyBytes, bobX25519Pub)

        // Receiver side (mirror of ThreadViewModel.decryptPayload):
        val bobX25519Priv = CryptoManager.ed25519ToX25519PrivateSeed(bob.privateKeyBytes)
        val receiverShared = CryptoManager.x25519Agreement(bobX25519Priv, ephemeral.publicKeyBytes)

        assertTrue(
            senderShared.contentEquals(receiverShared),
            "Sender-side and receiver-side shared secrets MUST match after the " +
                "Ed25519→X25519 conversion. If this fails, O91 step 2 of 2 is broken: " +
                "the conversion wrappers no longer produce matching DH agreement.\n" +
                "Sender:   ${senderShared.toBase64()}\n" +
                "Receiver: ${receiverShared.toBase64()}"
        )

        // End-to-end AEAD round-trip on the derived secrets confirms the keys
        // are functionally interoperable, not just byte-equal.
        val plaintext = "wired fix proves DMs decrypt for Ed25519 identities".encodeToByteArray()
        val ct = CryptoManager.aesGcmEncrypt(plaintext, senderShared)
        val recovered = CryptoManager.aesGcmDecrypt(ct, receiverShared)
        assertTrue(recovered.contentEquals(plaintext))
    }

    @Test
    fun `same-curve sanity — two X25519 identities round-trip cleanly`() {
        // Control test: when both sides use genuine X25519 keypairs (not
        // Ed25519 bytes misinterpreted), the DH agreement matches and AEAD
        // round-trips. Confirms the primitive itself isn't broken.
        val alice = CryptoManager.generateX25519KeyPair()
        val bob = CryptoManager.generateX25519KeyPair()
        val sharedAB = CryptoManager.x25519Agreement(alice.privateKeyBytes, bob.publicKeyBytes)
        val sharedBA = CryptoManager.x25519Agreement(bob.privateKeyBytes, alice.publicKeyBytes)
        assertTrue(sharedAB.contentEquals(sharedBA),
            "Two genuine X25519 keypairs MUST produce matching shared secrets")
        val plaintext = "round trip works with real X25519".encodeToByteArray()
        val ct = CryptoManager.aesGcmEncrypt(plaintext, sharedAB)
        val recovered = CryptoManager.aesGcmDecrypt(ct, sharedBA)
        assertTrue(recovered.contentEquals(plaintext))
    }
}
