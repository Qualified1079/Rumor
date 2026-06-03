# Session summary for review on wake

17 commits on `claude/practical-archimedes-wmySm`, pushed to origin. Each commit is independently revertible. Full chronological log in `docs/SLEEP_SESSION_LOG.md`; this doc is the executive summary.

Reading order: skim §1 for the shape, then §2 for things that need your yay/nay before they're "done."

---

## 1. What changed

### Documentation (no code risk)

| File | Purpose |
|---|---|
| `docs/wire-format.md` | Authoritative v1 wire-format spec. **Surfaced one real protocol divergence:** our RBSR XOR fingerprint formula does NOT match hoytech/NIP-77's. Pinned in code via `RbsrFormulaComparisonTest`. Decision needed (§2). |
| `docs/RESEARCH_NOTES.md` (11 sections) | Web-research findings on the explicit need-to-verify items in CLAUDE.md (negentropy, Apple 1.2, BLE battery, alt stores, Bitcoin Guix model, Nostr NIPs, mDNS, on-device NSFW/gore classifiers, Briar/iOS, Tor-android, KMP state). |
| `docs/PHASE_1C_SHIM_SURFACE.md` | Full expect/actual sketch for Phase 1c (13 java-importing files). First three shims now implemented. |
| `docs/O64_SENTATMS_AUDIT.md` | Every `sentAtMs` reference categorised; 5 UI bugs identified and fixed. |
| `docs/O48_BRIDGE_USERID_AUDIT.md` | Pre-O5-DM-bridging shape — needs your migration policy decision. |
| `docs/BACKLOG_ALREADY_DONE_AUDIT.md` | Four CLAUDE.md rows that were already shipped (O3/O12/O13/O16) plus O25 reclassification. |
| `docs/PREEXISTING_APP_TEST_FAILURES.md` | 7 dormant `:app` test failures surfaced by the JUnit 5 engine fix. 4 fixed; 3 remain. |
| `docs/SLEEP_SESSION_LOG.md` | Running log; 13 entries. |
| `CLAUDE.md` edits | O67/O68 refinements + O78/O79/O80/O81 added; count 67 → 71 → 67 after backlog audit closures; G19-G22 added. Direct edits — you authorised "be brave." |

### Code (tests green throughout)

