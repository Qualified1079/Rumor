# Handoff — marathon field+fix session (2026-07-18 evening): echo loop dead, DM crypto fixed, O114+O95 closed, fleet on 0.6.8

Everything committed AND pushed; all suites green; fleet (Samsung R58M30FSJKE /
Moto ZY22KP7F59 / OnePlus ec5b0707) flashed **0.6.8-hlc-long-counter (vc29)**
together. No known-failing tests, no partial fixes in flight — clean stop.

## What shipped this session (chronological, all field-verified unless noted)

1. **0.6.2 — echo-loop fix completed** (finished b42c4ed's punch list):
   SelfPresenceTest rewritten to the ephemeral-beacon contract;
   `MessageRepository.deleteByType` across all 4 impls + startMesh purge of
   accumulated SELF_PRESENCE rows; DM-decrypt exception logging. Field: echo
   loop GONE (0/0 sessions, bytes=0, stores purged of ~1500 beacon rows each).
2. **0.6.3 — THE DM decrypt bug found and fixed in minutes** thanks to the new
   logging: `AEADBadTagException` because **MessageEntity had no `ext` column**
   — Room silently stripped `_ext` (compression AAD flags, sealed-sender tags,
   room tags, thread metadata) from every stored AND relayed message. Schema
   v9→v10, `ext` JSON-blob column, `MessageEntityExtRoundTripTest`. Invisible
   to sim/tests (in-memory repo keeps the object). Recorded in CLAUDE.md
   Critical bug history with the rule: new RumorMessage field ⇒ same-commit
   entity column + round-trip test. Field: user-confirmed DM decrypt success;
   all 3 DBs show `_ext` intact incl. on relayed copies.
3. **0.6.4/0.6.5 — O124 (search-button announce+solicit)**: scan button now
   fires an immediate SELF_PRESENCE pulse + transports restart;
   `GossipEngine.presencePulse` hook + `PresenceReplyGate` (responder-clock
   per-peer cooldown, user-tuned 2min→30s, only for unknown-or-stale peers via
   `MeshViewTracker.hasFresh`). Root cause it fixes: a freshly-wiped node is
   invisible until its FIRST completed exchange (O97 field note filed).
4. **0.6.6 — O98 dead-group client re-bootstrap**: field-diagnosed ~3-min
   blackout (Moto radios off → OnePlus exhausted retries → went passive).
   Fixes: retry exhaustion kicks `rediscoverPeers()` + clears attempt grace;
   Client-role discovery guard escape hatch (no backbone SSID on air for the
   whole BACKBONE_MEMORY window → bootstrap-by-hosting; junior-yields resolves
   double-host). Field-verified full re-convergence in 62s from first radio-on,
   including a live double-host + junior-yield + blind-join sequence.
5. **O114 → G41**: simulator determinism — `SimNode` wraps its scope with
   `limitedParallelism(1)` (per-node serialized handlers); 19/20 then 12/12
   parallel full-suite runs green. Pattern recorded: awaitUntil the LAST
   observable effect; absence assertions need a settle delay.
6. **O95 → G42 (0.6.7/0.6.8)**: **HLC on the wire.** `_ext.hlc =
   "wall:counter"` stamped in buildMessage (after signing), folded in
   processIncoming after verification; `HlcClock` thread-safe with
   restore/onAdvance persistence (SharedPreferences in MeshService); thread
   display sort `(min(hlcWall, receivedAtMs+48h), counter, id)` with
   displayTimeMs fallback. Two-layer defense: clock bounds only absurdity
   (10y forward + counter sanity — a tight clamp would break the 64-day-slow
   motivating case, since that node sees every CORRECT peer as far-future);
   tight future-pinning clamp lives at display against receivedAtMs.
   **Pin-attack analysis on the G42 row**: sybil-irrelevant (max-fold not a
   vote; ONE hostile stamp pins the mesh), causality survives as a Lamport
   clock under pin, display unaffected — accepted per O51/O60 floor.
   **0.6.8**: counter Int→Long + ceiling 1e15 (user probe exposed that under a
   pin every node crosses an Int-scale ceiling in weeks → peers reject legit
   stamps → causality breaks; Long makes it unreachable). Wire is fleet-only,
   so the format change was free. HLC field-verifies passively via normal use.

## Standing directives recorded this session

- **Prebuilt-first reflex** (memory `prebuilt-first-reflex`): before coding
  anything new, check for a tested existing impl; say so at plan time.
  (Session origin: LocalOnlyHotspot-vs-O98 review — verdict: LOHO is
  system-app-gated on credentials/channel and prompts on client joins; the
  credentialed autonomous createGroup IS the closest legal equivalent.)
- Queue ordering: **smallest → largest**. O114 ✓, O95 ✓ — **next is O100**
  (content-addressed chunk identity first — the row says do the wire-format
  decision BEFORE more chunk traffic accretes on random transferIds; then
  multi-source assembler; sim comparison of fetch mechanisms A/B/C).

## Next-session pointers

- **O100 is the next queue item** (multi-session-sized; CLAUDE.md row carries
  the full design brief incl. the per-chunk-hash hard constraint).
- Samsung client-churn (O94 note) is worth a dig while the fleet topology
  still reproduces it — logs from the observation are described on the row.
- The 2026-07-18 audit residue rows (O115–O123 minus closed ones) remain,
  tiered in CLAUDE.md; several are one-sitting (O120 prune loops, O121 pins).
- Fleet passphrase + workflow: see memories `rumor-test-devices` /
  `rumor-hardware-test-workflow` (user does on-screen steps; adb is ours).

---

# Prior-session handoffs — archived

The current handoff is above. Earlier session handoffs were removed (2026-07-19)
once their work landed — every outcome is recorded durably elsewhere: shipped
work in `docs/COMPLETED_GAPS.md` (G-rows), open follow-ups as O-rows in
`CLAUDE.md`, and the full prose in git history (`git log -p handoff.md`). The
trail, newest first, so a specific session is findable in history:

- 0.6.8 marathon (2026-07-18 eve) — echo loop dead, DM `_ext` bug fixed (schema v10), O114/O95 closed → **this is the current entry above**
- echo-loop fix PARTIAL (2026-07-18) — superseded by 0.6.2 in the current entry
- O93 LAN transport + O98 blind join + §5 guard (2026-07-18) — shipped as G40
- overnight audit rounds 1–3 (2026-07-18) — fully filed as O114–O123 in CLAUDE.md
- O80 mode auto-fire / O57 closed (2026-07-17) — shipped as G39
- O98 Phase 3b field-verified + code-complete (2026-07-17) — shipped as G38
- O62 done, O98 Phase 3a, §2 ingest fix (2026-07-17) — shipped as G37 / bug history
- THE MERGE: archimedes + check-online → main (2026-07-16) — shipped as G30, catalog in `docs/ARCHIMEDES_MERGE_CATALOG.md`
- overnight audit (2026-07-16) — findings triaged into O-rows

**Handoff hygiene going forward:** keep only the current session entry (state,
next steps, standing directives). When a session's work lands, its handoff prose
is superseded by the durable record — trim it to a one-line index entry rather
than letting the file accrete full session logs.
