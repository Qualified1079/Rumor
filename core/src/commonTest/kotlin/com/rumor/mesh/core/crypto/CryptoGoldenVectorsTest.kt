package com.rumor.mesh.core.crypto

import com.rumor.mesh.core.platform.PlatformCrypto
import com.rumor.mesh.core.platform.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Print-then-pin helper: emits the JVM-produced byte outputs for every
 * deterministic primitive Rumor depends on, so the values can be pasted into
 * [CryptoGoldenVectorsTest] as hard-coded expectations.
 *
 * Run on JVM today, capture the output, paste into the asserting test. Then
 * the asserting test will fail loudly on any platform whose [PlatformCrypto]
 * actual produces different bytes — which is exactly the wire-format
 * regression catch we want for the iOS port (CryptoKit / CommonCrypto vs
 * BouncyCastle / javax.crypto byte-equivalence audit).
 *
 * Not a normal test — it always passes. Read its stdout output.
 */
class GoldenVectorsPrinter {

    private fun ByteArray.toHex(): String =
        joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    @Test
    fun `emit golden vectors for pinning`() {
        println("──── Rumor crypto golden vectors (JVM truth) ────")

        // SHA-256 over a known input.
        println("sha256_of_RumorRocks=" + Sha256.digest("RumorRocks".encodeToByteArray()).toHex())
        println("sha256_of_empty=" + Sha256.digest(ByteArray(0)).toHex())

        // PBKDF2-HMAC-SHA256: deterministic given (password, salt, iters, bits).
        val pb1 = PlatformCrypto.pbkdf2HmacSha256(
            passphrase = "correct horse battery staple",
            salt = byteArrayOf(0x52, 0x75, 0x6d, 0x6f, 0x72, 0x53, 0x61, 0x6c, 0x74),
            iterations = 1000,
            outputBits = 256,
        )
        println("pbkdf2_demo=" + pb1.toHex())

        // The X25519-KDF-wrap form Rumor uses (CryptoManager.deriveAesKey via
        // platformDeriveAesKey). Wire-format-critical for DM session keys.
        val pb2 = platformDeriveAesKey(
            secret = ByteArray(32) { it.toByte() },
            salt = byteArrayOf(0x52, 0x75, 0x6d, 0x6f, 0x72, 0x44, 0x48),
        )
        println("deriveAesKey_x25519kdf=" + pb2.toHex())

        // AES-256-GCM with known key + IV.
        val aesKey = ByteArray(32) { (it * 7).toByte() }
        val aesIv = ByteArray(12) { (it * 13).toByte() }
        val plain = "the quick brown fox jumps over the lazy dog".encodeToByteArray()
        val ct = PlatformCrypto.aesGcmEncrypt(plain, aesKey, aesIv)
        println("aesgcm_ct_demo=" + ct.toHex())

        // Ed25519 sign with a known private key. Note Ed25519 is deterministic
        // signing (no per-signature randomness) so the output is fully pinnable.
        val ed25519Priv = ByteArray(32) { 0x42 }
        val msg = "rumor-msg-v1:demo".encodeToByteArray()
        val sig = PlatformCrypto.ed25519Sign(msg, ed25519Priv)
        println("ed25519_sig_demo=" + sig.toHex())

        // X25519 agreement with known keys is deterministic.
        val ourPriv = ByteArray(32) { 0x01 }
        val theirPub = ByteArray(32) { 0x02 }
        val shared = PlatformCrypto.x25519Agreement(ourPriv, theirPub)
        println("x25519_shared_demo=" + shared.toHex())

        println("──── end golden vectors ────")
    }
}

/**
 * Pins the JVM-produced byte outputs of every deterministic primitive
 * [PlatformCrypto] / [CryptoManager] depends on. Run on every target —
 * any divergence means the platform actual diverges from the JVM truth
 * and would silently break wire-format interop.
 *
 * The values below were captured by running [GoldenVectorsPrinter] on JVM
 * with BouncyCastle 1.70 + SunJCE. They are wire-format-critical: changing
 * them requires bumping the protocol version (the receivers everywhere
 * would have to be updated in lockstep).
 *
 * For RNG-dependent operations (keypair generation, AES-GCM IV) we test
 * round-trip correctness (sign-then-verify, encrypt-then-decrypt) rather
 * than pin specific bytes.
 */
class CryptoGoldenVectorsTest {

    // ── Deterministic byte vectors ──────────────────────────────────────

    @Test
    fun `sha256 of known input`() {
        val actual = Sha256.digest("RumorRocks".encodeToByteArray()).toHex()
        assertEquals(
            "379831de5e33ea6813bf35384c9f5ec32651d590ca506ed3ffcd67a129c6312f",
            actual,
            "SHA-256 byte output drifted. Either the platform actual is broken " +
                "or the JVM truth was regenerated; align before changing this constant."
        )
    }

