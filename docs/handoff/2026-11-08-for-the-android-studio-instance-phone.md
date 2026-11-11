# Handoff — for the Android Studio instance: phone work starts here (2026-11-08)

**You can do what the last three cloud sessions could not: build `:app` and reach
the phones.** Everything below is pushed to `main`. Plain-English reports:
`docs/handoff/eli5/SESSION_ELI5_2026-11-06.md` and `docs/handoff/eli5/SESSION_ELI5_2026-11-08.md`.

## First: build + verify the `:app` lines no one has compiled

The cloud container has no Android SDK, so these `:app` changes were hand-reviewed
only. Build `:app`, run its unit tests, then reflash the fleet:
- `app/.../data/TransferDao.kt` — prune query status literals now uppercase (O249).
- `app/.../di/AppModule.kt` — `modeState = get<ModeState>()` into GossipEngine (O205,
  merged from the away branch; never compiled anywhere).
- Everything in `:core` that `:app` consumes changed this week (G65–G70). `:core`
  itself is green (512) — `:app` compile is the open question.

## Then: watch on the phones for the two untestable G68 pieces

Transfer reassembly is now serialised per transfer and the NACK watchdog cancels
itself LAST. Neither race is reachable on the JVM (in-memory repos never suspend).
On a phone (Room suspends): send a few files between phones and confirm each
reaches COMPLETE in the Transfers screen. A file whose chunks all arrive but that
never shows "Done" is the symptom.

## Then: the phone backlog, in order

1. **O187** Room lifecycle on Android — schema bump to v13 (commit `13.json` in the
   same commit, O116), Room DAO/adapter for `RoomRepository`, UI. Rooms ship in v1
   (O210).
2. **O185** `ROOM_MESSAGE` triggers two blocking SQLite reads (`runBlocking` in
   `AppModule.kt` `RoomSubscriptionProvider`) — observe/cache instead.
3. **O216** no join path for rooms (implements O214).
4. Field-visible from the audit: **O235** (BLE advertisers orphaned on stop-during-
   start; peer-nearby signal sticks), **O237** (auto-mode flaps STATIC↔FREE on
   screen on/off, restarting BLE each time) — both easiest to confirm in logcat.

## Repo tooling changed today — use it

- **Backlog edits go through `scripts/backlog.py`** (`next-id`, `file`, `close`, `counts`,
  `check`). It needs Python; if this machine has none, edit by hand — but
  `BacklogConsistencyTest` in `:core` now fails the suite on index/body drift, so
  you'll find out. Push a new row number *before* the work it describes.
- **Handoffs are one file per session** under `docs/handoff/`; add yours there and
  one line at the top of `handoff.md`'s list.
- `CLAUDE.md` is now a true index (62 KB → ~41 KB); bug history, bridges and
  dependency policy moved to `docs/BUG_HISTORY.md`, `docs/BRIDGING.md`,
  `docs/DECISIONS.md`.

## Refactors deliberately left for you (you can build `:app`; the cloud can't)

- **O250** split `GossipEngine.kt` by concern, keeping its public API stable across
  each move; one concern per commit.
- **O141** strip session provenance from code comments; `:app` and `:core` in
  separate commits.
- Do these *after* the phone work above, not before — they touch every file the
  Rooms work touches.

## Coordination

A cloud session (`rumor-38`) did this week's work. Follow
`docs/MULTI_INSTANCE_COORDINATION.md` (fetch + fast-forward before each step, one
row per commit, row counts only in the closing commit). Fleet facts (serials,
passphrase, clock offsets, adb quirks) are in `handoff.md` §1.

## Housekeeping

- `away/2026-10-11-overnight` is fully merged but still exists on GitHub (the cloud
  git proxy refused the delete with 403) — delete it from the branches page.
- Known full-suite load flakes: `LanLivenessValidationTest`, and once
  `TransferAssemblerHardeningTest` (10 s register wait). Both pass alone.
