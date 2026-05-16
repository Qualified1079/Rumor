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
| S1 | **Meshtastic bridge** | `app/…/plugin/meshtastic/{MeshtasticBridge,MeshtasticBleClient,MeshtasticMessages,MeshtasticProtobuf}.kt` | v0.1.0. Hand-rolled minimal protobuf codec (no protoc dep). BLE only — auto-scans, prompts bonding via `createBond()`. Reads plaintext from `MeshPacket.decoded` (radio decrypts); drops encrypted packets the radio can't decode. Only `TEXT_MESSAGE_APP` (port 1) BROADCASTs are bridged. Outbound uses primary channel (idx 0). Bridge traffic → `BRIDGE_UNSIGNED` → BRIDGED trust → never re-relayed. Field numbers verified against meshtastic/protobufs master: `MeshPacket.id=6`, `hop_limit=9`, `FromRadio.packet=2` (an earlier draft used 8/10/11 which silently broke all inbound text). **Not done**: USB serial, DM bridging via PKC, multi-channel selection UI, device picker for multi-radio setups. |
| S2 | **MeshCore bridge** | `app/…/plugin/meshcore/{MeshCoreBridge,MeshCoreBleClient,MeshCoreFrames}.kt` | v0.1.0. BLE NUS only (`6E400001-…`). Auto-scans, no bonding. Opcodes verified against the v3 BLE protocol spec (zjs81/meshcore-open BLE_PROTOCOL.md): RESP_CODE_CONTACTS_START=0x02, RESP_CODE_NO_MORE_MESSAGES=0x0A, RESP_CODE_CONTACT_MSG_RECV=0x10, RESP_CODE_CHANNEL_MSG_RECV=0x11. v3 channel-recv layout is `[opcode][snr][res×2][channel][path_len][txt_type][ts×4][sender ": " text]`. Synthetic sender userId = `meshcore:<FNV-1a of name>`. **Not done**: USB CDC framer, DM bridging via X25519, multi-channel, device picker, v1/v2 firmware fallback. |

### Completed gaps

| # | Item | Resolution |
|---|------|------------|
| G1 | **DM sent-message plaintext** | `GossipEngine.sentDmPlaintext` (bounded LinkedHashMap, 500 entries) stores plaintext at compose time. Exposed via `MeshController.sentPlaintextFor()`. `ThreadViewModel` checks this before falling back to `[sent]`. |
| G2 | **Priority-peer reconnect** | `WifiDirectTransport` tracks `activePriorityPeers` (userIds in live priority sessions). On Wi-Fi Direct disconnect, the set is moved to `priorityReconnectPending` and a watcher coroutine fires `discoverPeers()` with 2s→30s exponential backoff until the set drains. Identity is only ever confirmed via HELLO — MAC addresses are never trusted as identity. |
| P2 | **Protocol-layer diversity** | `messagesForExchange(peerUserId)` is now peer-aware. After HELLO, `GossipSession` calls the provider with the verified userId; `GossipEngine` consults `TopologyTracker.overlapFor(peerUserId)` and shapes the batch: overlap≥0.8 → 50 messages, ≥0.5 → 100, else 200. Well-informed peers get a smaller freshest-only batch; novel peers get the full set. Pre-HELLO selection was rejected because it requires trusting MACs. |
| G3 | **`onExchangeFailed` wiring** | `TransportConfig.onExchangeFailed` callback added; `WifiDirectTransport.runSession` calls it when `session.run()` returns null. `MeshService` wires it to `gossipEngine::onExchangeFailed`. |
| G4 | **Canary metrics queue-depth push** | `DebugMetricsViewModel` polls `engine.canaryMetrics.publish(engine.queueDepth)` every 1s via a `flow { while(true) { … delay(1_000) } }`. Screen stays live without needing a message event. |

### Open items / known gaps

Audit-derived punch list. When you close one, move it into "Completed gaps" with the resolution.

