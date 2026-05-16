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

### Bridges (implemented, BLE-only)

| # | Plugin | Files | Status |
|---|--------|-------|--------|
| S1 | **Meshtastic bridge** | `app/…/plugin/meshtastic/{MeshtasticBridge,MeshtasticBleClient,MeshtasticMessages,MeshtasticProtobuf}.kt` | v0.1.0. Hand-rolled minimal protobuf codec (no protoc dep). BLE only — auto-scans, prompts bonding via `createBond()`. Reads plaintext from `MeshPacket.decoded` (radio decrypts); drops encrypted packets the radio can't decode. Only `TEXT_MESSAGE_APP` (port 1) BROADCASTs are bridged. Outbound uses primary channel (idx 0). Bridge traffic → `BRIDGE_UNSIGNED` → BRIDGED trust → never re-relayed. **Not done**: USB serial, DM bridging via PKC, multi-channel selection UI, device picker for multi-radio setups. |
| S2 | **MeshCore bridge** | `app/…/plugin/meshcore/{MeshCoreBridge,MeshCoreBleClient,MeshCoreFrames}.kt` | v0.1.0. BLE NUS only (`6E400001-…`). Auto-scans, no bonding. Opcodes verified against the v3 BLE protocol spec (zjs81/meshcore-open BLE_PROTOCOL.md): RESP_CODE_CONTACTS_START=0x02, RESP_CODE_NO_MORE_MESSAGES=0x0A, RESP_CODE_CONTACT_MSG_RECV=0x10, RESP_CODE_CHANNEL_MSG_RECV=0x11. v3 channel-recv layout is `[opcode][snr][res×2][channel][path_len][txt_type][ts×4][sender ": " text]`. Synthetic sender userId = `meshcore:<FNV-1a of name>`. **Not done**: USB CDC framer, DM bridging via X25519, multi-channel, device picker, v1/v2 firmware fallback. |

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
