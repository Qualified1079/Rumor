# iOS Swift bridge — spec for the Ed25519 / X25519 / AES-GCM gap

Kotlin/Native cinterop reaches Objective-C and C public surfaces of every
Apple framework. **CryptoKit has neither** — it's a Swift-first framework
with no public `@objc` API. So the three primitives Rumor needs from
CryptoKit have to be reached via a Swift bridge class compiled into the iOS
app and called from Kotlin via the auto-generated Objective-C interop.

This doc spells out the bridge contract exactly enough that the Swift code
can be written on the first day a Mac (or xtool) is available, with no
design pass needed.

---

## Where this fits

- The Kotlin side already exists: `core/src/iosMain/.../platform/PlatformCrypto.kt`.
  Seven actuals work today via CommonCrypto / Foundation / Security; five
  throw `NotImplementedError(BRIDGE_MISSING)` (the three CryptoKit primitives
  plus the two Ed25519→X25519 derivation helpers added under O91/G29).
- The Swift side does NOT exist. It lives in the (future) iOS Xcode project
  alongside the SwiftUI / lifecycle code, NOT inside `:core`. `:core` stays
  Kotlin-only.
- The bridge class is built into the iOS app binary; Kotlin/Native sees it
  via the Xcode framework export.

## The contract

```swift
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

    /// 32-byte raw shared secret. Caller applies the KDF wrapper.
    @objc public static func x25519Agreement(_ ourPrivate: Data, theirPublic: Data) -> Data {
        let priv = try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: ourPrivate)
        let pub = try! Curve25519.KeyAgreement.PublicKey(rawRepresentation: theirPublic)
        let shared = try! priv.sharedSecretFromKeyAgreement(with: pub)
        return shared.withUnsafeBytes { Data($0) }
    }

    // ── AES-256-GCM ──────────────────────────────────────────────────────

    /// Ciphertext returned is `body || tag` (16-byte 128-bit tag appended),
    /// matching the JVM `javax.crypto.Cipher` GCM convention. The IV is NOT
    /// prefixed — it's passed alongside. [aad] is associated data covered
    /// by the tag but not encrypted; pass empty `Data()` for the legacy
    /// path. Used by O76 to bind originalLength to the tag.
    @objc public static func aesGcmEncrypt(_ plaintext: Data, key: Data, iv: Data, aad: Data) -> Data {
        let k = SymmetricKey(data: key)
        let nonce = try! AES.GCM.Nonce(data: iv)
        let sealed = try! AES.GCM.seal(plaintext, using: k, nonce: nonce, authenticating: aad)
        return sealed.ciphertext + sealed.tag
    }

    @objc public static func aesGcmDecrypt(_ ciphertext: Data, key: Data, iv: Data, aad: Data) -> Data {
        let k = SymmetricKey(data: key)
        let nonce = try! AES.GCM.Nonce(data: iv)
        // Split body | tag (tag is the last 16 bytes per GCM convention)
        let tagStart = ciphertext.count - 16
        let body = ciphertext.subdata(in: 0..<tagStart)
        let tag = ciphertext.subdata(in: tagStart..<ciphertext.count)
        let box = try! AES.GCM.SealedBox(nonce: nonce, ciphertext: body, tag: tag)
        return try! AES.GCM.open(box, using: k, authenticating: aad)
    }

    // ── Ed25519 → X25519 derivation (O91) ───────────────────────────────

    /// Convert a 32-byte Ed25519 private seed (what
    /// `Curve25519.Signing.PrivateKey.rawRepresentation` returns) into the
    /// 32-byte X25519 private key Rumor needs for DM agreement. Matches the
    /// JVM derivation: SHA-512 the seed, take the low 32 bytes, X25519-clamp
    /// per RFC 7748 §5. CryptoKit does not expose this conversion directly —
    /// it's two CryptoKit-friendly steps (Insecure SHA-512 then byte masking):
    ///
    ///   var x = Data(Insecure.SHA512.hash(data: ed25519Seed).prefix(32))
    ///   x[0]  &= 0xF8
    ///   x[31] &= 0x7F
    ///   x[31] |= 0x40
    ///   return x
    ///
    /// Golden vector pinned by `Ed25519ToX25519ConversionTest` on JVM.
    @objc public static func ed25519ToX25519PrivateSeed(_ ed25519Seed: Data) -> Data {
        var x = Data(Insecure.SHA512.hash(data: ed25519Seed).prefix(32))
        x[0]  &= 0xF8
        x[31] &= 0x7F
        x[31] |= 0x40
        return x
    }

    /// Convert a 32-byte Ed25519 public key into the 32-byte X25519 public
    /// key via the Edwards-to-Montgomery birational map `u = (1 + y) / (1 - y) mod p`
    /// where `p = 2^255 - 19`. CryptoKit does NOT expose a primitive for this
    /// either — implement with arbitrary-precision integers (e.g. the
    /// `BigInt` package) and match the JVM `Ed25519ToX25519.ed25519PubToX25519Pub`
    /// math byte-for-byte. The JVM implementation in
    /// `core/src/jvmMain/.../crypto/Ed25519ToX25519.kt` is the source of
    /// truth; replicate the masking-of-byte-31-sign-bit and the modular
    /// inverse over (1-y).
    @objc public static func ed25519ToX25519Public(_ ed25519Pub: Data) -> Data {
        // TODO: implement via a BigInt dependency. See Ed25519ToX25519.kt
        // for the exact arithmetic.
        fatalError("ed25519ToX25519Public not yet implemented on iOS")
    }
}
```