| # | Item | Notes |
|---|------|-------|
| O1 | **SQLDelight migration** | Promised in commit 3e7f588 as "next commit", never done. Room is still in use. Only worth doing if leaving Google's runtime matters; otherwise stale. |
| O2 | **Persistent priority-link connection at transport layer** | `Contact.isPriorityPeer` flag + compose/accept messages exist (#7). `WifiDirectTransport` reads the flag for skip-removeGroup, but still operates as one-off exchanges. True persistent connection would require holding the WiFi-Direct group open across the gossip-session boundary and re-running HELLO/exchange on a schedule. |
| O3 | **Reliability half of throughput+reliability ranking** | `bytesRelayed` (throughput proxy) is wired into `getPreferred`. Reliability score (success/failure ratio, drop count) is not tracked. Add a `failureCount` to `Route` and rank by `bytesRelayed / (1 + failureCount)`. |
| O4 | **Bridges — USB transport** | Both Meshtastic and MeshCore are BLE-only. MeshCore docs `'>'`+LE-len framing for USB CDC; Meshtastic uses 0x94 0xC3 magic + varint length. Significant scope per bridge. |
| O5 | **Bridges — DM bridging** | Both bridges deliberately broadcast-only today. Two viable architectures; **read the "Encrypted bridging" decision below before designing this**. Recommended: Architecture B (envelope passthrough, true E2E) for both bridges — curves align (both use X25519/Ed25519-derived ECDH), only the AEAD wrapper differs per bridge. Architecture A (decrypt-and-re-encrypt) is fallback only. Both architectures require the bridged-contact UI labelling listed under Architecture A as hard pre-ship blockers. |
| O6 | **Bridges — multi-channel selection UI** | Both bridges hardcode channel index 0. Need a per-plugin settings screen exposing the radio's channel list. |
| O7 | **Bridges — device picker** | Both bridges auto-connect to the first matching device. Multi-radio setups need a picker UI feeding `connect(device)`. |
| O8 | **MeshCore v1/v2 firmware fallback** | Current decoder is v3-only (opcodes 0x10/0x11, snr+reserved+path_len header). Older firmware uses 0x07/0x08 and a simpler layout. Detect via response code on the first frame. |
| O9 | **Real test coverage** | Unit tests cover scheduler/dedup/chunker/blocklist/online-status/neighbor only. No instrumented tests, no DI/lifecycle tests, no UI tests, no MainActivity smoke. |
| O10 | **CI + versionCode discipline** | No CI configured. `versionCode=1` is hardcoded. Need a CI rule that fails if `versionCode` isn't strictly greater than the previous release tag. |

### Design decisions recorded

- **Latency NOT used for routing.** On BLE/Wi-Fi Direct, measured latency is mostly discovery timing. Ranking is by `bytesRelayed DESC, sessionCount DESC`. `latencyMs` stored for diagnostics only.
- **Relay path never sees blocklist.** Blocking is a display filter. Nodes relay everything to preserve the mesh's routing integrity.
- **`BLOCKLIST_PUBLISH` is TRANSFER_SETUP, not INFRASTRUCTURE.** Full snapshots can exceed 16 KB; only incremental `BLOCKLIST_DIFF` stays at INFRASTRUCTURE tier.
- **Bridge traffic never re-relayed.** Re-signing a bridged message would launder it into a vouch the local node never made.
- **Bridges are broadcast-only and bridge plaintext only.** Rumor BROADCASTs are signed plaintext on the mesh; we hand the radio plaintext and it applies channel encryption with its own PSK. Inbound, the radio gives us plaintext via `MeshPacket.decoded` (Meshtastic) or after firmware decryption (MeshCore). We never touch channel keys. Encrypted Rumor DMs are not bridged because the bridge node would have to be the actual cryptographic endpoint — see "Encrypted bridging" below.
- **Encrypted bridging — two architectures, pick one before designing O5.**

  **Architecture A — decrypt-and-re-encrypt (bridge is an endpoint).** The bridge holds both crypto sessions. Sender E2E-encrypts to the bridge's Rumor pubkey; bridge decrypts; bridge re-encrypts to the LoRa contact's PKC key. Pure-relay DMs (Rumor → Rumor that happen to traverse a bridge node) stay E2E because the bridge has no key for messages it isn't the addressed recipient of. **Crypto is sound; trust model changes — the bridge operator can read bridged DMs.** Works for any LoRa stack regardless of envelope format. Required UI before ship (HARD blockers, not nice-to-have):
    1. Bridged contacts must render with a distinct "via <bridge>" label everywhere they appear (compose, contact list, thread header). Never as if they were direct Rumor contacts.
    2. The compose screen for a bridged contact must display a persistent banner: "Messages to this contact pass through <bridge owner>'s device and are readable there." No dismiss.
    3. SAS / safety-number verification must include a three-leg fingerprint (you ↔ bridge ↔ them) instead of the two-leg flow used for native Rumor contacts.
    4. Identity rotation: if the bridge node's Rumor key changes, every bridged contact must be re-verified — bridge-key change ≠ peer-key change but UI must treat it as either.

  **Architecture B — envelope passthrough (bridge sees ciphertext only).** Rumor's DM envelope for bridged contacts becomes byte-compatible with the target network's DM envelope. The bridge forwards opaque ciphertext both ways and never holds plaintext. Preserves real E2E across the bridge. Curve alignment exists for **both** bridges — Meshtastic PKI uses X25519 directly; MeshCore uses Ed25519 for identity and ECDH from the X25519-converted key, same as Rumor. The differences are only in the AEAD wrapper, not in the curve:
  - Rumor native: X25519 ECDH + AES-GCM
  - Meshtastic envelope: X25519 ECDH + AES-CCM + Meshtastic framing
  - MeshCore envelope: ECDH (Ed25519→X25519) + AES-128-ECB + HMAC-SHA256 (Encrypt-then-MAC) + MeshCore framing

  Requires per-bridge:
    1. A second DM-encryption code path in `GossipEngine.composeDirect` selected by recipient userId prefix (`meshtastic:*` → Meshtastic envelope; `meshcore:*` → MeshCore envelope; native → existing X25519+AES-GCM).
    2. Bridge advertises discovered remote NodeInfo / contacts as synthetic Rumor contacts whose `publicKey` field holds the remote pubkey (32 bytes for both — Meshtastic X25519 directly, MeshCore Ed25519 which we convert at ECDH time).
    3. Rumor's Ed25519 signature is dropped for bridged DMs — neither target envelope carries an outer signature, only an AEAD/MAC tag. Acceptable because authenticity is enforced by the AEAD/MAC plus the recipient's knowledge of which key sent it.

  Default recommendation: **Architecture B for both bridges.** Worth the per-bridge envelope code to preserve real E2E. Fall back to Architecture A only if a future bridge has no usable ECDH path. Both architectures still require the bridged-contact UI labelling from A1 above — even passthrough has a bridge in the path, and users need to know which contacts are bridged so a compromised bridge can't quietly substitute its own key for a peer's.
