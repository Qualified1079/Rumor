# Rumor

A Wi-Fi Direct mesh communication protocol for Android. No servers, no phone numbers, no internet required.

Messages spread like rumors — carried further by people who think they're worth repeating, dying out when nobody cares.

---

## How it works

**Discovery:** BLE advertising announces presence — a service UUID that says "I'm a Rumor node." Identity is established later in the gossip handshake.

**Transport:** When BLE detects nearby nodes, Wi-Fi Direct takes over. Two phones connect, authenticate via a signed challenge-response, exchange everything they each lack, then disconnect. One rich exchange replaces the need for a persistent connection.

**Flooding:** Every broadcast gets a hop count (hops-to-live, default 7). Each node that receives a new message rebroadcasts it once with hops-to-live decremented. Message dies at zero. Manual relay bumps hops-to-live by a small amount, capped at the default — a near-dead message can be revived, but chained relays can't multiply hop count.

**Direct messages:** Bounded hops-to-live (default 15) so a DM can't ghost-circle the network forever when the recipient is unreachable. Recipients drop the message; everyone else relays once with hops-to-live decremented.

**LoRa bridges:** Meshtastic and MeshCore bridge plugins extend range to kilometers. A phone with a LoRa dongle on solar is complete infrastructure in one device.

---

## Module map

The codebase is split into 14 modules with strict dependency rules. Each module has one job and imports only from modules below it in the stack.

```
┌─────────────────────────────────────────────────┐
│  UI                                             │  Compose screens, ViewModels
│  (feed, contacts, messages, settings)           │  Reads storage + routing output
└───────────────────┬─────────────────────────────┘
                    │
┌───────────────────▼─────────────────────────────┐
│  MeshService (orchestrator)                     │  The only class that wires modules together
│                                                 │  Lifecycle: BLE → Wi-Fi Direct → Gossip → Plugins
└──┬──────────────┬──────────────────┬────────────┘
   │              │                  │
┌──▼──────┐  ┌───▼──────┐  ┌────────▼────────────┐
│   BLE   │  │  Wi-Fi   │  │   Plugin Registry   │
│discovery│  │  Direct  │  │                     │
│         │  │transport │  │  ┌───────────────┐  │
│ presence│  │          │  │  │ Meshtastic    │  │
│  signal │  │device-   │  │  │ Bridge        │  │
│         │  │ quirk    │  │  ├───────────────┤  │
│         │  │ aware    │  │  │ MeshCore      │  │
└─────────┘  └────┬─────┘  │  │ Bridge        │  │
                  │         │  └───────────────┘  │
                  │  PeerExchangeResult            │
                  │         └────────────────────┬─┘
                  │                              │
┌─────────────────▼──────────────────────────────▼─┐
│  Gossip Engine                                   │  Pure protocol logic, no radio code
│  - Duplicate suppression (heap-sized LRU)        │
│  - hops-to-live flood for both broadcasts and DMs         │
│  - Bounded manual relay boost                    │
└───────────────────┬──────────────────────────────┘
                    │
        ┌───────────┼───────────┐
        │           │           │
┌───────▼──┐  ┌─────▼────┐  ┌──▼────────────────┐
│ Message  │  │ Routing  │  │ Identity Manager  │
│ Store    │  │ Engine   │  │                   │
│          │  │          │  │ Ed25519 keypair   │
│ sig      │  │ breadcrumb│  │ PBKDF2 passphrase │
│ verify   │  │ topology  │  │ device ID         │
│ eviction │  │ OnlineStatus│ └───────────────────┘
└────┬─────┘  └─────┬────┘
     │               │
┌────▼───────────────▼──────────────────────────────┐
│  Storage (Room)                                   │  Messages, contacts, breadcrumbs, routes
└───────────────────────────────────────────────────┘
     │
┌────▼───────────────────────────────────────────────┐
│  Crypto          │  Message Model  │  Logging      │
│  Ed25519/X25519  │  RumorMessage   │  RumorLog     │
│  AES-256-GCM     │  GossipPacket   │  ring buffer  │
│  BouncyCastle    │  OnlineStatus   │  zero deps    │
└────────────────────────────────────────────────────┘
```

**Dependency rule:** arrows point downward only. No module imports from a module above it. The transport modules do not import from the protocol module. The protocol module does not import from transport. UI does not import from routing or transport directly.

### Module locations

