// RumorCryptoBridge — Swift bridge for the three CryptoKit primitives
// Rumor's Kotlin/Native iosMain cannot reach.
//
// Kotlin/Native cinterop reaches Objective-C and C public surfaces of every
// Apple framework. CryptoKit has neither — it's Swift-first with no @objc
// API. So Ed25519 / X25519 / AES-GCM have to be reached via this bridge,
// compiled into the iOS app target and exposed back to Kotlin via the
// auto-generated Objective-C interop.
//
// Pre-Xcode: this file is vendored standalone so it's version-controlled
// and reviewable. Once an Xcode project exists, add this file to the iOS
// app target's Compile Sources and ensure the framework export surface
// includes RumorCryptoBridge.
//
// Contract reference: docs/IOS_SWIFT_BRIDGE_SPEC.md
// Validation: core/src/commonTest/.../CryptoGoldenVectorsTest.kt — every
// pinned JVM byte output must reproduce byte-for-byte through this bridge
// before iOS ships.

import CryptoKit
import Foundation

@objc public class RumorCryptoBridge: NSObject {

    // ── Ed25519 ──────────────────────────────────────────────────────────

    /// Returns a fresh keypair as [Data, Data] in [privateKey, publicKey] order.
    /// Each Data is exactly 32 bytes.
    @objc public static func ed25519GenerateKeyPair() -> NSArray {
        let priv = Curve25519.Signing.PrivateKey()
        return [priv.rawRepresentation, priv.publicKey.rawRepresentation] as NSArray
    }

    /// 64-byte detached signature. Ed25519 is deterministic — same (msg, key)
    /// always produces the same bytes.
    @objc public static func ed25519Sign(_ message: Data, privateKey: Data) -> Data {
        let key = try! Curve25519.Signing.PrivateKey(rawRepresentation: privateKey)
        return try! key.signature(for: message)
    }

    @objc public static func ed25519Verify(_ message: Data, signature: Data, publicKey: Data) -> Bool {
        guard let pub = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKey) else { return false }
        return pub.isValidSignature(signature, for: message)
    }

    // ── X25519 ───────────────────────────────────────────────────────────

    @objc public static func x25519GenerateKeyPair() -> NSArray {
        let priv = Curve25519.KeyAgreement.PrivateKey()
        return [priv.rawRepresentation, priv.publicKey.rawRepresentation] as NSArray
    }

    /// 32-byte raw shared secret. Caller (Kotlin side) applies the KDF wrapper
    /// via PlatformCrypto.pbkdf2 to match the JVM-side `x25519 KDF wrapper`
    /// golden vector.
    @objc public static func x25519Agreement(_ ourPrivate: Data, theirPublic: Data) -> Data {
        let priv = try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: ourPrivate)
        let pub = try! Curve25519.KeyAgreement.PublicKey(rawRepresentation: theirPublic)
        let shared = try! priv.sharedSecretFromKeyAgreement(with: pub)
        return shared.withUnsafeBytes { Data($0) }
    }

    // ── AES-256-GCM ──────────────────────────────────────────────────────

    /// Ciphertext returned is `body || tag` (16-byte 128-bit tag appended),
    /// matching the JVM `javax.crypto.Cipher` GCM convention. The IV is NOT
    /// prefixed — it's passed alongside.
    @objc public static func aesGcmEncrypt(_ plaintext: Data, key: Data, iv: Data) -> Data {
        let k = SymmetricKey(data: key)
        let nonce = try! AES.GCM.Nonce(data: iv)
        let sealed = try! AES.GCM.seal(plaintext, using: k, nonce: nonce)
        return sealed.ciphertext + sealed.tag
    }

    @objc public static func aesGcmDecrypt(_ ciphertext: Data, key: Data, iv: Data) -> Data {
        let k = SymmetricKey(data: key)
        let nonce = try! AES.GCM.Nonce(data: iv)
        // Split body | tag (tag is the last 16 bytes per GCM convention)
        let tagStart = ciphertext.count - 16
        let body = ciphertext.subdata(in: 0..<tagStart)
        let tag = ciphertext.subdata(in: tagStart..<ciphertext.count)
        let box = try! AES.GCM.SealedBox(nonce: nonce, ciphertext: body, tag: tag)
        return try! AES.GCM.open(box, using: k)
    }
}

// MARK: - Pre-ship hardening notes
//
// The `try!` calls above terminate the process on malformed input. Acceptable
// for the first cut because the Kotlin caller controls every byte that
// reaches this bridge (keys come from generateKeyPair output, IVs from
// SecureRandom, ciphertext from a paired encrypt) — there's no untrusted
// path. Convert to `do/catch` returning an optional Data once the golden-
// vector test passes on a real device and a documented error type is added
// to the bridge contract.
