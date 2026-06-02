# iOS port — Phase 1 handoff note

Picks up where the previous session stopped. For background see `docs/IOS_PORT_PLAN.md` (architecture + locked decisions) and `docs/IOS_APP_STORE_CHECKLIST.md` (reviewer prereqs).

## Where we are

All four gating decisions are locked (see IOS_PORT_PLAN.md "Decisions locked" section):
- No plugins on iOS for v1.
- No bridges (Meshtastic, MeshCore) on iOS for v1 — they stay first-class on Android, O63 capability negotiation handles the asymmetry.
- App Store only for v1; AltStore PAL deferred.
- Accept "part-time relay" framing.
- Compose Multiplatform for UI (not SwiftUI).

Build infra: dev builds via `xtool` (Linux-native iOS toolchain) for fast iteration; the App Store archive goes through Xcode Cloud (100 CPU-hours/month free with the developer account).

## What's done

- Branch: `claude/practical-johnson-hzNWF`. Most recent commit: `a58e42e`.
- Doc commits only so far. No source changes.

## What's next — Phase 1: KMP-restructure `:core`

Goal: convert `:core` from a JVM-only Gradle module to a Kotlin Multiplatform module while keeping `:app` and `:simulator` building and all tests green. No iOS source code yet — just the structural foundation.

Three sub-phases, each a separate commit:

### Phase 1a — structural shift only

Pure mechanical move; no source edits.

1. `git mv core/src/main core/src/jvmMain && git mv core/src/test core/src/jvmTest`
2. Edit `core/build.gradle.kts`: swap `id("org.jetbrains.kotlin.jvm")` → `id("org.jetbrains.kotlin.multiplatform")`, replace the flat `dependencies { }` block with a `kotlin { jvm { … }; sourceSets { commonMain { … }; commonTest { kotlin("test") }; jvmMain { BouncyCastle }; jvmTest { junit + kotest + jazzer } } }` block. Move `tasks.test { useJUnitPlatform() }` to `tasks.withType<Test>().configureEach { useJUnitPlatform() }`.
3. Edit root `build.gradle.kts`: add `id("org.jetbrains.kotlin.multiplatform") version "1.9.22" apply false`.
4. Verify: `./gradlew :core:compileKotlinJvm` then `./gradlew :core:jvmTest :simulator:test :app:assembleDebug`. Everything must pass with zero source edits.

**Network requirement:** the build needs `dl.google.com`, `repo.maven.apache.org`, `plugins.gradle.org`, `services.gradle.org`, and `*.jetbrains.com`. The previous session was sandboxed without `dl.google.com` and could not validate — do not push Phase 1a without a real `./gradlew` pass.

### Phase 1b — lift pure-Kotlin source into `commonMain`

Of the 53 `.kt` files in `:core`, 13 import from `java.*`:

```
java.security.MessageDigest
java.security.SecureRandom
java.util.ArrayDeque
java.util.Base64
java.util.UUID
java.util.concurrent.ConcurrentHashMap
java.util.concurrent.atomic.AtomicLong
java.util.concurrent.locks.ReentrantReadWriteLock
```

The other ~40 are pure Kotlin and can move from `jvmMain/` to `commonMain/` in batches. Move a directory at a time, run `:core:jvmTest` after each batch, commit when green.

### Phase 1c — `expect`/`actual` for JVM-coupled types

The 13 files above stay in `jvmMain` until their dependencies have a multiplatform answer:
- `MessageDigest`, `SecureRandom`, `Base64` → either a tiny `expect`/`actual` shim (cleanest) or pull in a small multiplatform crypto lib (`korlibs-crypto` or similar — evaluate at the time).
- `UUID` → `expect fun newRumorId(): String` returning hex, since Rumor only uses UUID for ID generation, not as a UUID type.
- `ConcurrentHashMap` → `kotlinx.atomicfu` or a Mutex-protected `MutableMap`.
- `AtomicLong` → `kotlinx.atomicfu.atomic`.
- `ArrayDeque` → standard Kotlin `ArrayDeque` works on common.
- `ReentrantReadWriteLock` → `kotlinx.coroutines.sync.Mutex` if the call site is suspending, else a small `expect`/`actual`.

