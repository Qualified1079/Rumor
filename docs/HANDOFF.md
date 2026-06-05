# Handoff note

> Per `docs/MULTI_INSTANCE_COORDINATION.md` async-handoff section. The
> instance that wakes next reads this, absorbs the state, and either
> rewrites the file with their own snapshot or clears it.

## Branch state

`claude/practical-archimedes-wmySm` is the working branch. All commits
below were pushed to it.

## What I closed / advanced this session

| Commit | Row | Result |
|---|---|---|
| `bf4cb3a` | **O39 → G25** | (Done by the OTHER instance — not me.) Per-message key lifecycle audit; zeroize sender + receiver ephemeral keys; fourth `SourceInvariantTest` guard. |
| `f95ade4` | **O84 → G26** | `docs/CRYPTO_PRIMITIVES_AUDIT.md` walks a Cashu-on-Rumor companion. Six of eight capabilities available today via G5 DmEnvelope. Two new follow-ups filed: **O87** (`PluginContext.signWithLocalKey`) and **O88** (in-thread plugin display widget). |
| `a8d012e` | **F-Droid groundwork** | `docs/FDROID_BUILD.md` build recipe + `fastlane/metadata/android/en-US/` stubs + `.github/workflows/ci.yml` switched to `./gradlew` (stale comment fixed). Checklist of remaining prereqs (LICENSE file, dependency locking, repro-hash verification) in the doc. |
| `76d9f4f` | **O67 → PART (substrate)** | `KeywordFilter.kt` model + `KeywordFilterMatcher.kt` matcher + 12 tests. |
| `2295950` | **O76 → PART (padding half)** | `PaddingBuckets.kt` (six buckets, zero-fill TLS-1.3-style) + 9 tests. Compression layer + wire integration are the followup. |
| `e3ed605` | Coordination doc | `docs/MULTI_INSTANCE_COORDINATION.md` codifies the informal-lock-via-commit-message protocol. |
| `<current>` | **O67 publish/verify** | `KeywordFilterPublisher.kt` + `KeywordFilterVerifier.kt` + 8 round-trip / tamper tests. O67 row updated to note publish/verify shipped; subscriber + gossip-bridge wiring still open. |

## What I considered and rejected

- **O76 compression layer in this session.** Would need `expect`/`actual`
  with `java.util.zip.Deflater` on JVM and a TBD impl on iOS/Native. Not
  blocked technically, but biggest-bang-for-tokens went to publish/verify
  instead. The next instance can take it cleanly: add
  `core/src/commonMain/.../wire/Compression.kt` with `expect fun deflate`
  / `expect fun inflate`, JVM actual in `jvmMain`, leave iOS actual as
  `NotImplementedError` until a Swift bridge or a pure-Kotlin deflate
  port is available.
- **`docs/CRYPTO_PRIMITIVES_AUDIT.md` listing "plugin-to-plugin secure
  channel" as a gap.** Intra-device IPC is the OS's job (bound-service
  for APK-tier, shared host storage for in-process). Not a Rumor crypto
  primitive. Documented as rejected in the audit doc.
- **A fourth audit round on commonMain JVM leaks.** Three rounds yielded
  6 → 0 → 0 findings. Diminishing returns hit. The grep coverage in
  round 3 was: imports (`java.`, `javax.`, `kotlin.jvm.`, `sun.`),
  `System.*`, `Thread`/`Runtime`, `String.format`, IO streams, reflection,
  `@Jvm*` annotations, raw `j.u.c.atomic`. Skip unless the surface area
  changes (e.g. a new commonMain module gets added).

## Suggested next moves

In priority order, with reasoning:

1. **O67 subscriber + gossip-bridge wiring.** Logical continuation of
   what I just shipped. Mirror `BlocklistSubscriber` /
   `BlocklistGossipBridge`. Needs a `FilterSubscriptionRepository`
   interface in commonMain + Room adapter in `:app` + in-memory stub in
   `:simulator`. Wire it into the gossip exchange path so subscribed
   lists propagate on contact. Probably 2 commits: repository interface
   + subscriber/bridge code with tests.

2. **O76 compression layer.** Pure code, well-spec'd, no UI. Add
   `expect`/`actual` deflate/inflate, integrate into compose path
   (skip BINARY/FILE per CRIME/BREACH constraint documented in
   `PaddingBuckets.kt`), HELLO `supportedFeatures: ["compression-v1"]`
   capability negotiation, receiver-side decompress.

3. **O87 `PluginContext.signWithLocalKey`.** 4-line addition the O84
   audit identified as the prerequisite for any payment companion app.
   Thread `IdentityManager` into `PluginContextImpl`, expose
   `signWithLocalKey(bytes): ByteArray`, throw on locked identity.

4. **LICENSE file at repo root.** Listed in FDROID_BUILD.md as the
   biggest open prereq before an F-Droid submission. Should be
   GPL-3.0-or-later or AGPL-3.0 based on Rumor's posture; ask the user
   to choose before adding.

5. **Backlog audit round 3 was clean** — don't repeat unless a new
   module lands.

## Backlog state at handoff

- **Counts:** 11 PART · 14 DECISION · 45 TODO (CODE 23 · SIM 2 · UI 9 · EMU 4 · HW 7). Total 70 open rows.
- **Completed gaps:** G1–G26.
- **Open rows touched this session:** O67 (PART, publish/verify added), O76 (PART, padding added), O84 (closed → G26), O87 + O88 (new).

## What's NOT updated and may be stale

- `CLAUDE.md` "Counts as of this writing" line was last updated when
  O76 went to PART (commit `2295950`). The publish/verify additions to
  O67 did not change the row's tag (still PART) or the totals — so the
  count line is still accurate. Verify before editing if you close
  anything.
- `docs/FDROID_BUILD.md` checklist mentions `LICENSE` file as missing
  — still missing. Asking the user is gated by user availability;
  don't add a LICENSE file without their choice.

## Tooling status

- `./gradlew :core:jvmTest` works (verified after every batch this
  session — 4 separate runs, all green).
- `./gradlew :app:testDebugUnitTest` not run this session; the other
  instance ran it on their O39 commit so the Android module should be
  fine, but re-verify before claiming app-side work as complete.
- `./gradlew :simulator:test` not run this session; same caveat.

## Canary

"By Order Of The High Magnate" sign-off was used on every commit
message this session.
