# iOS port — planning notes (practical-johnson)

> Captured from a planning session so the context survives instance turnover.
> See also: `docs/IOS_APP_STORE_CHECKLIST.md` for the standalone reviewer-prereqs checklist.

## Context

Rumor today is Android-only. The core protocol (`:core` Gradle module) is already pure JVM/Kotlin with strict layering and no Android imports — by design, the project has been built with eventual portability in mind. CLAUDE.md's open backlog explicitly tracks the long-term-collapse threat model (O55) which expects heterogeneous hardware including non-Android phones; an iOS port closes the most-requested platform gap.

Before any code is written, two things have to be settled:

1. **What does iOS actually allow?** iOS has no Wi-Fi Direct equivalent, restricts BLE peripheral background advertising, gates plugin-style runtime code loading under Guideline 2.5.2, and adjudicates mesh / P2P / encrypted-comms apps inconsistently. Several of Rumor's headline features (Wi-Fi Direct bulk transport, runtime-loaded `.rumor` plugin bundles per O15/O24/O26, F-Droid-style sideloading) may not survive the App Store review bar. The architecture has to be designed around the constraints, not against them.
2. **How is code shared?** Reimplementing the protocol in Swift would split the wire-format-critical surface across two languages and double the maintenance burden for every protocol change on the open backlog (O38, O42 promotion, O53, O5 envelope additions). The cheaper-and-safer answer is Kotlin Multiplatform reusing `:core` as-is.

This note covers the research pass and architecture sketch. **It does not commit to implementation.** A separate plan, written after the user decides the four gating questions at the end, will phase the actual port.

## Phase 1 — Research (read-only, web) — DONE

A research agent produced a ~1500-word cited report covering iOS transport options, App Store review climate, background execution limits, distribution paths, crypto export compliance, runtime-plugin feasibility under 2.5.2, and KMP iOS maturity in 2026. Key findings folded into the architecture sketch below; full report not duplicated here (lives in conversation transcript).

## Architecture sketch

### Code-sharing decision: Kotlin Multiplatform

