# Handoff note

> Per `docs/MULTI_INSTANCE_COORDINATION.md` async-handoff section.

## Branch state

`claude/practical-archimedes-wmySm`. **Wall clock at write:** 2026-06-05 14:37 UTC.

## Commits this session (chronological)

| Commit | Row(s) | Result |
|---|---|---|
| `bf4cb3a` | **O39 → G25** | (Other instance.) Per-message key zeroize + 4th SourceInvariant guard. |
| `f95ade4` | **O84 → G26** | `docs/CRYPTO_PRIMITIVES_AUDIT.md`; surfaced O87 + O88. |
| `a8d012e` | F-Droid groundwork | `docs/FDROID_BUILD.md` + fastlane stubs + CI `./gradlew`. |
| `76d9f4f` | **O67 substrate** | KeywordFilter model + matcher + 12 tests. |
| `2295950` | **O76 padding** | PaddingBuckets + 9 tests. |
| `e3ed605` | Coordination doc | `MULTI_INSTANCE_COORDINATION.md`. |
| `740cfcd` | **O67 publish/verify** | Publisher + Verifier + 8 tests. |
| `4d1f10e` | **O67 wire layer** | Subscriber + GossipBridge + repos + MessageType + 10 tests. |
| `bcddd88` | **O76 compression layer** | Compression expect/actual + CompressedPaddedCodec + 16 tests. |
| `664c856` | **O87 → G27** | `PluginContext.signWithLocalKey`. |
| `72ff7c1` | **O67 Android persistence** | Room v6 + DAOs + adapters + DI. |
| `3343909` | **G15 Android Room + O76 scaffolding** | ScheduledMessage Room v7 + ext-helper accessors + COMPRESSION_FEATURE const. |
| `d175cfe` | **O76 AEAD-AD wiring** | PlatformCrypto/CryptoManager take aad; Swift bridge spec updated; AAD-bearing golden vector pinned. |
| `59995d2` | **O76 receive path live** | ThreadViewModel reads compressed DMs; CompressedAeadRoundTripTest covers happy + tamper + format pin + empty cases. |
| `341c20a` | **G15 scheduler lifecycle** | MeshService.startMesh/stopMesh wire MessageScheduler.start()/stop(). |

Session output: 14 commits, ~600 lines of production code + ~900 lines of test code, **8 backlog rows advanced** (O67 → fully wired Android stack; O76 → padding + compression layer + AEAD-AD + receive path; O84 → closed G26; O87 → closed G27; G15 → Android Room + lifecycle; F-Droid groundwork; coordination protocol).

## What I considered and rejected this session (consolidated)

- **Flipping O76 `COMPRESSION_FEATURE` into `LOCAL_SUPPORTED_FEATURES`.**
  Receive path lives, compose path doesn't. Standard "receive
  everywhere first, then compose" pattern.
- **GossipEngine compose-side compression today.** Needs per-recipient
  feature awareness (a `Contact.lastKnownSupportedFeatures` field, or
  a session-tier cache, populated after a successful HELLO). That's
  an architectural choice deserving a focused commit, not a tail end.
- **Robolectric ViewModel test for the new O76 receive branch.**
  Codec-level + AEAD-level round-trip in `CompressedAeadRoundTripTest`
  covers correctness. ViewModel test would only confirm "yes the
  helper is wired" — visible code already proves that.
- **Expanded typed columns for KeywordFilterList/FilterSubscription
  Room storage.** JSON-blob per row beats child-table refactor when
  the matcher consumes lists as a unit and we never query inside the
  blob.
- **`SharedFlow<KeywordFilterList>` `onListApplied` hook on the
  gossip bridge.** Koin verify caught the default-valued Function1
  param mid-batch; dropped the hook. UI re-reads per render.
- **`expect val EMPTY_AAD` constant in PlatformCrypto.** Adding a
  constant inside `expect object` means each actual has to define
  it. `ByteArray(0)` inline is one fewer cross-platform contract.
