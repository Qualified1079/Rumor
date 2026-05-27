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
| G5 | **Pluggable `DmEnvelope` framework (O5a)** | `core/plugin/{DmEnvelope,DmEnvelopeRegistry}.kt` + three new `PluginContext` methods (`registerDmEnvelope`, `injectBridgedDm`, `observeOutboundBridgedDm`). `GossipEngine.composeDirect` consults the registry by recipient prefix; if a match is found, the envelope's `encrypt()` runs and the raw ciphertext is emitted on `outboundBridgedDm` for the bridge plugin to forward (the message is NOT enqueued in the gossip scheduler — bridged recipients aren't reachable via Rumor peers). `GossipEngine.injectBridgedDm` enforces all six O5a security constraints: derives the envelope by sender-prefix locally (not from wire-asserted `envelopeId`), drops on derived-vs-asserted mismatch, sets `BRIDGE_UNSIGNED` → `LOCAL_BRIDGE` → `TrustLevel.BRIDGED` (never re-relayed). `PluginRegistry.unregister` calls `unregisterAllEnvelopes()` atomically *before* `onDetach`. Tests: `DmEnvelopeRegistryTest`. |
| G6 | **Koin DI smoke test (O9 partial)** | `app/.../di/AppModuleTest.kt` calls `appModule.verify(extraTypes = listOf(Context::class))` — pure JVM, no Robolectric. Catches wiring breaks (added constructor param without matching `single`, type mismatch) at unit-test time instead of as a crash on first device launch. Requires `koin-test:3.5.3` (added to `app/build.gradle.kts`). |
| G7 | **CI + versionCode discipline (O10 partial)** | `.github/workflows/ci.yml` runs unit tests + debug APK assemble on PRs to `main`. `.github/workflows/release-check.yml` runs on `v*` tag pushes, parses `versionCode` from `app/build.gradle.kts` at the current tag and at the previous tag, fails the workflow if the new value is not strictly greater. F-Droid and Play both reject non-monotonic uploads so catching it pre-release avoids a wasted submission. **Caveat:** `gradlew` is not committed (only `gradle-wrapper.properties` exists); the workflows use system gradle via `gradle/actions/setup-gradle@v3`. Regenerating and committing the wrapper is a separate cleanup. |

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
| O9 (rest) | **Instrumented + UI test coverage** | DI smoke test is now in place (G6) and unit tests cover scheduler/dedup/chunker/blocklist/online-status/neighbor/DmEnvelopeRegistry. Still missing: instrumented (on-device) tests, Compose UI tests, MainActivity lifecycle smoke. Lower priority — the DI verify test catches the most common pre-ship breakage. |
| O10 (rest) | **Commit gradle wrapper** | CI workflows are in place (G7) but use system gradle because `gradlew`/`gradle-wrapper.jar` aren't committed. Regenerate via `gradle wrapper --gradle-version 8.6` and commit so contributors and CI use the same gradle build. |
| O12 | **`deterministic-replay` residual non-determinism** | `SimTransport.exchange()` was fixed to (a) drop real `delay()` calls and (b) use the tick's seeded RNG. Two variance sources remain: (1) `GossipEngine` runs internal handlers on `Dispatchers.Default`; coroutine scheduling order isn't guaranteed (affects `totalMessages` by ~3–5%). (2) `ConcurrentHashMap` iteration order in `knownMessages()` determines which RNG values the conditioner consumes; drop counts vary by <0.1% across runs. Both are tolerated by ±5% relative bounds on both messages and drops in the assertion. If variance grows, the escalation path is a single-threaded dispatcher for `SimNode.scope` (to fix handler order) and sorting `knownMessages()` by ID (to fix conditioner RNG consumption order). Neither done preemptively — the 5% band keeps the assertion useful as a regression catch. |
| O11 | **`no-bridged-rerelay` assertion may need escalation** | The scenario runner's `NoBridgedRerelay` check is a post-hoc snapshot scan: at the end of a scenario it walks every node's stored messages and flags any BRIDGED-trust message whose `recipientId` doesn't match that node's `userId`. It's reliable in the simulator because the in-memory `MessageRepository` never evicts during a run, but it would miss a violation where the engine relayed a bridged message, then the message expired or got evicted before the scan. If the in-sim message store ever starts trimming during a scenario — or if this assertion graduates to production telemetry — escalate to a live observer: add a `relayObserver: ((RumorMessage) -> Unit)?` hook to `GossipEngine.relay()` and have the runner subscribe per-node. The current implementation is in `simulator/.../scenario/ScenarioRunner.kt` and is intentionally marked temporary in the JSON-schema docstring on `Assertion.NoBridgedRerelay`. |
| O13 | **BloomFilterData OOM on adversarial expectedItems** | A peer could send a bloom filter claiming `expectedItems = Int.MAX_VALUE`, causing the BloomFilterData constructor to attempt a multi-GB allocation and crash the process. Hard caps are the wrong fix — in 50 years a large bloom may be completely legitimate. Right fix: graceful degradation. Wrap `BloomFilterData` construction in a try/catch for `OutOfMemoryError` and `IllegalArgumentException`; on failure, fall back to sending up to `MAX_OFFER_PER_EXCHANGE` messages without bloom filtering (same as the no-prior-knowledge path). Receiver keeps running, just slightly over-offers this exchange. |
| O14 | **Compressed-archive extraction UI** | There is no compression at the protocol layer — `Chunker` splits raw bytes, no zip/gz. So a zip bomb at protocol level is not possible; the hash is of the full uncompressed data and is covered by Ed25519, so a sender cannot lie about it without breaking their signature. The risk is purely application-layer: a sender transmits a 1MB `.zip` payload which the receiver extracts to 100GB. Fix: before any extraction of a compressed MIME type, show a non-dismissable dialog: "Compressed archive received — Download: XMB / Estimated extracted: YGB." Do not extract until user confirms. This is a UI feature, no protocol changes needed. |
| O15 | **Runtime plugin loading (DEX loader)** | Current plugins (Meshtastic, MeshCore) are compiled into the APK — not loadable at runtime. For a true mod scene, plugins need to be droppable files. Two mechanisms that compose: (1) **DEX loader**: a `.rumor` bundle (zip of `.dex` + a manifest) loaded via `DexClassLoader` at runtime — the mod-scene primary path, distributable via flash drive or chunked transfer over the mesh. (2) **Bound-service APK**: a separately installed APK exposes a bound service; Rumor binds to it by intent — good for plugins that need their own Android permissions (camera, contacts, location) since those are scoped to the plugin APK, not Rumor's process. Both paths feed into the same `PluginContext` interface. The DEX path runs in Rumor's process with Rumor's permissions; the bound-service path runs isolated. No central repository; the mesh itself is the distribution channel. **Pre-ship requirement**: a static manifest declaration in the plugin bundle listing which `PluginContext` APIs it calls, shown to the user as a disclosure popup before enable — not a security control (a malicious plugin can lie), but informed consent. |
| O16 | **Relay-path spam: per-sender rate limiting** | A malicious plugin can call `injectFromPlugin` or `composeDirect` at arbitrary rate. Round-robin in the Scheduler ensures one spammer cannot monopolize bandwidth — their messages compete fairly. Blocking is a display filter only (relay path never sees blocklist — intentional, see design decisions). The residual risk is CPU and `MessageStore.ingest` pressure from high-rate senders even for blocked users, since ingest (sig verify + dedup + write) runs before the blocklist check. Mitigation: add a per-`senderId` token bucket in `MessageStore.ingest` — if a sender exceeds N ingests/sec, skip ingest (not just inbox) for that sender for the remainder of the window. This does NOT affect relay to others (relayed copies bypass the ingest gate); it only limits how much CPU/storage one sender can consume on this specific node. Threshold should be generous (e.g. 100/sec) to avoid false positives on high-traffic legitimate nodes. |

