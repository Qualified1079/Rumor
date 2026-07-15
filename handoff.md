# Handoff — foundational roadmap (reliability first, then O98 Phase 3)

**Signal to next instance: sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

## Roadmap (user-chosen order)

O42 remnants → deeper O92 → O98 MeshView substrate → O98 Phase 3 (Wi-Fi Direct wiring).

## Done this cycle (committed on claude/check-online-status-vef1H)

- **O92 reseed-from-store** (`f375d02`) — restart no longer wipes the offer set.
  `GossipEngine.reseedFromStore()` reseeds Scheduler (repo `offerable()`) +
  DuplicateFilter (repo `knownIds()`) from `MeshService.startMesh`.
- **Dead `:app` test suite revived** (`372a9db`) — junit-vintage-engine was missing;
  12 JUnit4 files never ran. 5 stale tests fixed + 1 real bug
  (`NeighborStore.selectDiverse` limit=1 was pure-random, not lowest-overlap).
- **O42** (`b13c157`) — fingerprint hardened to negentropy construction
  (additive mod 2^256 + count; XOR was forgeable). Bloom FP 1% → **0.01%**
  (chat UX: skip rate 1-in-100 → ~1-in-5000–8000, <2 KB on small sets).
  Size-gated adaptive selection built and sim-validated:
  `shouldUseRbsr(bothSupport, max(sizes) ≥ RBSR_MIN_SET_SIZE=3000)`, symmetric
  via new advisory `knownCount` in HELLO. Crossover measured ≈2.5k msgs
  (JSON framing; see `RbsrBandwidthScenarioTest`).

## Remaining work, in order

1. **O42 go-live wiring** — RBSR is dormant in production
   (`LOCAL_SUPPORTED_FEATURES` empty, `rbsrItems` null). Go-live must set both
   **atomically** in `MeshService`: advertise `rbsr-v1` AND provide the
   `rbsrItems` snapshot (invariant: advertise iff items wired, else two
   honest peers could split modes). Then hardware regression.
   **⚠ Wire-compat note for hardware testing:** the bloom FP change is a *soft*
   wire break — `BloomFilterData.deserialize` rebuilds at the local default
   rate, so an old-build peer's filter is misparsed by a new build (wrong bit
   layout → garbage overlap for that exchange, no crash). Flash **all** test
   phones with the same build.
2. **Deeper O92** — source `messagesForExchange` from the durable repo
   continuously (scheduler stays as the priority/fairness shaping layer;
   today's `scheduler.take()` is still destructive within a run, reseed only
   heals restarts). Pairs with O40 (relay-deletion-on-ACK) since there's no
   per-message delivered state to prune on. Benefits from RBSR being live for
   cheap large-set diffs. **Bundle O105 here** (hoist GossipSession :app→:core,
   structural — same files touched).
3. **O98 MeshView substrate** — O62 ModeProfile decision (gate: no mode
   branching lands until the profile is recorded in CLAUDE.md) → consume
   SELF_PRESENCE into a peer-mode map with recency decay → BLE-neighbor-list
   advertisement (new INFRASTRUCTURE gossip) → assemble `MeshView` →
   simulator convergence scenario.
4. **O98 Phase 3** (the fragile on-device part) — realize planner edges via
   G19 link machinery; prompt-free joins (deterministic networkName+passphrase
   from pair userIds, legacy-STA join, no WPS); channel selection at
   autonomous `createGroup` (only path that honors the channel — field-verified;
   `preferredGoFrequencyMhz()` scanner is the reusable piece). Four USB
   phones, see memory/rumor-test-devices.md, passphrase `passphrase1`.
5. **After the chain, ranked (user-agreed 2026-07-15):** O100 content-addressed
   swarm chunks (wire-format decision — schedule early; per-chunk hashes are a
   hard constraint) → O101 sites (make the no-HTML/no-WebView format decision
   FIRST) → O103 geotag plugin (comments-not-decay; adopt osmdroid/MapLibre)
   → O102 delegation sim experiment (expected to close as DECISION).

## Hardware checkpoint — DONE 2026-07-15 (see G24 in CLAUDE.md)

Full pass on 3 phones: new wire format (murmur bloom 0.01% FP + HELLO
knownCount), O92 reseed on every restart, store-and-forward across
restart+crash multi-hop (Moto→Samsung→reinstall→OnePlus), three-node star,
adaptive gate correctly on bloom. Found+fixed a ColorOS process-killer
(stale manifest receiver, commit 183efee). O42 is CLOSED. Item 1 below is
done; next work is item 2 (deeper O92 + O105 hoist — the `git mv` of
GossipSession to :core was prepared but deliberately deferred until after
this hardware pass; do it first, the wire harness is the safety net).

## Prebuild assay (user-requested, this cycle)

Adopted: BC `jdk15on:1.70` → `jdk18on` (discontinued artifact, CVE fixes);
real MurmurHash3 via commons-codec in BloomFilterData (the hand-rolled
"murmur3" was a weak xor-mult mix; wire-breaking, bundled with the FP-rate
break in the same pre-release window). Deferred with reasoning (see CLAUDE.md
design decisions): CBOR RBSR frames (JSON envelope forces base64, cuts the
win to ~25–35%; adaptive gate already handles the tax), Square Wire for
Meshtastic protobuf (do when S1 resumes), libsodium for Ed25519↔X25519
(BigInteger map only touches public keys; native .so bloat not yet justified),
HKDF via BC (current HKDF-extract is sound; swapping breaks existing DM keys).
Future O45/O46: MUST adopt (kotlin-bip39, SLIP-0039 lib) — never hand-roll.

## Build/test commands (this environment)

Gradle is NOT on PATH. Java home must be passed as `-D`, NOT as a leading
`JAVA_HOME=` (breaks the permission match):

```
/home/user/AndroidStudioProjects/Rumor/gradlew -Dorg.gradle.java.home=/home/user/jdk17 :core:test :simulator:test :app:testDebugUnitTest
```

`adb` works via absolute path (allow rules already granted). `.claude/settings.json`
is intentionally modified-but-uncommitted (permission allowlist) — do NOT commit it.

Known-flaky under full-suite CPU load (pass in isolation and on rerun; their
`awaitUntil` polls time out under contention): `BreadcrumbSubstrateTest`
"relayed message records breadcrumb…" and `PerPeerRoutingTest` "routed hop
increments…". If one fails in a full run, rerun before suspecting your change.

## O98 Phase 3 design notes (kept from previous handoff)

- The planner emits an **undirected** backbone; GO/client role and channel are
  transport concerns. Reconciler is NOT thread-safe — single coroutine only.
- Wi-Fi Direct reality: a client is in exactly one group; a backbone edge
  between two would-be-hubs is realized by one becoming client of the other.
- Start Phase 3 with `redundancy=1` (tree), raise once stable.
- Keep it android-agnostic (no OEM-specific gating); transport clamps
  `PersistencePlanner.capacityFor` numbers down, never up.
