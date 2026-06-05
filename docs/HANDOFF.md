# Handoff note

> Per `docs/MULTI_INSTANCE_COORDINATION.md` async-handoff section. The
> instance that wakes next reads this, absorbs the state, and either
> rewrites the file with their own snapshot or clears it.

## Branch state

`claude/practical-archimedes-wmySm`. **Wall clock when this note was
written:** 2026-06-05 13:45 UTC.

## What I closed / advanced this session (all commits)

| Commit | Row | Result |
|---|---|---|
| `bf4cb3a` | **O39 → G25** | (Other instance.) Per-message key zeroize + 4th SourceInvariant guard. |
| `f95ade4` | **O84 → G26** | `docs/CRYPTO_PRIMITIVES_AUDIT.md`. Surfaced O87 + O88. |
| `a8d012e` | **F-Droid groundwork** | `docs/FDROID_BUILD.md` + fastlane stubs + CI `./gradlew`. |
| `76d9f4f` | **O67 substrate** | Model + matcher + 12 tests. |
| `2295950` | **O76 padding half** | `PaddingBuckets.kt` + 9 tests. |
| `e3ed605` | Coordination doc | `docs/MULTI_INSTANCE_COORDINATION.md`. |
| `740cfcd` | **O67 publish/verify** | `KeywordFilterPublisher` + `KeywordFilterVerifier` + 8 tests. |
| `4d1f10e` | **O67 wire layer** | Subscriber + GossipBridge + repos + MessageType + 10 tests. |
| `bcddd88` | **O76 compression layer** | `Compression` expect/actual + `CompressedPaddedCodec` + 16 tests. |
| `664c856` | **O87 → G27** | `PluginContext.signWithLocalKey`. |
| `<this>` | **O67 Android persistence + DI** | Room v6: KeywordFilterListEntity + FilterSubscriptionEntity (JSON-blob shape) + DAOs + adapters. AppModule wired. Simulator in-memory stubs. `FilterSubscription` + `FilterSubscriptionMode` marked `@Serializable` to support JSON blob storage. Gossip bridge had its optional `onListApplied` lambda dropped — Koin verify (G6) caught it as an unresolvable Function1 dep; UI re-reads `subscribedListsForMatcher()` per render anyway. |

## What I considered and rejected

- **Expanded typed columns for KeywordFilterList / FilterSubscription
  storage.** `List<FilterEntry>`, `Map<String, FilterAction>`, and
  `Set<String>` are not Room-native; either a child-table refactor
  (3+ extra tables) or per-field JSON converters would be required.
  Storing the whole signed object as a JSON blob per row is the
  smallest amount of code that achieves the same persistence
  semantics — we never query inside these blobs (the matcher reads
  whole-lists into memory on every render), and the wire-format /
  storage-format mapping is 1:1 so deserialization regressions
  surface immediately. Trade-off accepted in `KeywordFilterEntities.kt`
  docstring: a corrupted blob nukes one subscription rather than one
  field; adapter logs+null on parse failure.
- **A `SharedFlow<KeywordFilterList>` on KeywordFilterGossipBridge for
  "list applied" notifications.** Originally had a constructor-injected
  `onListApplied: ((KeywordFilterList) -> Unit)?` lambda but Koin's
  Verify reflection doesn't see default values on Function1 params,
  so the DI verify test failed. Dropped the parameter; UI consumers
  re-read `subscribedListsForMatcher()` on every render so the hook
  isn't strictly needed. Documented inline that if a hook becomes
  useful, prefer a SharedFlow over a constructor lambda.
- **GossipEngine integration for O76 compression.** The codec is
  shipped; wiring it into `composeBroadcast` / `composeDirect`
  requires HELLO `supportedFeatures` negotiation, `_ext` fields,
  AEAD associated-data plumbing for `originalLength`, and a chunker
  fallback above 64 KB. Bigger refactor than fits in a single batch.

## Suggested next moves

1. **O76 GossipEngine integration.** Codec shipped; need HELLO
   `supportedFeatures: ["compression-v1"]` negotiation,
   `composeBroadcast` / `composeDirect` calling `encodeForWire` for
   TEXT contentType only, `originalLength` in AEAD-protected
   associated data, receiver `decodeFromWire`, chunker fallback >64 KB.

2. **O88 in-thread plugin display widget.** O84 audit's second
   follow-up. `@Composable PluginDisplay(message)?` declared by
   plugins; falls back to default if none claims the type.

3. **LICENSE file.** Still gated on user choice (GPL-3.0-or-later or
   AGPL-3.0). FDROID_BUILD.md submission checklist is otherwise
   green.

4. **O67 UI list editor + default-list onboarding.** The whole
   wire/Room/DI stack is ready; this is the last piece before the
   feature is user-visible.

5. **iOS Compression actual.** Apple's libcompression has raw
   deflate via `COMPRESSION_ZLIB`. Same xtool/Mac gate as the rest
   of the iOS port.

## Backlog state at handoff

- **Counts:** 11 PART · 14 DECISION · 44 TODO (CODE 22 · SIM 2 · UI 9 · EMU 4 · HW 7). Total 69.
- **Completed gaps:** G1–G27.
- **Open rows touched this session:** O67 (PART, full Android wire stack), O76 (PART, compression layer + codec), O84 (closed → G26), O87 (closed → G27), O88 (new).

## What's NOT updated and may be stale

- `docs/FDROID_BUILD.md` still names `LICENSE` as missing.
- iOS Compression actual stubs `NotImplementedError`. The
  `compression-v1` HELLO capability must stay unadvertised on iOS
  until the libcompression cinterop lands.
- O67 UI is the only piece between today's state and a user-visible
  keyword filter feature on Android. The plumbing is all there.

## Tooling status

- `./gradlew :core:jvmTest` — green this session.
- `./gradlew :app:testDebugUnitTest` — green this session (G6 Koin
  DI verify in particular caught a real wiring bug mid-batch on this
  commit).
- `./gradlew :simulator:test` — green this session.
- Room schema v6 hasn't been exported to disk for schema-history
  checks; that's a `room.schemaLocation` config item we haven't
  wired (KSP warns about it on every build but the warning is
  benign for the dev configuration).

## Canary

"By Order Of The High Magnate" sign-off was used on every commit
message this session.
