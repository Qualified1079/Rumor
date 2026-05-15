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
- **MAC addresses are never identity.** Android randomises MACs per-connection and they are trivially spoofable. The only identity is the userId proven via HELLO Ed25519 challenge-response. `WifiP2pDevice.deviceAddress` is only used as an opaque connection target for the OS — never as a cache key, cooldown key, or trust anchor.

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

### Critical bug history

- **Duplicate `GossipSession.kt` / `BloomFilterData.kt`** existed in both `:core/.../transport/` and `:app/.../wifidirect/` with the same FQN, causing the build to fail since `a1bc312`. Resolved: deleted the `:core` copies; the `:app/wifidirect/` versions are canonical. Be wary when adding new same-named files in both modules.

### Fully stubbed (TODO comments in code)

| # | Item | Files | Notes |
|---|------|-------|-------|
| S1 | **Meshtastic bridge** | `app/…/plugin/meshtastic/MeshtasticBridge.kt` | BLE service `6ba1b218-15a8-461f-9fa8-5dcae273eafd`; chars `2c55e69e-…` (FromRadio read), `f75c76d2-…` (ToRadio write), `ed9da18c-…` (FromNum notify). Serial framing `0x94 0xc3` + 2-byte BE length + protobuf. Hand-rolled protobuf decoder for `FromRadio`/`ToRadio`/`MeshPacket`/`Data`/`PortNum` is cheaper than pulling protoc Gradle plugin. Default channel PSK `AQ==` decrypts to a publicly-known AES-256-CTR key; payload nonce = `packetId\|\|fromNode`. **BLE bonding required** by default firmware — must call `device.createBond()` and handle `ACTION_PAIRING_REQUEST`. License: GPL-3.0 (firmware + protobufs). |
| S2 | **MeshCore bridge** | `app/…/plugin/meshcore/MeshCoreBridge.kt` | Repo: `github.com/meshcore-dev/MeshCore` (MIT). Uses Nordic UART Service `6E400001-…` (RX `…0002` write, TX `…0003` notify). BLE frame = single GATT write/notify (no length prefix). USB-CDC frame = `>`/`<` + 2-byte LE length + frame. Companion sees **plaintext** — radio decrypts before pushing. Send: `CMD_SEND_CHANNEL_MESSAGE` (0x03) / `CMD_SEND_TXT_MSG`. Recv push: `PACKET_CONTACT_MSG_RECV` / `PACKET_CHANNEL_MSG_RECV` (+ `_V3`). No BLE bonding required. Reference impl: `michaelhart/meshcore-decoder` (TS). |

### Completed gaps

| # | Item | Resolution |
|---|------|------------|
| G1 | **DM sent-message plaintext** | `GossipEngine.sentDmPlaintext` (bounded LinkedHashMap, 500 entries) stores plaintext at compose time. Exposed via `MeshController.sentPlaintextFor()`. `ThreadViewModel` checks this before falling back to `[sent]`. |
| G2 | **Priority-peer reconnect** | `WifiDirectTransport` tracks `activePriorityPeers` (userIds in live priority sessions). On Wi-Fi Direct disconnect, the set is moved to `priorityReconnectPending` and a watcher coroutine fires `discoverPeers()` with 2s→30s exponential backoff until the set drains. Identity is only ever confirmed via HELLO — MAC addresses are never trusted as identity. |
| P2 | **Protocol-layer diversity** | `messagesForExchange(peerUserId)` is now peer-aware. After HELLO, `GossipSession` calls the provider with the verified userId; `GossipEngine` consults `TopologyTracker.overlapFor(peerUserId)` and shapes the batch: overlap≥0.8 → 50 messages, ≥0.5 → 100, else 200. Well-informed peers get a smaller freshest-only batch; novel peers get the full set. Pre-HELLO selection was rejected because it requires trusting MACs. |
| G3 | **`onExchangeFailed` wiring** | `TransportConfig.onExchangeFailed` callback added; `WifiDirectTransport.runSession` calls it when `session.run()` returns null. `MeshService` wires it to `gossipEngine::onExchangeFailed`. |
| G4 | **Canary metrics queue-depth push** | `DebugMetricsViewModel` polls `engine.canaryMetrics.publish(engine.queueDepth)` every 1s via a `flow { while(true) { … delay(1_000) } }`. Screen stays live without needing a message event. |

### Design decisions recorded

- **Latency NOT used for routing.** On BLE/Wi-Fi Direct, measured latency is mostly discovery timing. Ranking is by `bytesRelayed DESC, sessionCount DESC`. `latencyMs` stored for diagnostics only.
- **Relay path never sees blocklist.** Blocking is a display filter. Nodes relay everything to preserve the mesh's routing integrity.
- **`BLOCKLIST_PUBLISH` is TRANSFER_SETUP, not INFRASTRUCTURE.** Full snapshots can exceed 16 KB; only incremental `BLOCKLIST_DIFF` stays at INFRASTRUCTURE tier.
- **Bridge traffic never re-relayed.** Re-signing a bridged message would launder it into a vouch the local node never made.
