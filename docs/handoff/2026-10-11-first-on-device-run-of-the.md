# Handoff — first on-device run of the 10-10 tree; O98 field round; presence (2026-10-11, interactive)

**Interactive, on `main`, phones attached** (see §1 for the Windows/adb setup).
Commits: `2d7cef1` `f163fdf` `e324f55` `ffe87a3` `36a16a9` `329ad63` `3c89609`. Closed as
G56 (first `:app` compile + identity onboarding), G57 (O98 field round), G58
(presence), G59 (the seven 10-10 `:app` rows on hardware); full detail lives in
`docs/COMPLETED_GAPS.md`. Filed O222–O228.

## User directives this session (apply them)

- **After every change, reason about how it could break or be abused** and say
  so before calling it done (saved to memory). Two of my changes needed it: a
  scan receiver that spun at ~40ms, and a clock-offset presence fix the user
  correctly predicted would break on a backward clock jump (replaced with
  monotonic ages before it was pushed).
- **Unresolved issues you raise → OPEN_BACKLOG; completed → COMPLETED_GAPS and
  out of the handoff.**

## Pending, in priority order

1. **The merge check never fired in the field** (cage re-run 20:33–20:42;
   presence was verified in the same run — G58). Mesh re-formed ~2 min after
   uncage via planner view decay instead. Cause and fix candidate are in O223
   (persist HELLO-verified host names across restarts, bounded). To re-test,
   the returning phone must be one the scanning client has HELLO'd this run.
   The cage that worked: sealed, several metres away (the first attempt leaked
   at ~-48 dBm and kept the link up).
2. **The seven `:app` rows:** done except the bridges — G59. O169/O168 need a
   radio or a test-dep decision (O227).
3. **O198 liveness two-phone A/B** (still never run; the mesh now forms, so it
   is possible).
4. The O173 decision is now also the place for O222(a).

## Things that look wrong but aren't

- After `install -r` the apps restart; the OnePlus/Moto auto-unlock (O138, now
  off the main thread — the Unlock screen shows for a few seconds first).
- A freshly-cleared phone has no contacts, so it has no trusted names: it can
  still join (HELLO-checked) but won't stand down for anyone until it has
  completed a session — by design (G57 trust split).