| Module | Package | Responsibility |
|---|---|---|
| Message model | `core/model/` | Wire-format data classes, serialization |
| Crypto | `core/crypto/` | Ed25519, X25519, AES-GCM, PBKDF2 |
| Logging | `core/logging/` | Structured ring-buffer log, zero dependencies |
| Storage | `data/` | Room entities, DAOs, size-based eviction |
| Identity manager | `core/identity/` | Keypair lifecycle, passphrase lock, device ID |
| BLE discovery | `core/transport/ble/` | Advertise/scan on Rumor service UUID |
| Wi-Fi Direct | `core/transport/wifidirect/` | Peer connections, gossip sessions, device quirks |
| Device quirks | `core/transport/DeviceQuirks.kt` | OEM/OS workaround registry |
| Gossip engine | `core/protocol/` | Protocol logic, flooding, duplicate suppression |
| Routing engine | `core/routing/` | Breadcrumbs, topology, online status |
| Plugin API | `plugin/` | `RumorPlugin`, `PluginContext`, `BasePlugin` |
| Meshtastic bridge | `plugin/meshtastic/` | LoRa bridge stub |
| MeshCore bridge | `plugin/meshcore/` | LoRa bridge stub |
| Orchestrator | `service/MeshService.kt` | Wires everything, manages lifecycle |
| UI | `ui/` | Compose screens and ViewModels |

---

## UI screens

All screens are Jetpack Compose. ViewModels inject only the lowest-layer class that owns the relevant state — no screen imports from another screen's package.

| Screen | Route | Description |
|---|---|---|
| Onboarding | `onboarding` | First launch: set passphrase, generate identity |
| Unlock | `unlock` | Passphrase entry on subsequent launches |
| Feed | `feed` | Broadcast thread, compose button, manual relay per message |
| Messages | `messages` | DM thread list with unread badges and online status dots |
| Thread | `thread/{peerId}` | Per-peer conversation with bubble layout |
| Contacts | `contacts` | Contact list with ONLINE/RECENTLY/AWAY badge dots |
| Settings | `settings` | Duty cycle slider, passphrase change, debug toggle, links to sub-screens |
| Plugins | `settings/plugins` | Enable/disable bridge plugins grouped by category |
| Inbox policy | `settings/inbox` | Auto-accept rules and per-transfer byte cap |
| Blocked users | `settings/blocks` | Local blocks and subscribed remote blocklists |
| Transfers | `transfers` | Chunked file transfer history with progress indicators |
| Logs | `logs` | In-memory ring buffer viewer with level filter chips and auto-scroll |

Bottom navigation shows Feed, Messages, Contacts, and Settings. All other screens are reachable via navigation from Settings or the relevant parent screen.

---

## Building

Requirements: Android Studio Hedgehog or later, JDK 17, Android SDK 34.

```bash
git clone <repo>
cd Rumor
./gradlew assembleDebug
```

Install on a connected device:

```bash
./gradlew installDebug
```

The app targets API 23+ (Android 6.0). Wi-Fi Direct and BLE peripheral mode are required hardware features.

---

## Running tests

```bash
./gradlew test
```

Unit test coverage:

| Test class | What it covers |
|---|---|
| `SchedulerTest` | DRR fairness, starvation prevention, per-flow drop-oldest eviction cap |
| `ChunkerTest` | Round-trip reassembly, SHA-256 mismatch rejection, missing-chunk null return |
| `BlocklistVerifierTest` | Valid Ed25519 signatures accepted; tampered snapshot/diff payload rejected |
| `OnlineStatusTrackerTest` | ONLINE/RECENTLY/AWAY transitions, `currentSnapshot()` 30-minute cutoff |

---

## Writing a bridge plugin

A bridge plugin translates between Rumor messages and some external network — a LoRa radio, an SMS gateway, a packet radio modem, another mesh protocol. The plugin system is designed so you only need to know about three things:

- `RumorMessage` — the message type
- `BasePlugin` — the base class to extend
- `PluginContext` — the API you get when the plugin starts

### Minimal plugin