Confirmed. `:core` is verified Android-free (zero `import android.*` / `import androidx.*`). `:simulator` already proves it runs without Android. KMP is stable on iOS since Nov 2023, Compose-Multiplatform-iOS stable since May 2025. Production users at scale (Cash App, Netflix, McDonald's) de-risk it. Keep `:core` as the shared module; new `iosMain` source set holds `actual` implementations of the small set of `expect` declarations that need platform crypto/storage/transport.

Concretely:
- `:core` → KMP module with `commonMain` (existing JVM code, minor `expect`/`actual` refactor for `CryptoManager` and any `java.util.*` leakage), `androidMain` (JVM `actual`s wrapping BouncyCastle), `iosMain` (Swift-bridged `actual`s wrapping CryptoKit/CommonCrypto).
- `:simulator` stays JVM-only and keeps using the `androidMain`/JVM crypto path — it's the regression catch for `:core` purity.
- A new `:ios-app` Xcode project (not Gradle) consumes the `:core` XCFramework via `embedAndSignAppleFrameworkForXcode` or SPM. SwiftUI for UI. Swift for CoreBluetooth and lifecycle.

### Transport substitution

| Android touchpoint | iOS substitution | Notes |
|---|---|---|
| Wi-Fi Direct (`WifiDirectTransport`, `GossipSession` over TCP) | **None on iOS↔Android.** MultipeerConnectivity for iOS↔iOS legs only. | Wi-Fi Direct has no iOS equivalent. Apple does not interop. Bulk transport between iOS and Android phones is BLE-only — multi-MB/s drops to ~30–70 kB/s over `CBL2CAPChannel`. |
| `BleDiscoveryManager` (advertise + scan service UUID) | `CBPeripheralManager` advertise + `CBCentralManager` scan | When backgrounded, iOS moves the UUID into the overflow area (Apple manufacturer-data 0x4c00 0x01 + 128-bit bitmask). Android Rumor scanners must scan unfiltered and software-match the bit. Already-shipping Android scan loop needs an extra match rule. Reverse-engineered, not API-stable — flag as living risk. |
| `BluetoothGatt` GATT client (Meshtastic/MeshCore radios) | `CBPeripheral` GATT client | Mechanical port. Same characteristic-read/write/notify model. Delegate ergonomics nicer in Swift than via K/N ObjC interop — keep CoreBluetooth in Swift. |
| Foreground service (`MeshService`) keeping relay alive | **No equivalent.** `bluetooth-central` wake + BGTaskScheduler + (new in iOS 26) `BGContinuedProcessingTask` for user-initiated transfers. | iOS Rumor is a **part-time relay**, minutes-to-hours backgrounded, never an "always-on infrastructure node." This is the Briar conclusion and it holds in 2026. Real always-on infrastructure stays on Android phones, plug-power SBCs, or transport-plugin hardware (O54). Document explicitly. |
| `BroadcastReceiver` (plug state, screen on/off for O57 modes) | `UIDevice.batteryStateDidChangeNotification`, `UIApplication.willResignActiveNotification`, etc. | One-to-one mapping. iOS may not get the full Free-mode behaviour (background limits cap it) — Free-mode auto-trigger on iOS becomes Static-equivalent at best. |
| `WifiDirectBroadcastReceiver` | n/a (no Wi-Fi Direct) | Delete from iOS path entirely. |

### Crypto layer

CryptoKit covers Ed25519 (`Curve25519.Signing`), X25519 (`Curve25519.KeyAgreement`), AES-256-GCM (`AES.GCM`). No exotic curves or KDFs in the BouncyCastle path. PBKDF2 not in CryptoKit — use `CommonCrypto.CCKeyDerivationPBKDF`. Refactor `CryptoManager` to `expect`/`actual`; iOS `actual` is ~150 lines of CryptoKit + a thin CommonCrypto bridge.

Bonus: `O20`/`O44` Keystore-wrapping-key plan maps directly onto Secure Enclave + Keychain on iOS — a strict improvement over Android's mixed StrongBox/software fallback. iOS users get hardware-backed identity by default on every device shipped since 2016.

### UI

SwiftUI native, not Compose-Multiplatform. Compose-iOS is stable but UI is the cheapest layer to duplicate, the most platform-flavoured, and the most likely to be reviewer-scrutinised. SwiftUI gives native navigation, accessibility, Dynamic Type, Live Activities, control-center integration for free. ViewModels in Swift consume `Flow<…>` from `:core` via KMP-NativeCoroutines (`async/await` + `Combine`).

### Plugin track on iOS — product decision required

The Android plugin roadmap (O15 DEX loader, O24 capability tiers, O26 mesh-distributed plugins) **does not fit App Store iOS**. Guideline 2.5.2 is unambiguous about downloading executable code; March-2026 vibe-coding pulls show the bar has not relaxed. Three options, all with real costs:

1. **No plugins on iOS.** Bridges (Meshtastic, MeshCore) are compiled into the app bundle. Adding a new bridge requires an App Store update. Simplest, ships fastest, matches Briar's posture.
2. **JavaScriptCore DSL plugins on App Store.** Constrained `PluginContext` (observe-incoming, display filters, scheduled-message compose templates) exposed to signed JS payloads. Probably passes review if framed as content/configuration. Cannot do anything that touches the wire or registers a `DmEnvelope` — those need to stay native and compile-time-registered. This is a meaningful new code path with its own threat model.
3. **Full plugin loader on AltStore PAL only.** EU + Japan distribution gets the Android-equivalent plugin track via notarization (which checks malware, not 2.5.2). App Store build ships option (1) or (2); AltStore PAL build ships option (3). Two iOS build flavours.

Recommendation in the implementation plan (when written): **start with option 1**, ship the bridges in-bundle, defer the JS DSL and AltStore variant until there's a real user with a real plugin to write. This matches Rumor's "don't build for hypothetical futures" rule.

### Distribution path

**App Store + AltStore PAL (EU + Japan).** No realistic third channel for a small OSS project. TestFlight 90-day cap rules it out as a primary path. Enterprise program is gated behind >100-employee + financial review. Outside EU/Japan there is no F-Droid equivalent — non-App-Store users have no install path. Document this gap honestly in the README; do not pretend iOS distribution matches Android's.

Crypto compliance: standard self-classification for Ed25519/X25519/AES-GCM as mass-market open-source, annual notification to BIS (`crypt@bis.doc.gov`, `web_site@bis.doc.gov`), `ITSAppUsesNonExemptEncryption` in Info.plist set per Apple's published flow. No special handling required.

### Threat-model deltas vs Android (CLAUDE.md updates owed)

When the architecture is approved, add an `O63` entry to the open items table summarising:

- **Stronger on iOS:** Secure-Enclave-backed identity by default (improves O20/O44 trajectory). FLAG_SECURE-equivalent (`isScreenCaptured` + asset-protection class) is built into the platform. App-sandbox containment is stronger than Android's by default.
- **Weaker on iOS:** Always-on relay (Android FGS) — iOS gets minutes-to-hours of background, not days. Plugin loader (O15/O24/O26) — App Store can only host a constrained subset. Sideloading (F-Droid analogue) — only EU/Japan via AltStore PAL.
- **Different but not worse:** BLE-only Android↔iOS interop bottlenecks bulk transfers but does not change the protocol or security posture.
- **New invariants for iOS sustaining-compatibility (O56-style):** keep `:core` Kotlin-only forever (no Apple SDK imports leaking in); CoreBluetooth code lives only in Swift; `expect`/`actual` boundary surface stays small and audited; CryptoKit `actual`s must be byte-identical with BouncyCastle on the wire (golden-vector tests in shared `commonTest`).

## Verification gate before implementation

User decides:
1. **Plugin track:** option 1, 2, or 3 above?
2. **Distribution:** App Store + AltStore PAL pairing acceptable, or App Store only?
3. **Background-relay framing:** are we OK with the README saying "iOS Rumor is a part-time relay, real infrastructure lives elsewhere"?
4. **UI:** SwiftUI native (recommended), or Compose-Multiplatform-iOS (untried at this scale)?

After answers, write a second plan file naming concrete `expect`/`actual` boundaries, the new `:ios-app` Xcode project layout, and the phase order (crypto `actual` first → wire-format golden tests → BLE transport → bridge ports → SwiftUI → distribution mechanics).