`CryptoManager` is the keystone `expect class`. Its `jvmMain` `actual` keeps the BouncyCastle implementation byte-identical with what ships today. Phase 2 adds the `iosMain` `actual` using CryptoKit + CommonCrypto, gated by wire-format golden-vector tests in `commonTest`.

## Simulator runs still owed (from CLAUDE.md)

Independent of the iOS work; pick up whenever convenient.

1. **RBSR validation (O42 / G10 / G17).** Scenario 14 (`14-rbsr-partition-heal.json`) has an under-delivery finding noted in G17. Investigate with the `rbsrRoundsUsed` counter; compare against scenario 13 (bloom partition-heal) and 15/16 (line-200). Prerequisite for promoting `rbsr-v1` to `LOCAL_SUPPORTED_FEATURES` default-on. Also: verify bound-edge fingerprint stability against the `hoytech/negentropy` reference implementation.
2. **Determinism re-check (O12).** Re-run the deterministic-replay scenario across multiple seeds; confirm the ±5% bound on totalMessages and drops still holds. Escalation path documented in O12 if variance grew.
3. **Breadcrumb on/off regression (11 vs 12, 15 vs 16).** Re-run after every routing-layer change.
4. **NoBridgedRerelay under eviction (O11).** Not broken today (in-memory MessageRepository doesn't evict mid-run). If eviction lands, switch the assertion to the live `relayObserver` hook noted in the JSON-schema docstring.
5. **Heal-storm scenarios (O43).** Deferred — depends on O42 promotion + O32 per-peer byte accounting.

## Research flags — questions owed before specific items can ship

Each requires a focused web-research pass. Some are inline-noted in the relevant backlog row in CLAUDE.md; collected here so they don't get lost between sessions.

1. **On-device NSFW / gore image classifier model licensing** (relevant to the App Store filtering posture under O64/O65 and the LOCAL_ONLY plugin tier added in O24). Candidates: NudeNet, OpenNSFW-derived models, Google's Vision API on-device equivalents. Some popular models have non-commercial licenses that would block App Store distribution. Need: license audit + Core ML conversion path on iOS + TFLite/NNAPI conversion path on Android + binary-size impact.
2. **Apple reviewer expectations for report-log destination (Guideline 1.2)** — referenced in O65. Specifically: is a purely device-local report queue sufficient, or does App Store review require a destination endpoint (maintainer email, web form, etc.)? Affects whether O65 needs any networked component.
3. **Per-store review climate for P2P / mesh / E2E apps in 2026** — referenced in O71. Huawei AppGallery, Xiaomi GetApps, Samsung Galaxy Store, Amazon Appstore, Uptodown, APKPure, GetJar, SlideME, 1Mobile each have different policies; an empirical pass is needed before sinking effort into submissions.
4. **Chinese App Store (Apple China) + Tencent MyApp 2026 stance on uncensorable E2E mesh apps** — referenced in O71 as probably-infeasible. Confirm with primary sources whether any feature-stripped variant is permitted, or whether China distribution is a non-goal entirely.
5. **I2P-as-library Android maturity** — relevant to O54 (transport plugins, listing Tor / I2P / Nostr) and O69 (Nostr-relay fallback with Tor or I2P as the anonymising layer). Current state of `i2pd` vs `Java-I2P` for in-app use without root; comparison to `tor-android` (used by Briar) and Arti.
6. **iOS minimum-version sweet spot for CoreBluetooth StateRestoration + BGContinuedProcessingTask** — relevant to Phase 3 (BLE transport) and Phase 4 (background lifecycle). CryptoKit hard-floors at iOS 13; BGContinuedProcessingTask is iOS 26+. Question is what minimum deployment target balances reach against background-relay quality.

## When to update this file

Once Phase 1a is committed and verified, replace this file with a Phase 1b/c handoff (or delete it if Phase 1 is fully done). The next handoff doc should record what's actually in `commonMain` vs `jvmMain` so the iOS `actual`s know their target surface.
