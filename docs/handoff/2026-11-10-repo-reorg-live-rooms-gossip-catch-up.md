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
