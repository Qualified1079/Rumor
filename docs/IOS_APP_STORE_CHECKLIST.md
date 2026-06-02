# iOS App Store prerequisites — Rumor

Standalone checklist. Reference without dragging in the full port plan. For architectural context see `docs/IOS_PORT_PLAN.md`.

## 1. Developer account

- [ ] Apple Developer Program membership ($99/yr). Individual or organisation (org needs D-U-N-S).
- [ ] Bundle identifier reserved (e.g. `com.rumor.mesh`).
- [ ] App Store Connect record created.

## 2. Info.plist — required entries

- [ ] `NSBluetoothAlwaysUsageDescription` — user-facing string. Auto-rejection without it.
- [ ] `NSLocalNetworkUsageDescription` + `NSBonjourServices` — if any Bonjour/Network.framework use.
- [ ] `ITSAppUsesNonExemptEncryption = NO` (claims open-source/standard-crypto exemption — valid for Ed25519/X25519/AES-GCM).
- [ ] `UIBackgroundModes`:
  - `bluetooth-central` (mandatory for a mesh client)
  - `bluetooth-peripheral` (if advertising)
  - `processing` (for BGTaskScheduler jobs)
  - **Do not** request `voip` or `audio` — historical abuse, rejection vector.

## 3. Entitlements / capabilities

- [ ] Background Modes capability enabled in Xcode.
- [ ] Push Notifications (optional — only if using silent push for wake).
- [ ] App Groups (only if widgets / extensions share storage).
- [ ] Secure Enclave needs **no** entitlement — CryptoKit access is implicit.

## 4. Privacy

- [ ] `PrivacyInfo.xcprivacy` (mandatory since Spring 2024). Declare required-reason APIs (UserDefaults, file timestamps, disk space, system boot time) with standard reason codes.
- [ ] Privacy Nutrition Label in App Store Connect: declare "Data Not Collected." This is a marketing strength — call it out.
- [ ] No App Tracking Transparency prompt — skip entirely; don't link AdSupport.
- [ ] If contacts ever imported: `NSContactsUsageDescription` with a clear purpose string.

## 5. Review Guideline alignment

- [ ] **2.5.2 (no runtime code download).** Compile bridges into the bundle. No DEX/native-code download path. Constrained JavaScriptCore DSL is the only future-extension path that passes review; full plugin loader is AltStore-PAL territory only.
- [ ] **4.2 (minimum functionality).** Mesh messenger easily clears.
- [ ] **5.1.1 / 5.1.2 (Data Collection & Use).** Nothing collected, no third-party SDKs phoning home.
- [ ] **1.2 (User-Generated Content).** Required mechanisms: report a message, block a user, EULA, commitment to act on reports. Rumor already has block/blocklist machinery — surface it in the UI. No global broadcast room (O36 decision) substantially reduces UGC review burden.
- [ ] **5.0 (Legal).** App-Store copy framed as resilience / disaster-recovery / off-grid comms. Avoid politically-loaded or anti-government framing in any reviewer-visible material — moves review to a stricter desk. Bridgefy and Berty both pass with the resilience framing.

## 6. Encryption export compliance (U.S. BIS, separate from Apple)

- [ ] One-time notification email to `crypt@bis.doc.gov` and `web_site@bis.doc.gov` with source URL and crypto description (standard self-classification for open-source mass-market crypto).
- [ ] Annual self-classification report each February.
- [ ] Required regardless of distribution channel (App Store or AltStore PAL).

## 7. Reviewer-experience traps specific to mesh apps

- [ ] **Empty-room problem.** A reviewer in Cupertino with no nearby Rumor nodes sees an "empty" app. Mitigations: (a) reviewer-notes section in App Store Connect explains the offline-mesh model; (b) ship a debug/demo mode that simulates a peer when toggled via a reviewer-only flag.
- [ ] **Permission prompt timing.** BLE prompt must fire on explicit user action (e.g. "Enable mesh" button), not at first launch.
- [ ] **Background-mode justification.** Reviewers occasionally reject apps that declare `bluetooth-central` background but don't visibly use it. Make the background relay observable (notification, status badge, settings screen showing recent peer activity).

## 8. App-Store-blocked features (push to AltStore PAL if needed)

These cannot ship on App Store and require the EU/Japan AltStore PAL flavour:
- Runtime plugin loading beyond a JavaScriptCore-confined DSL.
- Any code-download or bundle-download path (`.rumor` bundles per O15/O26).
- "Install another app from this app" flows.

## 9. AltStore PAL (EU + Japan) — additional prereqs

- [ ] Apple Developer Program membership (same one).
- [ ] App notarized by Apple (malware scan only, not Guideline review).
- [ ] Acceptance of EU alternative-terms business agreement.
- [ ] Be aware: notarization is revocable; revoked notarization disables installed copies.
- [ ] Core Technology Fee (CTF) — currently waived under 1M first-installs/year per app; track threshold if usage grows.

## 10. Documentation deltas owed

- [ ] README: explicit statement that iOS is a part-time relay (background limits), distribution available via App Store everywhere + AltStore PAL in EU/Japan.
- [ ] CLAUDE.md: new `O63` row capturing the iOS sustaining-compatibility rules (keep `:core` Kotlin-only; CoreBluetooth in Swift; CryptoKit `actual`s wire-byte-identical with BouncyCastle).