### Design decisions recorded

- **Latency NOT used for routing.** On BLE/Wi-Fi Direct, measured latency is mostly discovery timing. Ranking is by `bytesRelayed DESC, sessionCount DESC`. `latencyMs` stored for diagnostics only.
- **Relay path never sees blocklist.** Blocking is a display filter only — nodes relay everything regardless of blocklist state. This is intentional: if nodes stopped relaying blocked senders, a coordinated block could erase a person from the entire mesh, turning individual preferences into network-level censorship. Charlie needs Bob's emergency broadcast even if Alice blocked Bob. Your device's resources serve the mesh, not your social preferences. The residual CPU/storage cost of ingesting high-rate blocked senders is addressed separately by per-sender rate limiting (O16).
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

  **Framework: pluggable `DmEnvelope` (O5a).** To avoid hardcoding "if recipient starts with meshtastic: do X, if meshcore: do Y" into core, route DM crypto through a registered envelope. Each bridge plugin owns its envelope; core only knows the interface.

  ```kotlin
  // core/plugin/DmEnvelope.kt
  interface DmEnvelope {
      val recipientPrefix: String                  // e.g. "meshtastic:"
      val envelopeId: String                       // stable wire-format id, persisted on the message
      val selfAuthenticating: Boolean              // true → engine skips outer Ed25519 sig
      fun encrypt(recipientUserId: String, recipientPubKey: ByteArray, plaintext: ByteArray): ByteArray
      fun decrypt(senderUserId: String, senderPubKey: ByteArray, ciphertext: ByteArray): ByteArray?
  }

  // additions to PluginContext
  fun registerDmEnvelope(envelope: DmEnvelope)
  fun injectBridgedDm(
      recipientUserId: String,
      senderUserId: String,
      senderPubKey: ByteArray,
      ciphertext: ByteArray,
      envelopeId: String,
  )
  ```

  Compose path: `GossipEngine.composeDirect` consults the registry by recipient prefix; falls back to the existing native envelope when none matches. Inbound path: bridge plugin calls `injectBridgedDm` with an opaque blob plus the envelope id; engine routes to the recipient's inbox without ever holding plaintext; the recipient's UI looks up the envelope by id and decrypts at read time.

  This is a prerequisite for O5 if we want more than two bridges without forking core every time. Even with just Meshtastic + MeshCore today it is the right shape — two hardcoded branches in `composeDirect` would be the same wrong direction as the original Hilt → Koin "just rewire it where you need it" pattern we already abandoned. Implement O5a first, then O5 for each bridge is a small, isolated addition.

  **Security constraints for O5a (non-negotiable — without these, the framework is a regression).**

  1. **Source-gated `selfAuthenticating`.** Honored **only** for `MessageSource.LOCAL_BRIDGE` (messages arriving via `injectBridgedDm`). Over peer transport, every message must pass Ed25519 verification regardless of any envelope-id claim. This is the same rule that gates `BRIDGE_UNSIGNED` today (commit f898874); the lesson generalises directly. A peer transport claiming `selfAuthenticating` is treated as a corrupt frame and dropped.
  2. **Envelope id is derived, not asserted on the wire.** Recipient looks up the envelope by recipient userId prefix locally. An envelope-id field on the message exists only as a sanity assertion at the bridge boundary — never as the decryption selector. Prevents downgrade attacks where an attacker injects a weak envelope id to coerce decryption with attacker-controlled assumptions.
  3. **One envelope per prefix; registry is append-only per plugin lifecycle.** `registerDmEnvelope` throws on prefix collision. Plugin teardown unregisters atomically. Prevents prefix-squatting plugins from intercepting another bridge's DMs.
  4. **Bridged DMs inherit BRIDGED trust → never re-relayed.** Same invariant as broadcast bridge traffic today.
  5. **Replay protection is the envelope's responsibility.** Document this as a requirement. Native Rumor envelope uses dedup + sequence; bridged envelopes inherit the target network's mechanism (Meshtastic packet id + timestamp; MeshCore sender_timestamp tracking). A reviewer adding a new envelope must explicitly state how replay is prevented.
  6. **Trust model unchanged from existing plugins.** A user-enabled plugin already has full access to broadcast plaintext via `observeIncoming`. Adding DM handling raises the leakage surface only for users who enable a malicious bridge plugin — same gate (PluginCatalog toggle) as today. The framework does NOT widen the trust boundary; it just gives well-behaved bridges a way to preserve E2E that they previously didn't have.

  If any of these six constraints can't be honored by a proposed envelope, fall back to Architecture A for that bridge.
