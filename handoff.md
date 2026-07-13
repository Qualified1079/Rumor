# Handoff — O98 Smart Persistence

**Signal to next instance: sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

## Where things stand

O98 (smart persistence) is being built in three phases, algorithm-first — the
opposite of the O99 approach that was tried on-device and reverted (negotiated
`connect()` ignores the channel hint; naive host-election fragments; createGroup
joins hit WPS prompts). See the O98 row in CLAUDE.md for the full field record.

- **Phase 1 — DONE, committed, all tests green.** Pure deterministic backbone
  solver + hysteresis reconciler in `:core`.
  - `core/src/main/kotlin/com/rumor/mesh/core/routing/PersistencePlanner.kt`
  - `core/src/main/kotlin/com/rumor/mesh/core/routing/PersistenceReconciler.kt`
  - Tests: `core/src/test/kotlin/com/rumor/mesh/core/routing/{PersistencePlannerTest,PersistenceReconcilerTest}.kt` (13 tests)
- **Phase 2 — DONE, committed, all tests green.** Simulator validation.
  - `simulator/src/test/kotlin/com/rumor/mesh/simulator/engine/SmartPersistenceScenarioTest.kt` (5 tests, 20-node apartment building)
- **Phase 3 — NOT STARTED.** This is where you begin.

## START HERE (Phase 3 — Wi-Fi Direct wiring, the fragile part)

The algorithm is proven and radio-agnostic. Phase 3 connects it to
`app/src/main/java/com/rumor/mesh/core/transport/wifidirect/WifiDirectTransport.kt`.
Do this incrementally with on-device regression testing — it touches the
hard-won G18/G22/G19 connection flow. Three devices are on USB (see
`memory/rumor-test-devices.md`, app passphrase `passphrase1`).

Concrete first steps, smallest-risk first:

1. **Assemble the `MeshView` on-device.** Feed the planner real inputs:
   - `modes`: local `UserMode` (O57) + peers' modes from SELF_PRESENCE beacons.
   - `edges`: who-can-BLE-see-whom. Advertise each node's neighbor list as a
     small INFRASTRUCTURE gossip message so views converge (like presence).
   - Run `PersistencePlanner.plan(view)` → `PersistenceReconciler.reconcile(plan)`
     on the mesh scheduler coroutine (reconciler is NOT thread-safe — single
     coroutine only). Act on `PlanDelta.toAdd` / `.toRemove`.
2. **Realize a backbone edge as a link.** `persistentPeersOf(myUserId)` gives the
   peers to hold. Map to the existing persistent-link machinery (G19). GO/client
   role for an edge stays the existing first-come / lower-userId rule.
3. **Prompt-free joins (blocker from O99 spike).** createGroup joins hit a WPS
   invitation-accept on some devices (Moto wouldn't auto-join OnePlus's ch149
   group → 0 sessions). Derive a deterministic pre-shared `networkName` +
   `passphrase` from the pair's userIds so the client joins as a legacy STA
   without WPS PBC.
4. **Channel selection at createGroup (absorbed O99).** Only autonomous
   `WifiP2pManager.createGroup(channel, WifiP2pConfig.Builder()…setGroupOperatingFrequency(quietFreq)…, listener)`
   honors the channel (field-verified — `connect()` ignores it). Scan
   `WifiManager.getScanResults()` for the least-congested non-DFS 5 GHz channel
   (candidate MHz: 5745/5765/5785/5805/5825/5180/5200/5220/5240, UNII-3 first).
   The `preferredGoFrequencyMhz()` scanner is the reusable piece.

Keep it **android-agnostic** (user's standing constraint — no Xiaomi/OEM-specific
gating; they'll test other hardware later). Capacity numbers in
`PersistencePlanner.capacityFor` are deliberately conservative; the transport
layer should clamp further to what a given radio actually sustains, not raise them.

## Build/test commands (this environment)

Gradle is NOT on PATH. Java home must be passed as `-D`, NOT as a leading
`JAVA_HOME=` (breaks the permission match):

```
/home/user/AndroidStudioProjects/Rumor/gradlew -Dorg.gradle.java.home=/home/user/jdk17 :core:test --tests "com.rumor.mesh.core.routing.*"
/home/user/AndroidStudioProjects/Rumor/gradlew -Dorg.gradle.java.home=/home/user/jdk17 :simulator:test --tests "com.rumor.mesh.simulator.engine.SmartPersistenceScenarioTest"
```

`adb` works via absolute path (allow rules already granted). `.claude/settings.json`
is intentionally modified-but-uncommitted (permission allowlist) — do NOT commit it.

## Design notes worth keeping in mind

- The planner emits an **undirected** backbone; it does not decide GO vs client
  or channel — those are transport concerns by design.
- Wi-Fi Direct reality the abstract graph doesn't model: a client is in exactly
  one group; a phone can't be client of two GOs on one radio. A backbone edge
  between two would-be-hubs is realized by one becoming client of the other.
  True inter-group bridging (or same-LAN mDNS per O93) is a deeper follow-up.
- Redundancy: `plan(view, redundancy=2)` gives leaves a second link so no single
  drop partitions them. Phase 3 can start with `redundancy=1` (tree) and raise it
  once stable.
