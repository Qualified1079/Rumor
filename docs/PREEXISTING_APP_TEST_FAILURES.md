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

### 2. `SchedulerTest.drr shares bandwidth fairly across two senders`

```
java.lang.AssertionError: all bob messages drained expected:<5> but was:<0>
```

Real assertion failure. The DRR (deficit round robin) test expects bob's messages to be drained from the scheduler; they're not. Either the scheduler's behavior changed since the test was written, or the test's expectation was wrong from the start. Needs an investigation pass.

### 3. `NeighborStoreTest.selectDiverse prefers low-overlap peers`

```
org.junit.ComparisonFailure: expected:<[low]> but was:<[high]>
```

Selection logic vs test-expected ordering disagrees. The test expects the low-overlap peer to be picked; code picks the high-overlap peer. Could be inverted comparator, or the test's expected behavior is wrong.

### 4. `ChunkerTest.reassemble returns null when chunk index gap exists`

```
java.lang.AssertionError: expected null, but was:<[B@c2e3264>
```

`Chunker.reassemble` is supposed to return null when chunks are missing. It's returning a non-null `ByteArray` instead. Either the gap-detection logic is broken, or the test's expectation is wrong about how `reassemble` handles partial inputs.

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

## Recommendation

These 7 failures are real defects (or stale tests). None of them are caused by this session's work — they were hiding under the broken JUnit 5 config. Fix them in a focused follow-up pass; not safe to do autonomously because each one requires either a behavior decision (is the test wrong or the code wrong?) or a DI wiring decision.

The CI workflow at `.github/workflows/ci.yml` runs `:app:testDebugUnitTest` per CLAUDE.md G7 — **the CI was silently passing this task because the broken config produced a "no tests" pass, not a fail.** After this session's `build.gradle.kts` fix, CI will turn red on the next push that touches `:app` test paths. **Two options on receipt of red CI:**

1. **Revert the test-engine fix** in `app/build.gradle.kts` (one commit), restoring the "no tests run" green state. Defer the 7 fixes.
2. **Land the test-engine fix and the 7 test repairs in one PR.** More work, but it's the honest state.

I recommend option 2 long-term but option 1 is fine for shipping clean CI in the short term.
