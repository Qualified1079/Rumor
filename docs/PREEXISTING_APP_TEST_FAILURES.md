# Pre-existing `:app:testDebugUnitTest` failures uncovered by JUnit 5 engine fix

Running `:app:testDebugUnitTest` was previously failing immediately with "Cannot create Launcher without at least one TestEngine" because `android.testOptions { unitTests.all { it.useJUnitPlatform() } }` is enabled but `app/build.gradle.kts` didn't declare a JUnit 5 engine as a runtime dependency. Adding:

```kotlin
testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
```

made tests actually run. **Result: 96 tests now execute; 7 fail.** These are pre-existing failures that were dormant under the broken config — not caused by this session's work. Listed here so they don't get lost.

The Phase 1b moves, Phase 1c shims, O64 UI fixes, and CLAUDE.md edits in this session are unaffected: `:core:jvmTest` is green across all of them, and `:simulator:test` is green, and `:app:compileDebugKotlin` is green.

## The 7 failures

### 1. ~~`DmEnvelopeRegistryTest` — 3 failures~~ **FIXED**

```
java.lang.IllegalArgumentException: DmEnvelope.envelopeId must match
  ^[A-Za-z0-9_.-]+$ (got 'env-meshtastic:')
```

Test fixture used an envelopeId with a trailing colon (`env-meshtastic:`); commit `8019739` "security: harden DmEnvelope framework from red-team review" added validation that rejects colons. **Fixed by updating the `makeEnvelope` helper default to strip the colon: `id = "env-${prefix.trimEnd(':')}"`.** All three test methods now pass.

### 2. ~~`SchedulerTest.drr shares bandwidth fairly across two senders`~~ **FIXED**

```
java.lang.AssertionError: all bob messages drained expected:<5> but was:<0>
```

**Real production DRR bug.** Bob's messages were ~60256 bytes (60000 char content + 256 envelope overhead); the quantum is 60000 bytes. **And** Bob's payload size exceeded the REALTIME ceiling (16 KB), so trafficClass demoted Bob's messages to BULK. Alice stayed in REALTIME with small messages.

In the BULK class, Bob was the only flow. On round 1 of `drain()`, Bob's deficit (0+60000) couldn't cover his head message (60256). `progress=false`, so the outer `while (progress && ...)` exited. Bob drained 0.

Classic DRR needs multiple rounds for messages that exceed the quantum, but the loop bailed after one progress-free round.

**Fix:** loop while *any queue is non-empty*, not while *progress was made*. Cap progress-free rounds at 32 (sane bound that lets messages up to 32×quantum drain while preventing infinite loops on pathological inputs).

### 3. ~~`NeighborStoreTest.selectDiverse prefers low-overlap peers`~~ **FIXED**

```
org.junit.ComparisonFailure: expected:<[low]> but was:<[high]>
```

Real bug: `(limit * 0.8).toInt()` truncated, so with `limit=1` we got `coverageCount=0` and the whole single pick became exploration (random). Fixed by switching to `kotlin.math.ceil(limit * 0.8).toInt().coerceAtMost(limit)` so the 80%/20% split rounds in favor of the documented "prefer low-overlap" intent at small limits.

### 4. ~~`ChunkerTest.reassemble returns null when chunk index gap exists`~~ **FIXED**

```
java.lang.AssertionError: expected null, but was:<[B@c2e3264>
```

Test fixture used `ByteArray(180_000) { 1 }` — every byte was 0x01, so every chunk was byte-identical. The test then replaced chunk 1 with chunk 0's data expecting the hash check to catch it, but the reassembled data was bit-identical so the hash matched. Fixed by changing to `ByteArray(180_000) { it.toByte() }` so chunks differ.

(The test's name says "chunk index gap" but the body actually tests "wrong data at right index" — not a literal gap. Name and body diverged; the body is what the test really exercises. Left the name unchanged.)

### 5. `AppModuleTest.appModule resolves all bindings` — PARTIALLY ADDRESSED

```
org.koin.test.verify.MissingKoinDefinitionException: Missing definition
  type 'com.rumor.mesh.data.TransferDao' in definition
  '[Factory:'com.rumor.mesh.ui.transfers.TransfersViewModel']'
```

**Cause is a broader DI-typing inconsistency surfacing now that tests run.** Walking the failures revealed the underlying issue:

1. **Direct DAO consumption by ViewModels.** `TransfersViewModel` takes `TransferDao` and `ChunkDao` directly. **Fixed in this session** by registering `single { get<RumorDatabase>().*Dao() }` for every DAO, including all the others (MessageDao, ContactDao, etc.) that adapters consume inline. This addresses the immediate "ViewModel needs a DAO" case.

2. **Adapters not registered as their interface types.** `single { BlockEntryRepositoryAdapter(...) }` registers the adapter as the concrete class, not as `BlockEntryRepository`. But `BlocklistPublisher`'s constructor signature takes `BlockEntryRepository`, which `Koin verify` can't resolve from a concrete-typed `single`. **NOT fixed in this session** — fixing it correctly requires either widening the registrations (e.g. `single<BlockEntryRepository> { BlockEntryRepositoryAdapter(...) }`) AND updating downstream `get<BlockEntryRepositoryAdapter>()` calls to use the interface, or keeping the current concrete-pinned registrations and changing constructors to take the adapter type. Both touch multiple files and warrant a focused DI refactor.

Three sibling adapters need the same treatment: `BlockEntryRepositoryAdapter`, `SubscribedBlocklistRepositoryAdapter`, `BlocklistEntryRepositoryAdapter`. Adopting interface-typed registration would also align with how `MessageRepositoryAdapter` / `ContactRepositoryAdapter` are already registered (`single<MessageRepository> { MessageRepositoryAdapter(...) }`).

## Final state

**6 of 7 failures fixed.** Only the AppModuleTest DI typing issue remains (#5 above) — touches multiple files and warrants design review on the interface-typed vs concrete-typed `single` registration choice.

The CI workflow at `.github/workflows/ci.yml` runs `:app:testDebugUnitTest` per CLAUDE.md G7. **The CI was silently passing this task before because the broken JUnit 5 config produced a "no tests" pass, not a fail.** After this session, 95 tests pass (96 ran; 1 still fails on the AppModule DI issue).

**To get CI clean again,** either:
1. Land the Block-adapter interface-widening refactor (3 `single<BlockEntryRepository> { ... }` widenings + downstream `get<*Repository>()` rewrites). Real but bounded.
2. Add a Koin `verify(injections = ...)` exception for those three types (works around without fixing).

Lean: option 1, with the refactor as a focused follow-up. The other 6 fixes from this session land cleanly.
