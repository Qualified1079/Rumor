# Handoff — overnight away session: test honesty, transfer coverage, sybil measurement (2026-10-11)

**Branch `away/2026-10-11-overnight` — merged into `main` 2026-11-06.** It was built
off a stale `main` by a session running concurrently with 2026-10-10's, so at merge its
numbers were renumbered (O208/O209/O210 → O219/O220/O221, G52–G57 → G55/G60–G64 —
already applied in the text below) and its relay-side O186 drop was discarded in favour
of `main`'s `MessageStore` drop (its `FeedTextOnlyEnforcementTest` was kept). 16 commits.
Plain-English review doc for the user: `docs/SESSION_ELI5_2026-10-11.md`.

## ⭐ TOP PRIORITY FOR NEXT INSTANCE — two user decisions are blocking, don't guess

Earlier `TOP PRIORITY` markers further down this file are **superseded** (the
mid-path-dropout test they point at was finished on 2026-08-03).

1. **O173 — sybil backbone capture. Measured, deliberately NOT fixed.** 8 fake
   identities declaring `UserMode.FREE` capture the ENTIRE backbone of a 20-phone
   mesh (0 honest↔honest links; all 20 isolated if the sybils defect). Control: the
   same identities declaring MOBILE take 2 slots and leave it spanning. Adjacency
   claims need no reciprocity either — `assembleView` keeps an edge whenever the
   *named* endpoint is a known beaconing node, so a node that has never been in
   radio range of anyone gets all 20 claims into the view and the planner turns them
   into real link assignments. **Do not implement a mitigation unattended.** The
   obvious one (rank by observed relay history) breaks the planner's one hard
   property — agreement without a coordinator — because first-hand evidence is
   per-node, so A and B would compute different plans (the O99 fragmentation).
   Options (a) reciprocity, (b) local no-history clamp, (c) earned capacity, with
   costs, are in the O173 row in `docs/OPEN_BACKLOG.md`. The numbers are the
   baseline any fix must beat: `PersistencePlannerSybilBiasTest`.
2. **O205's three window values (90 s / 45 s / 15 s)** are a judgement call — "3
   contacts of grace", the same reasoning the old fixed 30 s encoded for a 10 s
   cadence. If the user wants 2 or 5 contacts it is a one-line change
   (`ModeEnvelope.LIVENESS_WINDOW_ROUNDS`).

## The theme that produced everything else: tests that proved nothing

The user's one mid-session instruction was *make sure a test's result actually means
what it claims — "0 failures" can mean "0 of the checks ran"*. Acting on it is what
found the session's only real product bug.

- **`awaitUntil` returned silently on timeout** (G61). At ~30 call sites it gates a
  PRECONDITION, so a silent timeout ran the test against a state it was never
  written for. `PerPeerRoutingTest` failed 2 of 4 runs on an **unmodified tree**;
  `SelfEchoTest` was **vacuous** — it awaited a condition that could never hold
  (SELF_PRESENCE ingests `persist = false`, so it never enters the repo) and then
  asserted an absence that holds for free. It would have passed with the self-echo
  guard deleted. Timeout now throws and names the subject; `docs/SIMULATOR_TESTING.md`
  carries both new rules.
- **The method, now the house standard here:** every fix is verified by *deleting
  the thing it fixes* and confirming the matching test fails. 11 for 11 on the
  transfer work (G63). It caught two of my own tests that proved nothing — a
  plausibility test asserting before the hostile input was processed, and a sender
  test using a 40-byte payload when `Chunker` defaults to 60 KB chunks (so it was a
  one-chunk transfer and the NACK for index 1/3 was correctly ignored).
- **An absence assertion needs a positive control in the same test** proving the
  input the defence rejects actually arrived. Two real-wire tests were silently
  passing under load without one (G62).

## Closed this session → `docs/COMPLETED_GAPS.md` G55–G64

O205 (mode-aware liveness window), O219 (**the real bug**: a relay withheld a routed
DM from its own recipient), O220 + O221 (test-honesty sweeps), O159 (transfer-layer
coverage incl. making the 60 s NACK watchdog testable in 40 ms), O177 (was already
fixed — the row was drift).

## Environment facts that cost time; check these first in a cloud container

- **No `adb`, no Android SDK, no route to the user's LAN.** The user believed three
  phones were connected; they are connected to *their* machine. So `:app` could not
  be compiled, let alone tested, and every `[TODO/EMU]` / `[TODO/HW]` row and any
  fix living in `:app` was out of scope. **The one `:app` line this branch touches
  (O205's `modeState = get<ModeState>()` in AppModule) has never been compiled.**
  For device work the session must run on the machine the phones are plugged into.
- **No JDK 17** — `JAVA_HOME=/home/user/jdk17` from the previous handoff did not
  exist (fresh container has only JDK 21; the toolchain pins 17). Downloaded Temurin
  17 — §1 now says to put it in the session scratchpad.
- **`LANG` must be set.** A test name contains an em dash, and under the container's
  POSIX locale the Kotlin daemon dies with `InvalidPathException` writing the class
  file. Every build command needs
  `export LANG=C.UTF-8 LC_ALL=C.UTF-8 JAVA_HOME=/home/user/jdk17`. Worth making the
  build robust to this (`kotlin.daemon.jvmargs` in `gradle.properties`) rather than
  re-learning it — not done, the user's call since it is shared build config.
- Maven Central rate-limited (HTTP 429) for ~20 min at session start. It clears; retry.

## Suite reliability — read before trusting a red run

`:simulator:test` takes ~5–6 min and the two `LanMeshHarness`-based classes
(`LanLivenessValidationTest`, `LanMeshHarnessTest`) are **timing-sensitive on a
4-core box**, failing roughly half of full-suite runs before this session's work. I
verified that is environmental, not a regression, by reproducing on a pristine tree
with my changes stashed. Mitigations shipped: a readiness gate (distinguishes "the
harness never formed" from "routing failed"), a progress control instead of a fixed
delay, and patience derived from a measured probe (`LAN-CALIBRATION` line in the
output; ~20.5 s carry → ~123 s budget on an idle box). **If one of these two classes
goes red, re-run it in isolation and read the calibration line before concluding
anything about routing.** The structural cause is `forkEvery = 15` in
`simulator/build.gradle.kts` — these classes share a JVM with up to 14 other
scenario classes' live nodes. Lowering it is untested.

## Next-session candidates (no device needed)

- **Doc-drift chore, bounded:** `CLAUDE.md`'s index still lists ~12 rows from
  earlier sessions as open that are `[DONE]` in their own text (O182, O144, O156,
  O157, O162, O166, O170, O172, O180, O132, O136, O138). The convention is that a
  closed row moves to `COMPLETED_GAPS.md` and leaves the index. I moved only this
  session's rows — deliberately, to stay in scope — so the index is now consistent
  for 2026-10-11 and still stale for those. Part of O122.
- O159's successor is closed, but `TransferAssembler.attemptReassembly` and the
  swarm paths (O100) remain untested.
- O186 remainder: the per-room `allowMedia` policy is gated on O187 (Rooms unbuilt),
  and the feed's own identity is an O154 decision. The enforcement point is in
  `MessageStore` (main's implementation; the branch's `processIncoming` copy was
  discarded at merge) — extend it, never delete it.
- O102 (neighbour-aware forward delegation), O112 residue (hostile-string corpus
  through the remaining entry points).
- **Do NOT touch** `RelayEvictionModelTest` — the 2026-08-03 handoff marks it
  USER-REVIEW because reworking it changes O193's published conclusions.
