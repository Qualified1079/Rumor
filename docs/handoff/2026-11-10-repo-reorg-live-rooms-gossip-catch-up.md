# Handoff — repo reorganised for agents; live three-node Rooms run; gossip catch-up fix (2026-11-10 → 11-11)

Windows machine, away mode from the second half. Everything below is on `main`.

## Repo layout changed — read this first

- **Backlog is one file per row.** Index: `docs/backlog/INDEX.md`. Bodies: `docs/backlog/open/O123.md`. Completed: `docs/backlog/done/G45.md`. `python scripts/backlog.py show O123` prints a row from wherever it lives. `docs/OPEN_BACKLOG.md` and `docs/COMPLETED_GAPS.md` no longer exist.
- **`CLAUDE.md` is 18.5 KB** (was 43 KB) and no longer holds the index. `BacklogConsistencyTest` fails if it passes 24 KB or regains tier tables.
- **Module rules** are in `core/CLAUDE.md`, `app/CLAUDE.md`, `simulator/CLAUDE.md`, `node/CLAUDE.md`. `docs/README.md` maps every doc.
- **`GossipEngine.kt` is being split (O250)** by a subagent on branch `worktree-agent-aecb9001a32b7dc91` (worktree under `.claude/worktrees/`). Merged so far: `OutboundComposer`, `RoomMessaging`, `DirectMessaging`. A `PresenceBeacons` move was in progress when this was written. If that branch has commits `main` lacks, check they pass and fast-forward; then close O250.
- **JDK 17** is at `~/.jdks/jdk-17.0.21+9`; Gradle finds it with no flag.
- **CI** has two jobs: `test` (gating, about 6 minutes) and `real-wire` (`:simulator:realWireTest`, non-gating). Both green on `307d327`.

## Bugs found by running three `:node` instances in a line (A—B—C) plus the Moto

All fixed, each with a test that was red first.

| What | Row |
|---|---|
| A node that learns a room late got its history newest-first; invite/ban/unban were dropped before the CREATE and dedup-marked, so its roster stayed empty for good | G84 (was O213) |
| Room posts/creates/invites/actions were never stored by their composer, so only the first peer to open a session got them | G85 |
| A moderator could "invite" a banned user into its own roster only | G85 |
| A late joiner received only the freshest 200 messages and stalled (bloom/id-list path, every store under 3,000) | G88 |
| Own delivery receipts had the same first-peer-only defect | G89 (was O255) |

**Rooms reached the phone for the first time.** Moto `ZY22KP7F59` on the debug build of `43a07ee`, joined over LAN to a fourth node, two hops from the room's creator: its Room DB holds the room with the moderator, a member and a ban (`rooms`, `room_roster`). No phone UI exists for rooms yet, so this was read from the database (`adb exec-out run-as com.rumor.mesh.debug cat databases/rumor.db`).

## Open, filed this session

- **O253** — liveness window values (90/45/15 s) shipped without the user sign-off they were flagged for. Needs the user.
- **O256** — `composeRoomMessage` says "posted" for a post every receiver drops.
- **O258** — a relayed DM outside the freshest-200 window is never re-offered (likely cause of the 1-in-5 flood-test failure); RBSR by-id requests bypass the per-peer routing filter.
- **O208** (remainder) — make `real-wire` gating once it has history; `LivenessWindowModeAwarenessTest` races its own relay under load.

## Not done / watch for

- `WifiDirectTransport.kt` was not split: O248 says not standalone, and one phone cannot verify group formation.
- `SimTransport` still takes-then-filters its offer, so simulator scenarios with stores over 200 model the old catch-up behaviour.
- Room authority is a display filter by design: a non-member's post is dropped at every inbox but still relayed and stored mesh-wide. Nothing was changed there.
- Scratch nodes may still be running on this PC (HTTP 8181–8184). Kill the `java -jar node.jar` processes if they are in the way.
- The away-mode flag `~/.claude/away-mode` is on; remove it to stop the loop.

## Where work stopped (2026-11-11, user asked me to stop; away mode turned off)

Pushed to `main` after the section above was written, all with CI green on `31d2a94`:
- `0d7fed8` BRIDGED refused on the by-id serve path (`GossipEngine.messagesByIds`); test added to `BridgedNotServedTest`.
- `2819b04` first-batch merge of the split commits `PresenceBeacons` and `OfferSelector`.
- `31d2a94` a carried DM keeps a slice of the store-backfilled offer (G90, `RelayedDmOfferSliceTest`); `LivenessWindowModeAwarenessTest` made deterministic (G91). O258 is now only the by-id routing-filter question; O208 is only "make `real-wire` gating after ~10 green runs".

**Uncommitted in the main checkout, O229 (`MeshRuntime` lifecycle), NOT finished, do not commit as-is:**
- `core/.../runtime/MeshRuntime.kt`: `start()` creates a child `SupervisorJob` it owns and returns early if already running; `stop()` cancels it and clears the `presencePulse` and `hlc.onAdvance` hooks; `DebouncedHlcStore` timer resets `scheduled` in a `finally`, and `flushNow()` writes under the lock.
- `app/.../service/MeshService.kt`: `startMesh()` launches everything into a `meshScope` (child job `meshJob`) that `stopMesh()` cancels. Compile not yet checked for `:app`.
- `core/.../runtime/MeshRuntimeLifecycleTest.kt`: four tests. Against the old code three were red (stop, double start, debounce after scope death). Against the new code **one still fails**: `a save after the debounce scope died still reaches the store`. Cause: `scope.launch` on a cancelled scope returns an already-cancelled job whose body never runs, so the `finally` never resets `scheduled` and the `timer.isCancelled` branch in `save()` is checked before the job has been marked cancelled in some orderings, or the `finally` is skipped entirely. Simplest fix: in `save()`, check `scope.isActive` first and write through when it is false. Then run `:core:test`, `:node:test`, `:app:compileDebugKotlin`, then close O229 with `scripts/backlog.py`.
- Nothing about O229's `:node` shutdown hook (O247) was touched.

**O250 split, state of the subagent branch `worktree-agent-aecb9001a32b7dc91`** (worktree `.claude/worktrees/agent-aecb9001a32b7dc91`):
- Already on `main`: `OutboundComposer`, `RoomMessaging`, `DirectMessaging`, `PresenceBeacons`, `OfferSelector`.
- Committed on the branch but NOT on `main`: `e9fca80` (`TransferControl`) and `46c9f2d` (`MessageDeletion`). Cherry-pick them, rerun `:core:test :simulator:test :node:test :app:compileDebugKotlin`, then push.
- Uncommitted in that worktree: a `BridgeTraffic.kt` move plus edits to `GossipEngine.kt` and `SentAtMsLintTest.kt`. Unverified. The agent was cut off by a rate limit; resume it with `SendMessage` to `aecb9001a32b7dc91`, or finish by hand. `GossipEngine.kt` is about 1,240 lines on `main`, down from 2,091.

**Left running on this PC:** scratch `:node` instances on HTTP ports 8181-8184 (data under the session scratchpad `nodes/`). Stop them with `taskkill //F` on the PIDs from `netstat -ano | grep LISTENING | grep :818`.

**Needs the user:** O253 (liveness window values 90/45/15 s), and the O173 sybil-capture decision from earlier handoffs.