    @Test
    fun `sha256 of empty input`() {
        // RFC 6234 well-known: SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        val actual = Sha256.digest(ByteArray(0)).toHex()
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            actual,
            "SHA-256 empty-input vector is a well-known constant. If this fails, " +
                "the platform SHA-256 is fundamentally wrong — not a Rumor-specific issue."
        )
    }

    // ── Round-trip correctness (cover RNG-dependent ops) ────────────────

    @Test
    fun `ed25519 sign-then-verify round trip`() {
        val kp = CryptoManager.generateEd25519KeyPair()
        val msg = "round-trip test message".encodeToByteArray()
        val sig = CryptoManager.sign(msg, kp.privateKeyBytes)
        assertTrue(
            CryptoManager.verify(msg, sig, kp.publicKeyBytes),
            "Ed25519 round-trip failed — keypair / sign / verify don't compose."
        )
        // Tamper detection
        val tampered = msg.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertTrue(
            !CryptoManager.verify(tampered, sig, kp.publicKeyBytes),
            "Ed25519 must reject a signature over a different message."
        )
    }

    @Test
    fun `x25519 agreement is symmetric`() {
        val a = CryptoManager.generateX25519KeyPair()
        val b = CryptoManager.generateX25519KeyPair()
        val sharedAB = CryptoManager.x25519Agreement(a.privateKeyBytes, b.publicKeyBytes)
        val sharedBA = CryptoManager.x25519Agreement(b.privateKeyBytes, a.publicKeyBytes)
        assertTrue(
            sharedAB.contentEquals(sharedBA),
            "X25519 agreement must produce the same session key on both sides. " +
                "If this fails, DM crypto is broken — keys derived independently won't decrypt."
        )
        assertEquals(32, sharedAB.size, "Session key must be 32 bytes (AES-256).")
    }

    @Test
    fun `aes256gcm encrypt-then-decrypt round trip`() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val plain = "the quick brown fox jumps over the lazy dog".encodeToByteArray()
        val ct = CryptoManager.aesGcmEncrypt(plain, key)
        val recovered = CryptoManager.aesGcmDecrypt(ct, key)
        assertTrue(
            plain.contentEquals(recovered),
            "AES-256-GCM round-trip failed — encryption / decryption don't compose."
        )
    }

    @Test
    fun `pbkdf2 produces stable output for fixed inputs`() {
        // RFC 6070 / common test-vector inspired: password="password", salt="salt", c=1, dkLen=20
        // For our 256-bit variant we just pin the JVM truth value.
        val out = PlatformCrypto.pbkdf2HmacSha256(
            passphrase = "correct horse battery staple",
            salt = byteArrayOf(0x52, 0x75, 0x6d, 0x6f, 0x72, 0x53, 0x61, 0x6c, 0x74),
            iterations = 1000,
            outputBits = 256,
        )
        assertEquals(32, out.size, "256-bit output must be 32 bytes.")
        assertEquals(
            "78fa071eb029cd782de776cdbad4dc3fea6e738331a116fe2d7c571815a06f76",
            out.toHex(),
            "PBKDF2-HMAC-SHA256 byte output drifted from JVM truth."
        )
    }

    @Test
    fun `x25519 KDF wrapper is stable`() {
        // platformDeriveAesKey(secret=0x00..0x1f, salt="RumorDH") — the production
        // shape used by CryptoManager.x25519Agreement to wrap the raw shared
        // secret into a 256-bit AES key.
        val key = platformDeriveAesKey(
            secret = ByteArray(32) { it.toByte() },
            salt = byteArrayOf(0x52, 0x75, 0x6d, 0x6f, 0x72, 0x44, 0x48),
        )
        assertEquals(32, key.size)
        assertEquals(
            "62096187b6b531b5e1cd0258ea72e23e97f533c3ec0f6027e200f7adcee5eea7",
            key.toHex(),
            "X25519 KDF wrapper byte output drifted from JVM truth. This is the " +
                "actual DM session-key derivation — any change breaks decryption."
        )
    }

    // ── Additional deterministic-RNG-independent vectors (full pinning) ─

    @Test
    fun `ed25519 deterministic-sign with known key`() {
        // Ed25519 signing is deterministic (no per-sig randomness), so we can
        // fully pin the signature bytes.
        val priv = ByteArray(32) { 0x42 }
        val msg = "rumor-msg-v1:demo".encodeToByteArray()
        val sig = PlatformCrypto.ed25519Sign(msg, priv)
        assertEquals(
            "128b32f217480f0ffdd49c45aae4b88b6a8539034bac74a1758b9d5e940d9a59" +
                "a2a73d17a91d1831d616335c6cb1503888c4809a38547d2c37d06a5575a6ba02",
            sig.toHex(),
            "Ed25519 deterministic signature drifted. If this fails on iOS, the " +
                "CryptoKit / bridge implementation produces non-RFC-8032-compliant " +
                "signatures — signed messages will be rejected by Android peers."
        )
    }

    @Test
    fun `x25519 agreement with known keys`() {
        val ourPriv = ByteArray(32) { 0x01 }
        val theirPub = ByteArray(32) { 0x02 }
        val shared = PlatformCrypto.x25519Agreement(ourPriv, theirPub)
        assertEquals(
            "047f039121185037c302191e45982949f7d9b2c310cf0e173321535fbc14df3e",
            shared.toHex(),
            "X25519 raw agreement drifted. This is the input to the DM session-key " +
                "KDF; any change breaks DM crypto across platforms."
        )
    }

    @Test
    fun `aes256gcm with known key and IV`() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val iv = ByteArray(12) { (it * 13).toByte() }
        val plain = "the quick brown fox jumps over the lazy dog".encodeToByteArray()
        val ct = PlatformCrypto.aesGcmEncrypt(plain, key, iv)
        assertEquals(
            "3c060db30e7df1ce9dd7be634b7cfb8d722a13cdf69485ccf86df1a47a11700d" +
                "bbe409124d9a7ab44c6d572fdefe6ff3466e7bbd502586adc4e828",
            ct.toHex(),
            "AES-256-GCM ciphertext (with known key+IV+plaintext) drifted. " +
                "Includes the 128-bit GCM tag — any change in tag width / cipher " +
                "params breaks DM decryption."
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
}
