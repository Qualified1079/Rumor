package com.rumor.mesh.core.crypto

import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Empirical pin of a **real bug** in Rumor's DM crypto, discovered
 * during the autonomous-overnight session while researching whether
 * the O79 multi-recipient envelope inherits the same gap.
 *
 * **The bug:** `IdentityManager` generates Ed25519 keypairs; `Contact.publicKey`
 * stores the Ed25519 public; `composeDirect` calls
 * `CryptoManager.x25519Agreement(ephemeral.privateKeyBytes, recipientPublicKey)`
 * — passing the recipient's Ed25519 public bytes where an X25519 public is
 * expected. BouncyCastle silently accepts the bytes (X25519 just treats any
 * 32-byte input as a Montgomery x-coordinate) but the math is interpreting
 * Ed25519 (Edwards-form) bytes as Montgomery x-coordinates, so the two
 * sides produce **different** shared secrets.
 *
 * In production this means: a DM between two Rumor users with real
 * persistent identities would fail at the recipient's AEAD tag check (the
 * sender encrypts under one wrong-but-internally-consistent key; the
 * receiver decrypts with a different wrong-but-internally-consistent key).
 *
 * Probably nobody has hit this yet because:
 *  - Rumor is pre-release.
 *  - Existing unit tests generate fresh X25519 keypairs and use them
 *    consistently — they pass cleanly because they don't exercise the
 *    Ed25519-as-X25519 misinterpretation.
 *  - The bridge plugins (Meshtastic, MeshCore) use their own native
 *    key types, never going through this path.
 *
 * **What this test does:** asserts the CURRENT broken state — the
 * shared secrets differ. When the fix lands (Ed25519 → X25519 derivation
 * via the Curve25519 birational map; standard well-known transformation),
 * this test will fail and serve as a regression nudge to also update O79
 * `MultiRecipientEnvelopeCodec` callers and any other site using the
 * Ed25519-as-X25519 pattern.
 *
 * **What needs to happen to fix:**
 *  - Add `ed25519PrivToX25519Priv(ed25519Priv)` — clamp the SHA-512 of
 *    the Ed25519 seed per RFC 7748 §5; the standard "Ed25519 -> X25519
 *    private" derivation.
 *  - Add `ed25519PubToX25519Pub(ed25519Pub)` — decompress the Edwards-form
 *    point, convert to Montgomery x via x = (1 + y) / (1 - y); standard
 *    birational map.
 *  - `composeDirect` calls the public-key converter on `recipientPublicKey`
 *    before passing to `x25519Agreement`.
 *  - `ThreadViewModel.decryptPayload` (and other DM-decrypt sites) call
 *    the private-key converter on `localPrivKey` before passing to
 *    `x25519Agreement`.
 *  - Cross-platform: implement on JVM via BouncyCastle's built-in
 *    `Ed25519KeyConverter` if available, else manual. On iOS via
 *    CryptoKit's Curve25519 internals (or hand-roll over Swift).
 *
 * **Cross-references:** O38 (receiver-side prekeys depends on knowing
 * what the static X25519 actually is); O79 (`MultiRecipientEnvelopeCodec`
 * receives `Recipient(userId, x25519StaticPublic)` — production callers
 * need to convert from contact's Ed25519 pubkey first); the `:app`
 * `RoomSubscriptionProvider.localX25519StaticPrivate()` would return
 * the converted private once this lands.
 */
class Ed25519AsX25519RoundtripTest {

    @Test
    fun `documented gap — Ed25519 bytes as X25519 do NOT round-trip`() {
        // This test asserts the current broken state. When the fix
        // lands, flip the assertion to assertTrue + update the
        // CLAUDE.md row for whichever O# closes this gap.
        val alice = CryptoManager.generateEd25519KeyPair()
        val bob = CryptoManager.generateEd25519KeyPair()
        val ephemeral = CryptoManager.generateX25519KeyPair()

        val aliceSideShared = CryptoManager.x25519Agreement(ephemeral.privateKeyBytes, bob.publicKeyBytes)
        val bobSideShared = CryptoManager.x25519Agreement(bob.privateKeyBytes, ephemeral.publicKeyBytes)

        assertFalse(
            aliceSideShared.contentEquals(bobSideShared),
            "If this assertion starts failing (meaning shared secrets now MATCH), Ed25519->X25519 " +
                "derivation has been wired in. Flip to assertTrue and update CLAUDE.md to mark the " +
                "DM-crypto-round-trip gap as closed.\n" +
                "Alice-side shared: ${aliceSideShared.toBase64()}\n" +
                "Bob-side shared:   ${bobSideShared.toBase64()}"
        )
    }

    @Test
    fun `same-curve sanity — two X25519 identities round-trip cleanly`() {
        // Control test: when both sides use genuine X25519 keypairs (not
        // Ed25519 bytes misinterpreted), the DH agreement matches and AEAD
        // round-trips. This confirms the gap above is specifically the
        // curve-mixing issue, not a general crypto failure.
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
