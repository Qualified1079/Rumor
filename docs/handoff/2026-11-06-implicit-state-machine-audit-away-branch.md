# Handoff — implicit-state-machine audit, away-branch merge, four security fixes (2026-11-06)

**Interactive session on `main`, everything pushed, one commit per row.** Plain-English
after-action report for the user: `docs/handoff/eli5/SESSION_ELI5_2026-11-06.md` (read that first if
reviewing what changed).

## What happened

- **Audit:** catalogued implicit state machines (lifecycle spread across loose flags,
  nullable jobs and parallel maps) across all four modules; filed **O229–O249**. Each
  row says which findings were verified against code and which are reader-reported —
  verify the latter before building on them.
- **Merged `away/2026-10-11-overnight`** (`8f4b00e`). Its O208/O209/O210 → O219/O220/O221
  and its G52–G57 → G55/G60–G64 (the handoff had reserved only G55; G56/G57 collided
  too). Its relay-side O186 drop was discarded for `main`'s `MessageStore` one.
- **Fixed:** O233 → G65 (DIRECT_ACK receipts bound to our own DM + its recipient),
  O234 → G66 (priority ACCEPT only against our own request), O232 half → G67 (transfer
  cancel bound to its receiver; broadcast = per-receiver opt-out), O231 half → G68
  (hash-mismatch wedge → re-fetch ×2 then FAILED; cancel guarded; reassembly
  serialised; watchdog cancelled last). O249 filed + its Room query literals fixed
  (`:app`, hand-reviewed). One test race fixed (`TransferNackWatchdogTest`).
- **Verification:** `:core` 506/0, `:node` 4/0, `:simulator` 163 with only
  `LanLivenessValidationTest` red under the full suite (passes 3/3 alone — the known
  load flake). Every fix teeth-checked.

## Read before continuing

- **G68 has two untested pieces** (reassembly serialisation; watchdog cancel-last).
  In-memory repos never suspend, so neither race is reachable in a JVM test. Watch
  for transfers that complete but never show COMPLETE on a phone.
- **O234's handshake has no caller in `:app`** — priority peers come from the O98
  backbone plan only. Closing the hole removed no feature.
- **Next (user's plan):** step 3 = phones, via a shared folder with another instance;
  O187 Android persistence → O185 → O216 is the recommended order (O210 decided Rooms
  ship). `:app` cannot be built in this container.
- **Build here:** Maven Central 429s this container's IP even for `curl`. Google's
  mirror works: an init script adding
  `maven { url 'https://maven-central.storage-download.googleapis.com/maven2' }` to
  `dependencyResolutionManagement` and `pluginManagement` (scratchpad only, never
  committed) — see §1's container notes.