- **Broadcast compression (O76 path).** DMs only by design today —
  broadcasts have no AEAD layer to bind `cl` into; padding plaintext
  leaks `cl` to any observer. Compress-without-pad has bandwidth
  merit but is a separate feature-flag decision.

## Suggested next moves (in priority order, with reasoning)

1. **O76 compose-side flip.** The structural piece left for O76 to
   ship. Decide where peer feature awareness lives — either:
   - Additive field on `Contact` (`lastKnownSupportedFeatures: List<String>`),
     populated by a successful HELLO via the existing
     `WifiDirectTransport`/`GossipSession` callback path.
   - Session-tier `recipientFeatureCache: Map<UserId, List<String>>`
     scoped to `GossipEngine`.
   The Contact field is more persistent (survives reboots) but
   touches Room schema. The session cache is simpler but loses state
   across the GossipEngine lifetime. Once the cache exists, gate
   `composeDirect` on `peerSupportsCompression()` and emit
   compressed payloads. Then flip `LOCAL_SUPPORTED_FEATURES = listOf(
   COMPRESSION_FEATURE)`. Receive path is already live (this session).

2. **Chunker fallback for >64 KB compressed text.** Today
   `CompressedPaddedCodec.encodeForWire` returns null on >64 KB
   compressed size. composeDirect needs to detect that and fall back
   to the existing chunker, marked TEXT-typed, with a TextAssembler
   on the receive side stitching chunks into a single logical
   message. Per O76 spec: no UI ever exposes "your message was
   chunked."

3. **O67 UI list editor + default-list onboarding.** All plumbing
   complete. Compose surface where the user authors / signs /
   shares filter lists, plus an onboarding flow for the three
   default lists (slurs default-BLOCK; NSFW text default-WARN;
   gore text default-WARN). Content sourcing for the defaults is
   the open question; can ship UI with empty defaults and let
   users opt in.

4. **O88 in-thread plugin display widget.** O84 audit follow-up.
   `@Composable PluginDisplay(message: RumorMessage)?` declared by
   plugins, falls back to default rendering.

5. **LICENSE file at repo root.** Still gated on user choice
   (GPL-3.0-or-later vs AGPL-3.0). FDROID_BUILD.md is otherwise
   green.

6. **iOS PlatformCrypto + Compression actuals.** Same xtool/Mac
   gate as the rest of the iOS port. The Swift bridge spec is
   updated for the AAD parameter and the AAD-bearing golden vector
   is pinned, so the first iOS test run will surface any
   incompatibility immediately.

## Backlog state at handoff

- **Counts:** 11 PART · 14 DECISION · 44 TODO (CODE 22 · SIM 2 · UI 9 · EMU 4 · HW 7). Total 69.
- **Completed gaps:** G1–G27.
- **Rows touched this session:** O67 (PART → fully wired Android stack); O76 (PART → padding + compression + AEAD-AD + receive path); O84 (closed → G26); O87 (closed → G27); G15 (Android Room + lifecycle); O88 (new follow-up).
- **`Counts as of this writing` in CLAUDE.md stays at 69.**

## What's NOT updated and may be stale

- `docs/FDROID_BUILD.md` still names `LICENSE` as missing.
- iOS `PlatformCrypto.aesGcm*` and `Compression` actuals still throw
  `NotImplementedError`. Gated on the Swift bridge landing.
- `LOCAL_SUPPORTED_FEATURES` in `GossipSession` is still empty in
  production. By design — both halves of `compression-v1` must be
  wired before advertising.
- Default keyword filter lists (slurs/NSFW/gore) are not yet seeded.
  The subscriber path will accept them when an authoring publisher
  signs and broadcasts them; ship-time decision required for
  what/whose lists get default-applied.

## Tooling status

- `:core:jvmTest` — green at HEAD.
- `:app:testDebugUnitTest` — green. Koin DI verify (G6) caught one
  real wiring bug mid-session (default-valued Function1 lambda) —
  fixed by removing the lambda parameter.
- `:simulator:test` — green.

## Canary

"By Order Of The High Magnate" used on every commit message this
session.