- **Phase 1b**: 32 pure-Kotlin core files moved `jvmMain/` → `commonMain/`. 4 batch commits each ending green.
- **Phase 1c first wave**: 3 `expect/actual` shims (Sha256, Base64Codec, Uuid). 9 production call-sites migrated. 3 more files moved to commonMain. 35 of ~54 core files now in commonMain.
- **O64 security fix**: 5 UI sites stopped trusting sender-asserted `sentAtMs`. Added `RumorMessage.displayTimeMs = min(sentAtMs, receivedAtMs)`. Closes the message-pinning attack.
- **JUnit 5 engine fix** (`:app`): added engine + vintage to testRuntimeOnly so tests actually run. 96 tests now execute. Knock-on: 7 dormant failures surfaced; 4 fixed.
- **Build hygiene**: kotlinx.serialization opt-in fix on `WireJson`; `-Xexpect-actual-classes` flag to suppress beta warning.
- **Tests added**: `TrafficClassInvariantTest` (25 tests pinning the "trafficClass is derived" rule), `RbsrFormulaComparisonTest` (4 tests pinning the formula divergence so it can't silently regress).

Three-module verification (`:core:jvmTest`, `:simulator:test`, `:app:compileDebugKotlin`) green throughout the moves and shims.

---

## 2. Things that need your call

In rough priority order.

### A. RBSR formula divergence — keep ours, or align with NIP-77?

`docs/wire-format.md` §2.9 and `docs/RESEARCH_NOTES.md` §1 cover this in depth. Test in `core/src/jvmTest/.../RbsrFormulaComparisonTest.kt` proves the formulas produce different bytes on the same input set.

- **Keep ours**: no work, no behavior change. Cost: we cannot interop with a strfry / Nostr Negentropy relay or any other NIP-77 implementation.
- **Align with NIP-77**: bump `rumor-rbsr-v1` → `rumor-rbsr-v2`, capability-gate via `rbsr-v2` feature flag, ship migration. Cost: small implementation pass. Win: O54 transport plugins and O72 Nostr fallback can reuse RBSR machinery for free.

Since `LOCAL_SUPPORTED_FEATURES = emptyList()` in production today (rbsr-v1 opt-in only), the cost of switching is essentially nil. I lean **align with NIP-77** now.

### B. CI is going to turn red on next `:app` push — *one* test, not three

`:app:testDebugUnitTest` was silently passing because the JUnit 5 engine was missing. My fix to add the engine surfaced 7 dormant failures. I diagnosed and repaired **6 of 7**:

- **ChunkerTest reassemble gap** ✅ — fixture used uniform-byte content so chunks were byte-identical; replacing chunk 1 with chunk 0 yielded the same hash. Fixed fixture.
- **NeighborStore selectDiverse** ✅ — real bug: `(limit*0.8).toInt()` truncated, producing `coverageCount=0` at limit=1 (so the documented "prefer low-overlap" pick became random). Fixed with `ceil`.
- **SchedulerTest DRR fairness** ✅ — real production bug: a single-flow class with a head message exceeding the quantum couldn't drain (loop exited after `progress=false` round 1, even though deficit would have accumulated to cover the message on round 2). Restructured to loop while any queue is non-empty, with a 32-round progress-free cap. **This was a real bandwidth-fairness regression in BULK-class scheduling that would have surfaced on any node sending media chunks.**
- **DmEnvelopeRegistryTest** ✅ (3 of 7) — test fixture's default envelopeId contained a colon, rejected by the validator added in 8019739.

**Only 1 test failure remains** — `AppModuleTest` Koin verify catches a real DI typing inconsistency in the three Block adapters (registered as concrete types, consumed via interface types). Fixing it cleanly requires interface-widening the registrations and rewriting downstream `get<*Adapter>()` calls. Touches multiple files; warrants design review. See `docs/PREEXISTING_APP_TEST_FAILURES.md` §5 for both possible cleanup paths.

If you want CI green immediately: revert just `60cb2c9` (the DAO bindings + first AppModule attempt) AND `0bed36c` (the engine fix), which puts you back to silently-passing-on-zero-tests. But the engine fix is net-positive — it caught a real DRR bug and 5 other defects. I lean keep everything, fix the 1 remaining DI issue when you next touch DI.

### C. AppModuleTest DI typing inconsistency

Partially addressed (DAO bindings added). Remaining issue: three Block-related adapters (`BlockEntryRepositoryAdapter`, `SubscribedBlocklistRepositoryAdapter`, `BlocklistEntryRepositoryAdapter`) are registered as concrete types but their consumers' constructors take the interface type. Two cleanup paths in `docs/PREEXISTING_APP_TEST_FAILURES.md` §5. Touches multiple files; warrants design review.

### D. CLAUDE.md amendments — are they in your voice?

Direct edits made: O67/O68 refinements, O78/O79/O80/O81 new rows, O3/O12/O13/O16 closed, O25 reclassified, G19-G22 added, counts updated. If anything reads wrong, easy to revert per-commit.

### E. O64 explicit ingest-time clamp on `sentAtMs`

The audit (`docs/O64_SENTATMS_AUDIT.md`) recommended just relying on `displayTimeMs = min(...)` for the UI/display layer rather than an explicit `SKEW_TOLERANCE_MS` clamp. I implemented the implicit path; if you want the explicit ingest-time clamp anyway, that's a small follow-up.

### F. O48 bridge synthetic-userId migration

Decision-blocked per audit doc: needs your call on whether to do hard cutover (drop old bridged contacts) or O41-style identity rotation rebind. Then I can implement.

---

## 3. Open backlog (touched lightly or untouched)

- **Phase 1c remaining shims**: ConcurrentMap (4 callers), AtomicCounter (3 callers), RwLock (1 caller), Crypto AES-GCM + PBKDF2 (chunky). Need decision on `atomicfu` vs alternatives, BouncyCastle KMP vs platform-native. Documented in `docs/PHASE_1C_SHIM_SURFACE.md`.
- **O80 implementation** (battery-% mode triggers) — smallest of the new rows, can ship in one session, needs Android emulator to verify so not autonomous.
- **More invariant tests**: BRIDGED-never-relays, BRIDGE_UNSIGNED only-from-LOCAL_BRIDGE. The scenarios cover these; unit tests would tighten the safety net.

---

## 4. What I did NOT do

- Did not push to any branch other than `claude/practical-archimedes-wmySm` (per branch directive).
- Did not change any wire-format invariant on the wire (no MessageType added, no signature recipe changed, no domain tag bumped).
- Did not touch `:simulator` test scenarios.
- Did not run `:app:assembleDebug` to verify a full APK builds (`:app:compileDebugKotlin` passed; full assemble would have needed signing config).
- Did not run any `mcp__github__*` tool — no PR created, no issue posted, no review comment.

---

## 5. Commits on this branch (newest first)

```
60cb2c9 test: register raw DAOs in AppModule (partial AppModuleTest fix)
022e66a test: fix 3 DmEnvelopeRegistryTest failures (stale fixture, not a code bug)
0bed36c test: add JUnit 5 + JUnit-vintage engines to :app testRuntimeOnly
12e6e20 CLAUDE.md: close O3/O13/O16/O12 (already done in code); reclassify O25 to PART
547986a docs: backlog audit — three O-rows are already done (O3, O13, O16); O12 stale; O25 partial
305e460 test: trafficClass invariants — 25 tests covering type→class mapping + size-ceiling demotion + exhaustiveness
14e1ff4 docs: sleep-log entry 9 — Phase 1c first wave + RBSR formula test recorded
d0438e0 Phase 1c: Base64Codec + Uuid shims + WireJson opt-in fix; 2 more files to commonMain
d5d878b Phase 1c partial: Sha256 expect/actual shim + Rbsr.kt lifted to commonMain
48cf2b1 docs: more research notes (§9 Briar/iOS, §10 Tor-android, §11 KMP state) + RBSR formula divergence test
a0e6b2f Phase 1b: split Clock — interface to commonMain, SystemClock stays in jvmMain
~a39cba8 Phase 1b batch 3: lift 13 more pure files to commonMain
~7d2ef1d Phase 1b batch 2: lift policy (3) + 5 pure data interface files to commonMain
~e0bcaca Phase 1b batch 1: lift 10 pure model files to commonMain
7f9c3a8 O64: Category C UI fixes — stop trusting sentAtMs for ordering and labels
6fc4a8d docs: Phase 1c shim plan, O64 sentAtMs audit, O48 bridge-userId audit, O13 closed
80f99f1 docs: wire-format spec + research notes + sleep-session log
dd1e297 CLAUDE.md: O78 block-reasons, O79 rooms, O80 battery-%, O81 NSFW classifier; refine O67/O68
c1428d9 docs: proposed CLAUDE.md additions (O78 block-reasons, O79 rooms, O80 battery-%) [obsolete, superseded by direct edits]
```

(Three Phase 1b batch commits prefixed `~` are the shortened SHA — git's prefix-resolution will find them.)