## Kotlin side update

Once the bridge is in the framework export, the iosMain
`PlatformCrypto.kt` stubs get replaced with cinterop calls:

```kotlin
import platform.darwin.RumorCryptoBridge  // package depends on framework name

actual fun ed25519GenerateKeyPair(): Pair<ByteArray, ByteArray> {
    val arr = RumorCryptoBridge.ed25519GenerateKeyPair() as NSArray
    return arr[0].toByteArray() to arr[1].toByteArray()
}
// ...and so on for the other six
```

The `NSData ↔ ByteArray` glue is small (~10 lines) and lives in the same file.

## Validation gate

`core/src/commonTest/.../CryptoGoldenVectorsTest.kt` already pins the JVM
byte outputs for every deterministic primitive. **The bridge is "done" when
every golden vector passes on iOS targets.** Specifically:

- `sha256 of known input` — CommonCrypto path, already validated
- `sha256 of empty input` — same
- `pbkdf2 produces stable output for fixed inputs` — CommonCrypto path
- `x25519 KDF wrapper is stable` — composes PlatformCrypto.pbkdf2 over the
  X25519 raw output; depends on the bridge for the raw output to match
- `ed25519 deterministic-sign with known key` — pure bridge dependency
- `x25519 agreement with known keys` — pure bridge dependency
- `aes256gcm with known key and IV` — pure bridge dependency
- `ed25519ToX25519 round-trip on real identities` (O91 wired-fix property —
  the test exists in `Ed25519AsX25519RoundtripTest.kt`; on iOS it requires
  both bridge derivation helpers to be implemented byte-for-byte against
  the JVM math). If this fails, real Rumor users cannot DM each other on
  iOS — same failure mode that O91 closed on JVM.

If any of those FAILS on iOS with the bridge wired up, the bridge has a bug
(byte-order, encoding, parameter mismatch) and the iOS impl is NOT
byte-compatible with Android until fixed. **Do not ship until they all
pass** — silent DM-decryption failure is the alternative.

## Failure modes to watch for

1. **CryptoKit returns big-endian numbers, BouncyCastle uses little-endian
   (or vice versa) on internal field representations.** Both expose the
   "raw" key as 32 bytes; they should agree on layout for Curve25519 (it's
   defined by RFC 7748). Confirm with the X25519 agreement vector.
2. **AES-GCM tag handling differs.** JVM's `Cipher` produces `body || tag`
   appended (when `Cipher.doFinal()` is called); CryptoKit returns them as
   separate fields. The bridge spec above appends them in the Swift side to
   match the JVM convention. If the test fails on a tag-length mismatch,
   that's the symptom.
3. **NSArray boxing of Data into Kotlin/Native.** `NSArray[Int]` returns
   `Any?`; cast carefully. May need a `NSData.toByteArray()` extension
   written in `iosMain`.
4. **Force-unwraps (`try!`) in the Swift bridge.** Production-grade should
   throw a documented error type instead of crashing. Bridge crashes on
   malformed input means the Kotlin side never sees an error — it just
   terminates the iOS app. Convert to `do/catch` once the happy path
   works.

## Acquisition trigger

Per `IOS_PORT_PLAN.md` § Hardware acquisition triggers — this bridge work
is the first concrete task that benefits from at least an xtool setup on
Linux + an iPhone for runtime test. Pre-Mac it can be written and unit-
checked via the golden-vector test on iOS Simulator on a GitHub Actions
macos-latest runner. Pre-iPhone you have CI-only validation; with a phone
you can sideload and run the assertion suite on real hardware.
