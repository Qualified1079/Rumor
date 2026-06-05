# Handoff note

> Per `docs/MULTI_INSTANCE_COORDINATION.md` async-handoff section. The
> instance that wakes next reads this, absorbs the state, and either
> rewrites the file with their own snapshot or clears it.

## Branch state

`claude/practical-archimedes-wmySm` is the working branch. All commits
below were pushed to it. **Wall clock when this note was written:**
2026-06-05 13:24 UTC.

## What I closed / advanced this session

| Commit | Row | Result |
|---|---|---|
| `bf4cb3a` | **O39 → G25** | (Done by the OTHER instance — not me.) Per-message key lifecycle audit; zeroize sender + receiver ephemeral keys; fourth `SourceInvariantTest` guard. |
| `f95ade4` | **O84 → G26** | `docs/CRYPTO_PRIMITIVES_AUDIT.md`. Surfaced **O87** (signWithLocalKey) and **O88** (in-thread plugin widget) as new follow-ups. |
| `a8d012e` | **F-Droid groundwork** | `docs/FDROID_BUILD.md` + `fastlane/metadata/android/en-US/` stubs + CI uses `./gradlew`. |
| `76d9f4f` | **O67 → PART (substrate)** | `KeywordFilter.kt` model + matcher + 12 tests. |
| `2295950` | **O76 → PART (padding half)** | `PaddingBuckets.kt` + 9 tests. Compression layer + wire integration are the followup. |
| `e3ed605` | Coordination doc | `docs/MULTI_INSTANCE_COORDINATION.md` codifies the protocol. |
| `740cfcd` | **O67 publish/verify** | `KeywordFilterPublisher` + `KeywordFilterVerifier` + 8 round-trip / tamper tests. |
| `<this>` | **O67 wire layer complete** | `KeywordFilterSubscriber` + `KeywordFilterGossipBridge` + `KeywordFilterRepository` (two interfaces) + 10 subscriber tests + `MessageType.KEYWORD_FILTER_PUBLISH` wired into trafficClass (TRANSFER_SETUP), GossipEngine relay branch, GossipEngine clampTtl, exhaustive when guard in `TrafficClassInvariantTest`. `FilterSubscription` now carries `publisherPublicKey` + `lastAppliedVersion` so subscriptions survive process restart. |

## What I considered and rejected

- **Storing subscription tracking state (`publisherKey`, `lastAppliedVersion`)
  in a third repository.** Would've been cleaner separation but the
  fields are tiny and fit naturally inside `FilterSubscription`. Adding
  a third storage shape for ~16 bytes of tracking metadata felt like
  overengineering. Documented inline as "subscriber-internal tracking,
  not part of override semantics."
- **Splitting `KeywordFilterList` into per-entry rows like `BlocklistEntry`.**
  Blocklists have thousands of entries; keyword filter lists have dozens.
  Storing the whole signed list as a single row keyed by publisherId
  matches usage (matcher consumes the list as a unit) and avoids the
  per-row deletion / re-insert churn the per-entry shape requires.
- **Adding gossip-bridge tests with a real `GossipEngine`.** Would need
  a full engine fixture (identity, MessageStore, scheduler, transport
  fake) — disproportionate setup for what's effectively a 50-line
  routing wrapper. The publisher / verifier / subscriber paths are all
  unit-tested separately; the gossip bridge is the trivial composition.
  If a real bug surfaces, that's the moment to invest in the fixture.
- **A `BlocklistDiff`-style diff variant.** Keyword filter lists are
  expected to churn at hours-to-days cadence (a moderator adds a slur,
  promotes a WARN to BLOCK), not per-minute like a popular block
  publisher. Full snapshots at TRANSFER_SETUP cost are fine; revisit
  if real-world churn justifies it.
- **Compression layer for O76 in this batch.** Would need
  `expect`/`actual` (`java.util.zip.Deflater` on JVM, TBD on Native).
  Bigger pickup; left for a focused commit.

## Suggested next moves

1. **O76 compression layer.** Spec is in CLAUDE.md and
   `core/wire/PaddingBuckets.kt` docstring. Add
   `core/src/commonMain/.../wire/Compression.kt` with `expect fun deflate` /
   `expect fun inflate`, JVM actual using `java.util.zip.Deflater` with
   raw deflate (no zlib wrapper). Leave iOS/Native actual as
   `NotImplementedError("compression: pending compress-step shim")`.
   Then integrate compress-then-pad into `GossipEngine.composeBroadcast`
   / `composeDirect` for TEXT contentType only (skip BINARY/FILE per
   CRIME/BREACH constraint in the PaddingBuckets docstring). HELLO
   `supportedFeatures: ["compression-v1"]` capability negotiation
   gates whether compression is applied on a given exchange.

2. **O67 Android Room adapters.** `KeywordFilterListRepository` +
   `FilterSubscriptionRepository` interfaces are in `:core/commonMain`;
   the `:app` Room adapters and the `:simulator` in-memory stubs both
   need to land before any code path uses these for real. Per the
   CLAUDE.md "DI (Koin) wiring" section — update `AppModule.kt`,
   `InMemoryRepos.kt`. Room schema bump (current version: 5 → 6).

3. **O87 `PluginContext.signWithLocalKey`.** 4-line addition. Thread
   `IdentityManager` into `PluginContextImpl`, expose
   `signWithLocalKey(bytes): ByteArray`, throw on locked identity.

4. **LICENSE file at repo root.** Listed in FDROID_BUILD.md as the
   biggest open prerequisite. Ask the user to choose between
   GPL-3.0-or-later and AGPL-3.0 before adding.

5. **O67 UI list editor.** Compose surface where a user composes their
   own filter list, signs it, optionally publishes to the mesh.
   Followed by built-in default lists onboarding flow. Lower priority
   than the wire layer was — this is the user-facing tip of the
   pyramid that the substrate enables.

## Backlog state at handoff

- **Counts:** 11 PART · 14 DECISION · 45 TODO (CODE 23 · SIM 2 · UI 9 · EMU 4 · HW 7). Total 70 open rows.
- **Completed gaps:** G1–G26.
- **Open rows touched this session:** O67 (PART, full wire layer added), O76 (PART, padding added), O84 (closed → G26), O87 + O88 (new).
- **`Counts as of this writing` line in CLAUDE.md is still accurate at 70.** The O67 work moved the row text but not the row tag or the totals.

## What's NOT updated and may be stale

- `docs/FDROID_BUILD.md` checklist still names `LICENSE` as missing —
  still missing. Asking the user is gated by user availability; don't
  add a LICENSE file without their choice.
- The O67 backlog row text now reflects "wire layer complete" but
  doesn't list the remaining open work explicitly — see the row in
  CLAUDE.md for the (long) "Not done" section.

## Tooling status

- `./gradlew :core:jvmTest` — green this session (verified after every
  batch).
- `./gradlew :app:testDebugUnitTest` — green this session.
- `./gradlew :simulator:test` — green this session.
- All three modules compile and test together as of `<this>`.

## Canary

"By Order Of The High Magnate" sign-off was used on every commit
message this session.
