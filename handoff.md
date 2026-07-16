# Handoff — deeper O92 landed; next is O98 MeshView substrate

**Signal to next instance: sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

## Roadmap (user-chosen order)

~~O42~~ → ~~deeper O92 + O105~~ → **O98 MeshView substrate (START HERE)** →
O98 Phase 3 (Wi-Fi Direct wiring). After the chain, ranked (user-agreed):
O100 swarm chunks (wire-format decision — schedule early; per-chunk hashes
are a hard constraint) → O101 sites (make the no-HTML/no-WebView format
decision FIRST) → O103 geotag plugin (comments-not-decay; adopt
osmdroid/MapLibre) → O102 delegation sim experiment (expected to close as a
DECISION).

## Done this cycle (all committed on claude/check-online-status-vef1H)

- **O42 CLOSED** (see G24 + closed O42 row): adaptive size-gated RBSR/bloom
  selection live (`shouldUseRbsr`, threshold 3000, HELLO `knownCount`),
  bloom FP 1%→0.01% + real commons-codec MurmurHash3, single-switch go-live
  (`TransportConfig.rbsrItemsProvider` — advertises `rbsr-v1` iff wired).
- **Hardware regression PASSED on 3 phones** (G24): new wire format, O92
  reseed, store-and-forward across restart+crash multi-hop
  (Moto→Samsung→reinstall→OnePlus), three-node star, adaptive gate on bloom.
  **ColorOS crash found+fixed** (stale manifest receiver → CNFE, `183efee`).
  Field: Moto needs Battery→Unrestricted (O33); Samsung clock still 64d slow.
- **O105 hoist** (`9c8cad7`): `GossipSession` (pure JVM) moved :app → :core
  with its loopback wire-harness test. Same FQN, zero call-site changes.
  Unblocks :node (O106).
- **Deeper O92** (this commit): `messagesForExchange` is now **suspend** and
  = scheduler head (priority/DRR shaping) + **durable-store backfill**
  (`offerable`, dedup by id, capped) — every exchange offers what we HOLD,
  not what survives a volatile queue; the destructive-drain artifact
  (first peer drains, second peer gets nothing) is dead. New
  `GossipEngine.messagesByIds` (cap 500) + `GossipSession.messagesByIds`:
  phase 4 fetches requested ids absent from the capped offer from the store
  — an RBSR peer's exact-diff Request can name things older than any offer
  batch. Wired through `TransportConfig`/`MeshService`. Tests: sequential-
  peers regression (SchedulerReseedTest), outside-offer RBSR fetch
  (GossipSessionWireTest), all suites green.

## Investigation record: the vacuous PerPeerRoutingTest (important context)

Backfill broke `PerPeerRoutingTest."relayed DM … offered only to matched
peers"` — and the investigation showed the test had **passed vacuously since
birth**: its breadcrumb-laying phase never worked (`BreadcrumbCache.record`
is only called from `processIncoming` with a `fromPeerId != senderId` guard;
the test delivered A's message *from A*, so no crumb formed) and
`awaitUntil` **times out softly** (see TestAwait.kt KDoc), so nothing
flagged it. The old green came from the destructive scheduler drain hiding
the DM from the second offer call. Two real fixes landed:

1. **Engine:** backfilled relayed DMs re-derive the O29 restriction at offer
   time (the `intendedPeers` mark lives on the scheduler copy only; the repo
   copy predates the relay decision). Uses the **authoritative suspend
   `candidatePeers()`** (repo-backed), NOT `candidatePeersSync` — the sync
   snapshot can be stale/cold and the filter must see the view relay() saw.
2. **Test:** lays the crumb explicitly via `b.breadcrumbs.record(...)` with a
   postmortem comment.

**Watch for this pattern elsewhere:** any sim test whose setup relies on
breadcrumbs forming from a direct author→receiver exchange is testing
nothing (the guard skips it), and `awaitUntil` won't save it. Known-flaky
under full-suite CPU load (pass in isolation): BreadcrumbSubstrateTest
"relayed message records…", PerPeerRoutingTest "routed hop increments…".

## Where the next instance starts: O98 MeshView substrate (task list #10)

1. **O62 ModeProfile decision** — record per-mode profile (scan duty cycle,
   discovery cadence, session length, batcher window, crumb decay, beacon
   rate, routing weight, storage fraction) in CLAUDE.md **before** any code
   branches on mode (self-imposed gate in the O62 row).
2. Consume inbound SELF_PRESENCE into a peer-mode map with recency decay
   (pure :core + unit tests).
3. BLE-neighbor-list advertisement (new INFRASTRUCTURE gossip) → assemble
   `MeshView` → simulator convergence scenario (20-node harness exists:
   SmartPersistenceScenarioTest).
4. Then O98 Phase 3 on-device (prompt-free joins via deterministic
   networkName+passphrase; channel selection at autonomous createGroup —
   `preferredGoFrequencyMhz()` is the reusable piece).

## Build/test commands (this environment)

Gradle NOT on PATH; Java home as `-D` (never leading `JAVA_HOME=`):

```
/home/user/AndroidStudioProjects/Rumor/gradlew -Dorg.gradle.java.home=/home/user/jdk17 :core:test :simulator:test :app:testDebugUnitTest
```

`adb` at `/home/user/Android/Sdk/platform-tools/adb` (4 phones — see
memory/rumor-test-devices.md; Optimus needs out of charging-only USB mode).
**User does all on-screen UI steps themselves — just ask them** (see
memory/rumor-hardware-test-workflow.md). `.claude/settings.json` is
intentionally modified-but-uncommitted — do NOT commit it.

## O98 Phase 3 design notes (kept)

- Planner emits an undirected backbone; GO/client role + channel are
  transport concerns. Reconciler is NOT thread-safe — single coroutine only.
- A client is in exactly one group; an edge between two would-be-hubs is
  realized by one becoming client of the other.
- Start with `redundancy=1`, raise once stable. Android-agnostic (no OEM
  gating); transport clamps `capacityFor` numbers down, never up.
