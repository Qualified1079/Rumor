# Handoff note

> Per `docs/MULTI_INSTANCE_COORDINATION.md` async-handoff section.

## Branch state

`claude/practical-archimedes-wmySm`. **Wall clock at write:** 2026-06-05 14:22 UTC.

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
| `<this>` | **G15 Android Room adapter + O76 scaffolding** | ScheduledMessageEntity/Dao/Adapter (Room v7); MessageScheduler now wired in AppModule; CompressedPaddedExt with `_ext` accessors (`c`/`cb`/`cl` short keys) + 3 round-trip tests; GossipSession.COMPRESSION_FEATURE constant defined but deliberately not in LOCAL_SUPPORTED_FEATURES; SentAtMsLintTest allowlist extended for the new ext-helper test. |

## What I considered and rejected (this batch)

- **Flipping O76 compression-v1 on the wire today.** Cannot honor the
  capability advertising without AEAD AD wiring — `originalLength` in
  `_ext` would be flippable by a relay, opening a DoS path (cap
  mismatch on inflate, wasted CPU). Real fix requires
  `PlatformCrypto.aesGcmEncrypt/Decrypt` to accept `aad: ByteArray`
  and the JVM + iOS Swift bridge to thread it through. Documented in
  `CompressedPaddedExt.kt` header — that's where the next instance
  should look first.
- **Writing ext-helper accessors in `core/model/Message.kt` next to
  the TTL helpers.** Keeping them in `core/wire/` because compression
  is a wire-format/codec concern, not a protocol concern; readers of
  the codec see the full picture in one place.

## Suggested next moves

1. **O76 AEAD-AD wiring** is the gating change before compression
   ships. `PlatformCrypto` API needs an `aad: ByteArray` param;
   `Cipher.updateAAD` on JVM; `AES.GCM.seal(_, authenticating:)` on
   iOS. Once that lands, the compose path can pack
   `originalLength` (4 bytes) as AAD; receiver re-derives and the
   tag check guarantees no relay tampered with it. Then flip
   `COMPRESSION_FEATURE` into `LOCAL_SUPPORTED_FEATURES`.

2. **MessageScheduler lifecycle hook in MeshService.** The DI wiring
   is done; nobody calls `scheduler.start()` yet. Drop one line in
   `MeshService.onCreate` (or similar) to start the poll loop, and
   one in `onDestroy` to stop it.

3. **O88 in-thread plugin display widget.** UI work; Compose
   composable injection point.

4. **LICENSE file.** Gated on user choice (GPL-3.0-or-later vs AGPL-3.0).

5. **O67 UI list editor + default-list onboarding.** The plumbing is
   complete end-to-end; only the surface is left.

## Backlog state at handoff

- **Counts:** 11 PART · 14 DECISION · 44 TODO (CODE 22 · SIM 2 · UI 9 · EMU 4 · HW 7). Total 69.
- **Completed gaps:** G1–G27.
- **G15 row text** updated this commit to note "Room adapter shipped on Android."
- **`Counts as of this writing` in CLAUDE.md** stays at 69 — no row tags moved this batch (G15 was already closed; O76 stays PART; the Room work on G15 is a finished-followup-on-a-closed-row).

## What's NOT updated and may be stale

- `docs/FDROID_BUILD.md` still names `LICENSE` as missing.
- iOS Compression actual stubs `NotImplementedError`.
- `MessageScheduler.start()` is wired in DI but nothing calls it yet —
  the schedule fires would not happen on Android until the lifecycle
  hook lands.
- `CompressedPaddedExt` accessors are wired but not consumed by
  `GossipEngine` or `ThreadViewModel` — wire-format integration is
  scaffolded, not active.

## Tooling status

- `:core:jvmTest` — green.
- `:app:testDebugUnitTest` — green (G6 Koin verify covered the new
  scheduler binding).
- `:simulator:test` — green.

## Canary

"By Order Of The High Magnate" used on every commit message.
