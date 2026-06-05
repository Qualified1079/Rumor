# Handoff note

> Per `docs/MULTI_INSTANCE_COORDINATION.md` async-handoff section. The
> instance that wakes next reads this, absorbs the state, and either
> rewrites the file with their own snapshot or clears it.

## Branch state

`claude/practical-archimedes-wmySm` is the working branch. **Wall clock
when this note was written:** 2026-06-05 13:31 UTC.

## What I closed / advanced this session

| Commit | Row | Result |
|---|---|---|
| `bf4cb3a` | **O39 → G25** | (Other instance, not me.) Per-message key zeroize + 4th SourceInvariant guard. |
| `f95ade4` | **O84 → G26** | `docs/CRYPTO_PRIMITIVES_AUDIT.md`. Surfaced O87 + O88. |
| `a8d012e` | **F-Droid groundwork** | `docs/FDROID_BUILD.md` + fastlane stubs + CI ./gradlew. |
| `76d9f4f` | **O67 substrate** | Model + matcher + 12 tests. |
| `2295950` | **O76 padding half** | `PaddingBuckets.kt` + 9 tests. |
| `e3ed605` | Coordination doc | `docs/MULTI_INSTANCE_COORDINATION.md`. |
| `740cfcd` | **O67 publish/verify** | `KeywordFilterPublisher` + `KeywordFilterVerifier` + 8 tests. |
| `4d1f10e` | **O67 wire layer complete** | `KeywordFilterSubscriber` + `KeywordFilterGossipBridge` + repos + MessageType.KEYWORD_FILTER_PUBLISH + 10 subscriber tests. |
| `<this>` | **O76 compression layer + composed codec** | `core/platform/Compression.kt` expect/actual (JVM via java.util.zip raw deflate; iOS stub pending libcompression cinterop) + `core/wire/CompressedPaddedCodec.kt` composing compress+pad / unpad+decompress + 9 compression tests + 7 codec tests. **Total 24 tests on the O76 surface now.** |

## What I considered and rejected

- **GossipEngine integration in this batch (calling `encodeForWire` from
  `composeBroadcast` / `composeDirect`).** Would touch HELLO
  `supportedFeatures` negotiation, the wire-format `_ext` layer, AEAD
  associated-data plumbing for `originalLength`, and the chunker
  fallback for >64 KB. Each piece is small but together they're another
  multi-file refactor on top of an already-large session. The codec is
  a pure function — landing it cleanly first means the next instance can
  do the GossipEngine wiring as a focused change without also designing
  the codec API.
- **zstd / brotli over raw deflate.** zstd would shrink better (~10–20%
  more on text) but needs a third-party native dep (`zstd-jni` on JVM,
  `zstd.framework` on iOS, varies elsewhere). Raw deflate ships in every
  platform's stdlib — Android's `java.util.zip`, iOS's
  `libcompression.dylib`, Linux's `zlib`. Portability beats marginal
  ratio for our cross-platform / MCU-eventual surface.
- **gzip framing.** Adds 10–18 bytes of header/trailer per message; on
  the 64 B bucket that's a meaningful tax. Raw deflate is the right
  default; the wire format gives us its own framing.
- **Streaming compression.** No use case — each message is whole; the
  chunker takes over above 64 KB. Streaming would also reopen the
  CRIME/BREACH cross-message-context risk that the
  "one-fresh-compression-per-message" rule explicitly avoids.

## Suggested next moves

1. **O76 GossipEngine integration** is the obvious next step. Add
   `_ext` fields `compressed: Boolean` + `bucketIndex: Byte` to the
   message wrapper; HELLO `supportedFeatures: ["compression-v1"]`
   capability negotiation; gate on TEXT contentType in
   `composeBroadcast` / `composeDirect`; `originalLength` rides in
   AEAD-protected associated data (NOT `_ext`, so a relay can't flip
   it without breaking the tag); receiver-side `decodeFromWire` call
   after AEAD-decrypt. Chunker fallback above 64 KB is in scope.

2. **O67 Android Room adapters.** The interfaces are in commonMain;
   `:app/data/adapter/` needs `KeywordFilterListRepositoryAdapter` +
   `FilterSubscriptionRepositoryAdapter` (Room) and
   `:simulator/.../InMemoryRepos.kt` needs the equivalent in-memory
   stubs. Room schema bump (current version 5 → 6). DI wiring in
   `AppModule.kt`.

3. **O87 `PluginContext.signWithLocalKey`.** 4 lines per the audit
   doc.

4. **LICENSE file.** Still gated on user choice between GPL-3.0-or-later
   and AGPL-3.0.

5. **iOS Compression actual.** Apple's `libcompression.dylib` exposes
   raw deflate via `compression_stream` + `COMPRESSION_ZLIB` algorithm
   (despite the name, raw deflate is selectable). cinterop-reachable.
   Same xtool/Mac gate as the rest of the iOS port; until then, iOS
   peers simply won't advertise `compression-v1`.

## Backlog state at handoff

- **Counts:** 11 PART · 14 DECISION · 45 TODO (CODE 23 · SIM 2 · UI 9 · EMU 4 · HW 7). Total 70 open rows.
- **Completed gaps:** G1–G26.
- **Open rows touched this session:** O67 (PART, full wire layer added),
  O76 (PART, compression layer + composed codec added), O84 (closed →
  G26), O87 + O88 (new).
- **`Counts as of this writing` in CLAUDE.md remains 70.** Row tags
  unchanged this commit (O76 was already PART; codec didn't promote
  it).

## What's NOT updated and may be stale

- `docs/FDROID_BUILD.md` checklist still names `LICENSE` as missing —
  still missing.
- iOS Compression actual stubs `NotImplementedError`. Anyone enabling
  `compression-v1` in HELLO `supportedFeatures` on iOS without the
  cinterop wiring will crash at the first compose call. The capability
  string must remain unadvertised on iOS until the actual is real.

## Tooling status

- `./gradlew :core:jvmTest` — green this session.
- `./gradlew :app:testDebugUnitTest` — green earlier this session (last
  run after the O67 wire commit).
- `./gradlew :simulator:test` — green earlier this session.
- Compression / codec tests added today are all in `jvmTest` (require
  JVM `java.util.zip`); commonTest can't host them yet because the iOS
  actual throws.

## Canary

"By Order Of The High Magnate" sign-off was used on every commit
message this session.