```kotlin
class MyBridge : BasePlugin() {

    override val pluginId    = "my_bridge"      // unique, lowercase, underscores
    override val displayName = "My Bridge"
    override val version     = "1.0.0"

    override fun onAttach(context: PluginContext) {
        super.onAttach(context)                  // stores context, logs attachment

        // Subscribe to incoming broadcasts and forward them to your network
        pluginScope.launch {
            observeIncoming()
                .filter { it.type == MessageType.BROADCAST }
                .collect { msg -> forwardToMyNetwork(msg) }
        }

        // Open your hardware connection
        openConnection()
    }

    override fun onDetach() {
        closeConnection()                        // release hardware
        super.onDetach()                         // cancels pluginScope automatically
    }

    // Called when your external network receives a message
    private fun onExternalMessageReceived(rawData: ByteArray) {
        val message = RumorMessage(
            id              = UUID.randomUUID().toString().replace("-", ""),
            senderId        = "my_node_id",
            senderPublicKey = "",
            sequenceNumber  = System.currentTimeMillis(),
            elapsedMs       = 0,
            type            = MessageType.BROADCAST,
            ttl             = 3,
            payload         = MessagePayload(ContentType.TEXT, decodePayload(rawData)),
            signature       = PluginContext.BRIDGE_UNSIGNED,  // skip Ed25519 for bridge traffic
        )
        sendMessage(message, sourceDescription = "my network")
    }
}
```

### Registering your plugin

In `MeshService.startMesh()`, find the plugin registration block and add one line:

```kotlin
// ── Register bridge plugins ──────────────────────────────────────────
pluginRegistry.register(MeshtasticBridge())
pluginRegistry.register(MeshCoreBridge())
pluginRegistry.register(MyBridge())          // add this
```

That's it. The registry handles attachment, lifecycle, and message routing.

### PluginContext API

Everything your plugin can do:

```kotlin
context.localUserId          // your own User ID (String?)
context.localPublicKey       // your own public key, Base64 (String?)
context.sendMessage(msg)     // inject a message into the local mesh
context.observeIncoming()    // Flow<RumorMessage> of all incoming mesh messages
context.contacts             // Flow<List<ContactSummary>> of known contacts
context.log(level, message)  // write to the in-app log
```

### BasePlugin helpers

`BasePlugin` wraps `PluginContext` so you can call these directly without going through `context`:

```kotlin
sendMessage(message)         // same as context.sendMessage
observeIncoming()            // same as context.observeIncoming
log(LogLevel.INFO, "text")   // same as context.log
pluginScope                  // CoroutineScope cancelled automatically on onDetach
```

---

## Device quirk handling

BLE and Wi-Fi Direct behavior varies significantly across Android versions and OEM firmware. All known workarounds are documented in `DeviceQuirks.kt`. When you find a new device-specific bug:

1. Add detection logic to `DeviceQuirks` (manufacturer check, API level check, etc.)
2. Apply the workaround in `BleDiscoveryManager` or `WifiDirectTransport` using the flag from `DeviceQuirks`
3. Add a comment explaining which devices exhibit the behavior and what the fix is

This keeps workarounds discoverable rather than scattered. The two transport modules consult `DeviceQuirks` at runtime; nothing else needs to change.

Current known workarounds:

| Issue | Devices | Fix |
|---|---|---|
| BLE scan silently dies after ~30 min | Samsung | Restart scan every 25 minutes |
| BLE advertiser not supported | Some low-end chipsets | Graceful fallback to scan-only |
| GO negotiation ignored | Samsung, some MediaTek | Dual-role: run server socket AND attempt client connect; first TCP connection wins |
| DHCP race after Wi-Fi Direct connect | Many devices | Retry TCP connect with exponential backoff (500ms → 8s, 5 attempts) |
| Stale Wi-Fi Direct group blocks new connections | Most devices | Always call `removeGroup()` at service start |
| `WifiP2pManager` returns BUSY | Many devices | Serialize all API calls through a semaphore with 300ms cooldown |
| Background process kill | Xiaomi (MIUI), Huawei (EMUI), Samsung (One UI) | Battery optimization exemption prompt directed to OEM-specific settings screen |

---

## Message format

Every message that travels between nodes:

```
RumorMessage {
    id              String    128-bit random hex. Duplicate suppression key.
    senderId        String    SHA-256 fingerprint of sender's Ed25519 public key.
    senderPublicKey String    Base64 Ed25519 public key. Carried with the message
                              so recipients can verify without prior key exchange.
    sequenceNumber  Long      Per-sender monotonic counter. Used for ordering.
    elapsedMs       Long      Milliseconds since creation. Each relay adds hold time.
                              Clock-agnostic — no NTP dependency.
    type            Enum      BROADCAST | DIRECT | PING | PONG
    ttl             Int       Remaining hops. Decremented at each relay; dies at 0.
                              Default 7 for broadcasts, 15 for DMs.
    payload         Object?   TEXT | IMAGE | VOICE | FILE (BROADCAST only)
    encryptedPayload String?  AES-256-GCM ciphertext (DIRECT only)
    recipientId     String?   Target User ID (DIRECT only)
    signature       String    Ed25519 signature over all other fields. Base64.
}
```

