# Phase 1c shim surface — `expect/actual` declarations needed

Companion to `IOS_PORT_PHASE_1_HANDOFF.md`. When Phase 1b moves the 41 pure-Kotlin files from `jvmMain/` to `commonMain/`, the remaining 13 files must stay in `jvmMain/` until each `java.*` import they hold has a matching `expect` declaration in `commonMain/` and an `actual` in `jvmMain/` (and later `iosMain/`, `linuxMain/`, etc.).

This document sketches the `expect/actual` surface. **No code yet** — when Phase 1b is green the moves below get implemented.

---

## Inventory: 13 files × 9 java types

| File | Needs |
|---|---|
| `core/scheduling/MessageScheduler.kt` | `UUID` |
| `core/routing/BreadcrumbCache.kt` | `ConcurrentHashMap` |
| `core/routing/NeighborStore.kt` | `ConcurrentHashMap` |
| `core/protocol/GossipEngine.kt` | `UUID`, `ConcurrentHashMap`, `AtomicLong` |
| `core/protocol/MessageStore.kt` | `AtomicLong` |
| `core/protocol/CanaryMetrics.kt` | `AtomicLong` |
| `core/logging/RumorLog.kt` | `ArrayDeque`, `ReentrantReadWriteLock` |
| `core/crypto/CryptoManager.kt` | `MessageDigest`, `SecureRandom`, `Base64`, `Cipher`, `SecretKeyFactory`, `GCMParameterSpec`, `PBEKeySpec`, `SecretKeySpec` |
| `core/sync/Rbsr.kt` | `MessageDigest` |
| `core/transfer/Chunker.kt` | `MessageDigest`, `Base64`, `UUID` |
| `core/transfer/TransferSender.kt` | `Base64` |
| `core/transfer/TransferAssembler.kt` | `Base64`, `ConcurrentHashMap` |
| `core/transport/wifidirect/BloomFilterData.kt` | `Base64` |

Demand ranking (most-needed shims first):
1. **`Base64`** — 5 sites (CryptoManager, Chunker, TransferSender, TransferAssembler, BloomFilterData)
2. **`ConcurrentHashMap`** — 4 sites
3. **`MessageDigest`** (SHA-256 primarily) — 3 sites
4. **`UUID.randomUUID()`** — 3 sites
5. **`AtomicLong`** — 3 sites
6. **`SecureRandom`** — 1 site (CryptoManager)
7. **`ArrayDeque`** — 1 site (RumorLog) — **NOT a shim**, migrate to `kotlin.collections.ArrayDeque` (available since Kotlin 1.4, identical API for our usage)
8. **`ReentrantReadWriteLock`** — 1 site (RumorLog) — see below
9. **`javax.crypto.*`** (Cipher / KeyFactory / spec classes) — CryptoManager only

---

## Proposed `expect` declarations

All live under `core/src/commonMain/kotlin/com/rumor/mesh/core/platform/`. One file per concern.

### `Base64.kt` (highest value, 5 callers)

```kotlin
package com.rumor.mesh.core.platform

expect object Base64Codec {
    fun encode(bytes: ByteArray): String
    fun decode(s: String): ByteArray
}
```

- **JVM actual**: `java.util.Base64.getEncoder().encodeToString` / `getDecoder().decode`.
- **iOS actual**: `Foundation.NSData.base64EncodedString()` / `NSData(base64EncodedString:)`. Or use Multiplatform `kotlinx-io` / `okio` Base64 once available — check the dependency cost.
- **Note**: our existing `CryptoManager.toBase64()` / `fromBase64()` extension functions stay; they just delegate to `Base64Codec` after this shim.

### `Concurrent.kt` (4 callers)

```kotlin
package com.rumor.mesh.core.platform

expect class ConcurrentMap<K : Any, V : Any>() {
    fun get(key: K): V?
    fun put(key: K, value: V): V?
    fun remove(key: K): V?
    val keys: Set<K>
    val values: Collection<V>
    val size: Int
    fun clear()
    fun putIfAbsent(key: K, value: V): V?
    fun compute(key: K, remappingFunction: (K, V?) -> V?): V?
    // exact surface to be confirmed by reading each call site
}
```

- **JVM actual**: typealias to `java.util.concurrent.ConcurrentHashMap`.
- **iOS actual**: `NSLock`-guarded `MutableMap`, or `atomicfu` library, or `kotlinx.collections.immutable` once it ships native primitives. **Decision**: use `atomicfu` library — it's the Kotlin team's recommended cross-platform concurrent primitive.

### `Hash.kt` (3 callers — Rbsr, Chunker, CryptoManager all use SHA-256)

```kotlin
package com.rumor.mesh.core.platform

expect object Sha256 {
    fun digest(bytes: ByteArray): ByteArray  // 32-byte result
}
```

- **JVM actual**: `java.security.MessageDigest.getInstance("SHA-256")`.
- **iOS actual**: `CryptoKit.SHA256.hash(data:)`.
- **No streaming variant needed** — current usage is one-shot.

### `Uuid.kt` (3 callers)

```kotlin
package com.rumor.mesh.core.platform

expect object Uuid {
    fun random(): String  // returns 128-bit random in 8-4-4-4-12 hex form
}
```

- **JVM actual**: `java.util.UUID.randomUUID().toString()`.
- **iOS actual**: `Foundation.NSUUID().uuidString.lowercased()` (NSUUID is uppercase by default; canonicalise).
- **Note**: this is the source of the deterministic-replay variance in soak scenarios (CLAUDE.md scenario 17 note). Fix is orthogonal to the platform shim.

### `AtomicLong.kt` (3 callers)

