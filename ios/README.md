# iOS native sources

Swift / Objective-C sources that compile into the (future) iOS app target,
NOT into `:core`. `:core` stays Kotlin-only and reaches these via the auto-
generated Objective-C interop once an Xcode project exists.

## Files

- **`RumorCryptoBridge.swift`** — `@objc` wrapper around CryptoKit's
  Ed25519 / X25519 / AES-GCM primitives. Required because CryptoKit is
  Swift-first and unreachable from Kotlin/Native cinterop. Contract spec:
  [`../docs/IOS_SWIFT_BRIDGE_SPEC.md`](../docs/IOS_SWIFT_BRIDGE_SPEC.md).
  Validation gate: every pinned vector in
  `core/src/commonTest/.../CryptoGoldenVectorsTest.kt` must reproduce
  byte-for-byte through the bridge before iOS ships.

## When an Xcode project lands

1. Create the iOS app target alongside `:core` (Kotlin Multiplatform iOS
   framework export).
2. Add every `.swift` here to the target's Compile Sources.
3. Replace the `NotImplementedError("iOS PlatformCrypto: Swift bridge not
   yet wired")` stubs in
   `core/src/iosMain/.../platform/PlatformCrypto.kt` with cinterop calls
   into `RumorCryptoBridge`.
4. Run `:core:iosTest` — the golden-vector suite is the acceptance test.

Per `docs/IOS_PORT_PLAN.md` hardware tiers: this work begins once xtool is
set up on Linux (CI-only validation) or a Mac mini is available (full
local iteration).