Signature covers everything except `receivedAtMs`, `isRead`, `wasRelayed` (local-only fields marked `@Transient`).

Bridge traffic from Meshtastic/MeshCore nodes uses `signature = "bridge_unsigned"`. The gossip engine skips verification for this sentinel value.

---

## Identity and encryption

**User ID:** SHA-256 fingerprint of the Ed25519 public key. The public key is the identity.

**Device ID:** SHA-256 of hardware fingerprint (build metadata + sensor specs) mixed with a random UUID. Generated once at first launch. Cannot be predicted or cloned.

**Passphrase lock:** The Ed25519 private key is wrapped with AES-256-GCM. The wrapping key is derived with PBKDF2-SHA256 (100,000 iterations) from the user's passphrase and a random salt. Both the encrypted key and salt are stored in SharedPreferences. The plaintext private key never touches disk.

**Direct message encryption:** Sender generates an ephemeral X25519 keypair, performs DH with the recipient's long-term public key, derives an AES-256-GCM session key. The ephemeral public key travels with the ciphertext so the recipient can recompute the shared secret. Wire format: `<ephemeral_pubkey_b64>.<iv+ciphertext_b64>`.

**Broadcast messages:** Signed but not encrypted. Anyone on the mesh can read them.

---

## Online status

`OnlineStatusTracker` (`core/routing/`) maintains a per-peer last-seen timestamp updated after every completed gossip session. The three states and their thresholds:

| State | Threshold | UI indicator |
|---|---|---|
| ONLINE | Last seen < 5 minutes ago | Green dot |
| RECENTLY | Last seen 5–30 minutes ago | Amber dot |
| AWAY | Last seen > 30 minutes ago, or never | Grey dot |

`currentSnapshot()` returns only peers seen within the last 30 minutes. Peers older than 30 minutes are pruned from the in-memory map on each update cycle. Status dots appear on contact avatars and DM thread headers.

---

## Inbox policy

`InboxPolicyManager` (`core/protocol/`) controls which incoming DMs are auto-accepted before the gossip engine delivers them to storage.

| Setting | Default | Description |
|---|---|---|
| `autoAccept` | `true` | Accept DMs from anyone on the mesh |
| `requireFollowBack` | `false` | Only accept DMs from contacts already in your contact list |
| `maxIncomingBytes` | `0` (unlimited) | Drop transfers larger than this byte count before reassembly |

Policy is persisted to SharedPreferences and exposed as `StateFlow<InboxPolicy>` for the UI to observe. The Settings → Inbox policy screen lets users adjust all three settings.

---

## Block and moderation

### Local blocks

`BlockManager.block(userId, durationMinutes, reason)` adds a peer to the local blocklist. Blocked peers' messages are silently dropped at ingest — they are never stored, relayed, or shown in any UI. Blocks are time-limited (expire automatically) or permanent (`durationMinutes = 0`).

```kotlin
blockManager.block(peerId, durationMinutes = 60 * 24, reason = "spam")
blockManager.unblock(peerId)
```

### Subscribed blocklists

Remote blocklists gossip across the mesh in the same way messages do. `BlocklistSubscriber` receives incoming blocklist packets; `BlocklistVerifier` checks the Ed25519 signature on each snapshot and diff before the entries are applied locally. A tampered payload — even a single flipped bit — is rejected outright.

Each subscribed blocklist is identified by its publisher's User ID. You can unsubscribe at any time from Settings → Blocked users.

### Export and import

Encrypted backup of your local block entries:

```kotlin
val blob = blockManager.exportEncrypted(passphrase)   // returns Base64 string
blockManager.importEncrypted(blob, passphrase)         // merges into local blocklist
```

---

## Transfer system

Large payloads (images, voice, files) travel as chunked transfers rather than single messages.

`TransferSender` splits the payload into fixed-size chunks, assigns each a sequential index, and tracks NACK responses from the receiver to retransmit only the missing pieces. This keeps retransmission cost proportional to loss rate rather than payload size.

`TransferAssembler` accumulates received chunks and, once all are present, verifies the SHA-256 hash of the reassembled payload. If the hash matches, the file is written to storage. If any chunk is missing or the hash fails, the transfer is discarded — no partial files reach the application layer.

