# Rumor — Claude Code context

## Architecture at a glance

Three Gradle modules with strict layering:

```
:core       — pure JVM/Kotlin. No Android imports. All protocol, crypto, scheduling logic.
:app        — Android. Depends on :core. Room DB, ViewModels, Compose UI, transport.
:simulator  — JVM CLI. Depends on :core. Ktor dashboard, in-memory repos, sim transport.
```

Key invariants:
- `GossipEngine.relay()` never touches blocklist state — only `emitToInbox()` does.
- `TrustLevel.BRIDGED` messages are never re-relayed. Bridge traffic cannot forge VERIFIED trust.
- `BRIDGE_UNSIGNED` sentinel is honored **only** for `MessageSource.LOCAL_BRIDGE`; peer transport always verifies Ed25519.
- `trafficClass` is derived, never on the wire — a sender cannot claim INFRASTRUCTURE class for a bulk payload.
- Size ceilings enforced in `trafficClass`: INFRA/REALTIME=16 KB, TRANSFER_SETUP=256 KB. Oversized → BULK.

## Coding conventions

- No CDN or external assets. All UI is offline-first (no WebView phoning home).
- F-Droid compatible: no proprietary SDKs, no Play Services.
- Minimal comments — only write WHY, not WHAT. No docstrings unless a contract is genuinely non-obvious.
- No premature abstractions. Three similar lines beats a helper. No feature flags.
- Room schema changes: bump `RumorDatabase.version`. Uses `fallbackToDestructiveMigration()` in dev. Current version: **4**.

## DI (Koin) wiring

Single source of truth: `app/src/main/java/com/rumor/mesh/di/AppModule.kt`.
Simulator uses constructor injection with in-memory repos (`simulator/…/data/InMemoryRepos.kt`).

When adding a new core class that needs to reach the app:
1. Add `single { … }` to AppModule.
2. Add an in-memory stub to `InMemoryRepos.kt` if the simulator needs it.
3. If it's a new `ContactRepository`/`RouteRepository` method, update all four impls:
   - `ContactRepositoryAdapter` / `RouteRepositoryAdapter` (Room)
   - `InMemoryContactRepository` / `InMemoryRouteRepository` (simulator)

---

## Unimplemented — living backlog

Update this list whenever something is completed or newly identified.

### In-progress / partially done

| # | Item | Status | Files |
|---|------|--------|-------|
| P1 | Priority-link persistent Wi-Fi Direct connection | Transport holds group open for priority peers; BLE reconnect loop on drop not yet implemented | `WifiDirectTransport.kt` — already skips `removeGroup()` for priority peers |
| P2 | `selectDiversePeers` integration in transport | `NeighborStore` + `TopologyTracker.selectDiversePeers()` exist. Session cooldown (45s) is wired. Full overlap-aware connection ordering needs MAC→userId mapping at discovery time (before HELLO) | `WifiDirectTransport.kt`, `TopologyTracker.kt` |

### Fully stubbed (TODO comments in code)

| # | Item | Files | Notes |
|---|------|-------|-------|
| S1 | **Meshtastic bridge** | `app/…/plugin/meshtastic/MeshtasticBridge.kt` | ~14 TODOs. Needs: BT/USB RFCOMM, varint-framed protobuf decode (`MeshPacket`), outbound encode. Everything else (plugin API, trust gating, relay) is done. |
| S2 | **MeshCore bridge** | `app/…/plugin/meshcore/MeshCoreBridge.kt` | ~11 TODOs. Same pattern as Meshtastic but MeshCore binary format instead of protobuf. |

### Known gaps (no code yet)

| # | Item | Notes |
|---|------|-------|
| G2 | **BLE reconnect loop for priority peers** | After the Wi-Fi Direct group is kept alive, if the peer disconnects, there is no reconnect attempt. Needs a watcher coroutine on `exchangeResults` per priority peer that re-initiates discovery when the peer goes silent. |

### Completed gaps

| # | Item | Resolution |
|---|------|------------|
| G1 | **DM sent-message plaintext** | `GossipEngine.sentDmPlaintext` (bounded LinkedHashMap, 500 entries) stores plaintext at compose time. Exposed via `MeshController.sentPlaintextFor()`. `ThreadViewModel` checks this before falling back to `[sent]`. |
| G3 | **`onExchangeFailed` wiring** | `TransportConfig.onExchangeFailed` callback added; `WifiDirectTransport.runSession` calls it when `session.run()` returns null. `MeshService` wires it to `gossipEngine::onExchangeFailed`. |
| G4 | **Canary metrics queue-depth push** | `DebugMetricsViewModel` polls `engine.canaryMetrics.publish(engine.queueDepth)` every 1s via a `flow { while(true) { … delay(1_000) } }`. Screen stays live without needing a message event. |

### Design decisions recorded

- **Latency NOT used for routing.** On BLE/Wi-Fi Direct, measured latency is mostly discovery timing. Ranking is by `bytesRelayed DESC, sessionCount DESC`. `latencyMs` stored for diagnostics only.
- **Relay path never sees blocklist.** Blocking is a display filter. Nodes relay everything to preserve the mesh's routing integrity.
- **`BLOCKLIST_PUBLISH` is TRANSFER_SETUP, not INFRASTRUCTURE.** Full snapshots can exceed 16 KB; only incremental `BLOCKLIST_DIFF` stays at INFRASTRUCTURE tier.
- **Bridge traffic never re-relayed.** Re-signing a bridged message would launder it into a vouch the local node never made.
