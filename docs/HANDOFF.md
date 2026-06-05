# Handoff note

> Per `docs/MULTI_INSTANCE_COORDINATION.md` async-handoff section.

## Branch state

`claude/practical-archimedes-wmySm`. **Wall clock at write:** 2026-06-05 14:34 UTC.

## Commits this session (chronological)

| Commit | Row | Result |
|---|---|---|
| `bf4cb3a` | **O39 → G25** | (Other instance.) Per-message key zeroize. |
| `f95ade4` | **O84 → G26** | `docs/CRYPTO_PRIMITIVES_AUDIT.md`. |
| `a8d012e` | **F-Droid groundwork** | Build doc + fastlane stubs. |
| `76d9f4f` | **O67 substrate** | Model + matcher. |
| `2295950` | **O76 padding** | PaddingBuckets + 9 tests. |
| `e3ed605` | Coordination doc | `MULTI_INSTANCE_COORDINATION.md`. |
| `740cfcd` | **O67 publish/verify** | Publisher + Verifier. |
| `4d1f10e` | **O67 wire layer** | Subscriber + GossipBridge + repos + MessageType. |
| `bcddd88` | **O76 compression layer** | Compression expect/actual + CompressedPaddedCodec. |
| `664c856` | **O87 → G27** | `signWithLocalKey`. |
| `72ff7c1` | **O67 Android persistence** | Room v6 + DAOs + adapters + DI. |
| `3343909` | **G15 Android Room + O76 scaffolding** | ScheduledMessage Room v7 + ext-helper accessors. |
| `d175cfe` | **O76 AEAD-AD wiring** | PlatformCrypto/CryptoManager take `aad`; Swift bridge spec updated; AAD-bearing golden vector pinned. |
| `<this>` | **O76 receive path live** | `ThreadViewModel.decryptPayload` reads `_ext.c`, computes `compressionAad(cl)`, runs AAD-bound AEAD-decrypt, calls `CompressedPaddedCodec.decodeFromWire`. End-to-end `CompressedAeadRoundTripTest` covers happy-path round-trip + tampered-AAD fail + canonical format pin + empty-plaintext edge. |

## What I considered and rejected (this batch)

- **Flipping `COMPRESSION_FEATURE` into `LOCAL_SUPPORTED_FEATURES` now.**
  Advertising a capability you can produce but the wider network can't
  read silently breaks peers running pre-receive-path builds. Standard
  "receive everywhere first, then compose" deployment pattern. Once
  the receive path is broadly deployed (any release that includes this
  commit), a future commit can wire the compose-side gate.
- **Wiring compose-side compression behind a per-recipient flag.**
  Would need either a `RecipientFeatureCache` (where you store "X
  supports compression-v1" from the last HELLO with that peer) or a
  session-tier negotiation. Either is a clean piece of work but it's
  not necessary today — receive path alone is the right structural
  prerequisite. Compose can wait for the cache.
- **Broadcast compression.** Currently DM-only by design: broadcasts
  have no AEAD layer to bind `cl` into; padding-plaintext leaks `cl`
  to any observer. Compress-without-pad has bandwidth merit but it's
  a separate decision (could land later as a `compression-broadcast-v1`
  feature flag).
- **Robolectric ViewModel test for the new decryptPayload branch.**
  The end-to-end correctness is covered by
  `CompressedAeadRoundTripTest` at the codec/AEAD level; what would be
  added by a ViewModel test is just "yes ThreadViewModel calls the
  helper" which the visible code already proves. Saving the
  Robolectric setup cost for a real bug.

## Suggested next moves

1. **O76 compose-side flip.** Recipient-feature cache or session-tier
   negotiation; gate `composeDirect` on the gate; wire
   `withCompressionMetadata` + `compressionAad`; add a contract test
   that confirms compose+receive round-trip works on the GossipEngine
   side (not just the codec).

2. **Chunker fallback above 64 KB.** Today
   `CompressedPaddedCodec.encodeForWire` returns null if compressed
   bytes exceed `MAX_SINGLE_MESSAGE`. Caller should fall back to the
   existing file-transfer chunker with TextAssembler stitching the
   chunks back into a single logical message — no "fragment 3 of 5"
   UI per O76 spec.

3. **MessageScheduler lifecycle hook in MeshService.** Two-line wire.

4. **O88 in-thread plugin display widget.** UI work.

5. **LICENSE file.** Gated on user choice.

## Backlog state at handoff

- **Counts:** 11 PART · 14 DECISION · 44 TODO (CODE 22 · SIM 2 · UI 9 · EMU 4 · HW 7). Total 69.
- **Completed gaps:** G1–G27.
- **O76 row text updated** to reflect the AEAD-AD wiring + receive path
  landing; row tag stays PART (compose-side flip is the gate).
- **G15 row** updated mid-session to note Android Room adapter
  shipping; tag was already closed (G15) and stays so.

## What's NOT updated and may be stale

- `docs/FDROID_BUILD.md` still names `LICENSE` as missing.
- iOS `Compression` actual + `PlatformCrypto.aesGcm*` iOS actuals
  remain `NotImplementedError`. Both gated on the Swift bridge
  landing per the iOS port plan.
- `MessageScheduler.start()` is wired in DI but no lifecycle hook
  in `MeshService` calls it yet.
- `LOCAL_SUPPORTED_FEATURES` in `GossipSession` is still empty in
  production. Per design until both compression-v1 halves are wired.

## Tooling status

- `:core:jvmTest` — green (32+ new tests this session across O67/O76).
- `:app:testDebugUnitTest` — green (Koin DI verify caught one wiring
  bug, fixed in the same batch).
- `:simulator:test` — green.

## Canary

"By Order Of The High Magnate" used on every commit message.