Progress is tracked per transfer:

| Status | Meaning |
|---|---|
| `IN_PROGRESS` | Chunks still arriving; progress bar shows `received / total` |
| `COMPLETE` | All chunks received and hash verified |
| `FAILED` | Hash mismatch after all chunks arrived |
| `ABANDONED` | Sender gave up or hops-to-live expired before completion |

The Transfers screen shows recent transfers with a progress indicator for in-flight ones and a status badge for finished ones.

---

## Scheduler — outbound fairness

Outbound messages pass through a class-aware Deficit Round Robin (DRR) scheduler before entering the gossip engine's send queue.

**Two levels of priority:**

1. **Traffic class** — messages are bucketed by derived class and drained in strict priority order: `INFRASTRUCTURE` (ping, chunk requests, blocklist diffs) first, then `REALTIME` (text messages), `TRANSFER_SETUP` (transfer metadata, blocklist snapshots), and `BULK` (image/voice/file chunks) last. Routing chatter and texts never wait behind a flood of media chunks.

2. **Flow (DRR within a class)** — every distinct sender ID is one flow. Each round a flow gets a byte-credit quantum; messages are popped while the head-of-line fits the remaining credit. Leftover deficit carries to the next round.

**Size-ceiling hardening:** A message cannot claim a high-priority class with a large payload. Each class has a ceiling (INFRASTRUCTURE/REALTIME: 16 KB, TRANSFER_SETUP: 256 KB). Anything over its class ceiling is forced to BULK regardless of message type. A custom client mislabelling a 60 KB blob as `PING` gains nothing.

**Overflow shedding:** When the total backlog exceeds the cap, the deepest flow in the lowest-priority class is trimmed first. Under load the node sheds bulk payloads (which recipients can re-request via `CHUNK_REQUEST`) rather than dropping texts or routing traffic.

**Static mode boost:** Nodes marked as static (plugged in, always on) get 3× quantum, per-flow cap, and total cap.

---

## Relay jitter and micro-batching

Received messages are not immediately committed to the outbound scheduler. Instead they sit in a `RelayBatcher` for a random 100–500ms window before being flushed.

Two benefits:
- **Timing correlation resistance** — a passive observer cannot reliably link an incoming message to its outbound relay by comparing timestamps.
- **Micro-batching** — multiple relay candidates are committed to the scheduler in one shot rather than one-at-a-time, reducing transport wake-up overhead.

Locally composed messages bypass the batcher entirely — the sender does not feel the delay.

---

## Topology and peer selection

`TopologyTracker` maintains one `Route` record per peer. After each gossip exchange it accumulates:

- `bytesRelayed` — cumulative bytes successfully transferred with this peer (primary ranking signal)
- `sessionCount` — number of completed exchanges
- `latencyMs` — smoothed exponential average; stored for diagnostics only, **not** used for routing (on BLE/Wi-Fi Direct it mostly measures discovery timing, not route quality)

`preferredPeers()` ranks by `bytesRelayed DESC, sessionCount DESC` — high-throughput, reliable peers are preferred.

**Neighbor-overlap-aware broadcasting:** After every exchange, `NeighborStore` records what fraction of the local node's outbound offer the peer already knew (exponential moving average, α=0.25). `selectDiversePeers()` returns 80% lowest-overlap peers (maximum novel reach) plus 20% random exploration to prevent clique ossification where the same tight cluster always exchanges with itself.

---

## Priority links

Two nodes can opt into a persistent connection by exchanging `PRIORITY_LINK_REQUEST` / `PRIORITY_LINK_ACCEPT` direct messages. Once both sides have set `isPriorityPeer = true` on the corresponding Contact, the transport can skip the normal session teardown and keep the connection alive between gossip rounds.

To request a priority link:
```kotlin
gossipEngine.composePriorityLinkRequest(peerId)
```
To accept an incoming request (call from the UI when the user approves):
```kotlin
gossipEngine.acceptPriorityLink(requestMessage)
```
Both sides are automatically marked priority when an ACCEPT arrives.

---

## Static mode

Settings → Node mode → Static mode enables a higher-performance profile for always-on, plugged-in nodes (a phone in a window, a Raspberry Pi, a relay box):

- BLE scans and advertises more aggressively (`BALANCED` power vs `LOW_POWER`)
- DRR quantum, per-flow cap, and total message backlog cap are all multiplied by 3
- Message store cache limit is multiplied by 4 — the node serves as a longer-term cache for peers that were offline