```kotlin
package com.rumor.mesh.core.platform

expect class AtomicCounter(initial: Long = 0L) {
    var value: Long
    fun incrementAndGet(): Long
    fun getAndIncrement(): Long
    fun addAndGet(delta: Long): Long
    fun compareAndSet(expected: Long, new: Long): Boolean
}
```

- **JVM actual**: wrap `java.util.concurrent.atomic.AtomicLong`.
- **iOS actual**: use `atomicfu` (recommended) or `NSLock`-guarded `Long`.

### `SecureRandom.kt` (1 caller, CryptoManager)

```kotlin
package com.rumor.mesh.core.platform

expect object PlatformRandom {
    fun nextBytes(buf: ByteArray)
    fun nextBytes(size: Int): ByteArray
}
```

- **JVM actual**: `java.security.SecureRandom()` (kernel-seeded).
- **iOS actual**: `SecRandomCopyBytes(kSecRandomDefault, ...)`.
- **Critical**: NEVER fall back to `kotlin.random.Random` for crypto. Both `SecureRandom` and `SecRandomCopyBytes` are CSPRNGs; `kotlin.random.Random` is not.

### `Logging.kt` (RumorLog only)

```kotlin
package com.rumor.mesh.core.platform

expect class RwLock() {
    fun <T> read(block: () -> T): T
    fun <T> write(block: () -> T): T
}
```

- **JVM actual**: wrap `java.util.concurrent.locks.ReentrantReadWriteLock`.
- **iOS actual**: wrap `os_unfair_lock` or `NSRecursiveLock`.

Also: replace `java.util.ArrayDeque` with `kotlin.collections.ArrayDeque` — straight rename, no shim. Same `addFirst` / `addLast` / `removeFirst` / `removeLast` / size semantics.

### `Aead.kt` and `Pbkdf.kt` (CryptoManager only — the chunky one)

CryptoManager is where the iOS-port work is real. Sketch:

```kotlin
package com.rumor.mesh.core.platform

/** AES-256-GCM, 12-byte nonce. */
expect object Aes256Gcm {
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, ad: ByteArray? = null): ByteArray
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, ad: ByteArray? = null): ByteArray?
}

/** PBKDF2-HMAC-SHA256. */
expect object Pbkdf2 {
    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBits: Int): ByteArray
}
```

- **JVM actual**: BouncyCastle (already on classpath) or platform `javax.crypto.Cipher` + `SecretKeyFactory`.
- **iOS actual**: `CryptoKit.AES.GCM.SealedBox` for AEAD; `CryptoKit.HKDF` plus a CommonCrypto PBKDF2 binding (CryptoKit doesn't expose PBKDF2 directly — falls back to `CCKeyDerivationPBKDF`).
- **Decision pending**: do we keep BouncyCastle in `commonMain` (it has multiplatform builds available now) or split per-platform? BouncyCastle's KMP story has improved since 2024; check the current state when implementing.

Ed25519 / X25519 are already in `CryptoManager` via BouncyCastle on JVM. For iOS:
- **Ed25519**: `CryptoKit.Curve25519.Signing.PrivateKey/PublicKey` natively.
- **X25519**: `CryptoKit.Curve25519.KeyAgreement.PrivateKey/PublicKey` natively.

Both are first-class on iOS; the shim layer is straightforward.

---

## Implementation order when Phase 1c starts

1. **`Base64Codec`** first — highest fan-in (5 sites), trivial actuals, no library deps. Touches CryptoManager, Chunker, TransferSender, TransferAssembler, BloomFilterData.
2. **`Sha256`** second — 3 sites, trivial, no deps.
3. **`Uuid`** third — 3 sites, trivial, no deps. Note: doesn't fix the deterministic-replay variance — that's a separate wire-format change to make message IDs deterministic-from-content.
4. **`ConcurrentMap`** fourth — 4 sites, BUT requires adding `atomicfu` dependency to common. Slightly heavier lift.
5. **`AtomicCounter`** fifth — same `atomicfu` dep; do these together.
6. **`PlatformRandom`** sixth — CryptoManager only, but blocks everything crypto.
7. **`RwLock` + `kotlin.collections.ArrayDeque` migration** seventh — RumorLog migration in one PR.
8. **`Aes256Gcm` + `Pbkdf2`** last — biggest single lift; needs the BouncyCastle decision first.

Between each batch: run `:core:jvmTest`. Commit per batch.

**Estimated total effort**: 1–2 days of focused work after Phase 1b. Most of the friction is decision-shaped (BouncyCastle vs split per-platform, atomicfu vs alternatives), not implementation-shaped.

---

## What this unblocks

- **O63 iOS port** Phase 1 complete after this — the `:core` module compiles for `iosX64`, `iosArm64`, `iosSimulatorArm64` targets.
- **O54 native TransportPlugin** — a Linux daemon written in Kotlin can target `linuxX64` once these shims have Linux actuals (mostly straight Java→native-Linux mappings).
- **O75 MCU relay** still needs a separate language (Rust/C); but the wire-format doc (committed) gives them what they need.

---

## Decisions deferred to the user

1. **atomicfu vs alternatives** for ConcurrentMap / AtomicCounter. `atomicfu` is the Kotlin team's recommendation but adds a dependency.
2. **BouncyCastle in commonMain vs per-platform** crypto. BC has KMP-y builds now but track record is shorter than the platform-native (CryptoKit on iOS, javax.crypto on JVM).
3. Whether to also add `linuxMain` / `mingwMain` targets in the same pass (for O54), or just `iosMain` (for O63) first. Lean toward iOS-first because Phase 1 plan documents say iOS-first; Linux is a smaller delta once iOS works.

None of these block Phase 1b mechanical moves. Phase 1b can proceed now; Phase 1c starts only after these decisions and after Phase 1b is green.