The setting is persisted across restarts and exposed in Settings.

---

## Canary metrics

`GossipEngine.canaryMetrics` exposes live operational counters:

| Metric | Description |
|--------|-------------|
| `dedupHitRate` | Fraction of incoming messages already known (suppressed) |
| `sigFailures` | Messages dropped for invalid Ed25519 signature |
| `exchangeSuccesses/Failures` | Gossip session outcomes |
| `avgRttMs` | Smoothed average exchange round-trip time |
| `relayedMessages` | Messages forwarded on behalf of other senders |
| `queueDepth` | Current outbound scheduler backlog |

Enable **Debug logging** in Settings to access the live **Node metrics** screen.

---

## LoRa integration

Two bridge plugins exist as stubs with clearly marked TODOs:

- `MeshtasticBridge` — connects to Meshtastic hardware via Bluetooth serial or USB serial
- `MeshCoreBridge` — same architecture, MeshCore wire protocol

Both extend `BasePlugin`. Implementing either requires:
1. Framing: read length-prefixed bytes from the serial stream
2. Decoding: parse the protocol's packet format (Meshtastic uses protobufs; MeshCore has its own format)
3. Encoding: serialize a `RumorMessage` into the protocol's packet format

The bridge architecture in Rumor handles everything else.

Network topology with LoRa:

```
Settlement A              Hilltop relay             Settlement B
┌──────────┐             ┌──────────┐             ┌──────────┐
│ Rumor    │  Wi-Fi     │ Rumor +  │  LoRa       │ Rumor    │
│ mesh     │◄──Direct──►│ LoRa     │◄──────────►│ mesh     │
│ (phones) │            │ bridge   │             │ (phones) │
└──────────┘            └──────────┘             └──────────┘
```

---

## Deployment notes

- No carrier connectivity required. BLE and Wi-Fi Direct work on carrier-locked phones.
- Sideload via F-Droid or direct APK. No Play Store dependency.
- Foreground service with persistent notification keeps the gossip engine alive on Android's aggressive process management.
- Battery optimisation exemption is surfaced automatically in Settings for OEMs known to kill background processes.
- For relay nodes: a phone in a window plugged in permanently becomes a high-throughput node automatically. Add a LoRa dongle for inter-settlement reach.

### Companion apps

These work well alongside Rumor on the same device:

- **Meshtastic** — LoRa long-range with external radio modules
- **Signal** — while internet exists
- **Kiwix** — offline Wikipedia, WikiMed, iFixit
- **OsmAnd** — offline maps with downloaded regions
- **Trail Sense** — compass, barometer, star navigation from phone sensors
- **LocalSend** — local file transfer
- **KeePassDX** — encrypted credentials

---

## Known limitations

- **Persistent priority-link connection at transport layer:** The `isPriorityPeer` flag, compose/accept messages, and reconnect watcher are wired (priority peers are tracked and rediscovered with exponential backoff after a Wi-Fi Direct disconnect). What's not yet done is holding the Wi-Fi Direct group open *across* gossip-session boundaries — sessions today are still one-off exchanges, just with priority-peer-aware reconnection.
- **LoRa bridges — DM bridging and USB transport:** `MeshtasticBridge` and `MeshCoreBridge` ship working BLE codecs (Meshtastic via `MeshPacket.decoded`, MeshCore via v3 BLE-NUS opcodes) and bridge `TEXT_MESSAGE_APP` / channel broadcasts. Not yet implemented: USB serial/CDC transport, DM bridging via PKC (framework in place via the pluggable `DmEnvelope` registry), multi-channel selection UI, device picker for multi-radio setups, MeshCore v1/v2 firmware fallback.
- **Threat model and overclaims:** Rumor is not Tor and not Signal. It provides better privacy than centralized messengers (no server, no phone number, end-to-end encrypted) but weaker anonymity than Tor (a co-located observer with multiple radios can correlate traffic; userId is on the wire for signature verification). A formal threat-model page is pending (CLAUDE.md O34); until then, do not use Rumor as a sole defence against a state-level adversary targeting you specifically.
- **Backlog:** Living list of open items, design decisions, and completed gaps lives in `CLAUDE.md`. Notable open work: receiver-side forward secrecy (currently sender-FS only), DM breadcrumb routing (today's DMs are flooded with bounded TTL), pre-crisis contact onboarding flow, identity backup/recovery, plugin runtime loader.

---

## License

See `LICENSE`.
