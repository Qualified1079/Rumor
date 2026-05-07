# Rumor

A Wi-Fi Direct mesh communication protocol for Android. No servers, no phone numbers, no internet required.

Messages spread like rumors — carried further by people who think they're worth repeating, dying out when nobody cares.

---

## How it works

**Discovery:** BLE advertising announces presence. No identity is in the advertisement — just a service UUID that says "I'm a Rumor node." BLE MAC addresses are randomized on Android and change constantly on privacy ROMs (GrapheneOS, CalyxOS), so they carry no meaning here.

**Transport:** When BLE detects nearby nodes, Wi-Fi Direct takes over. Two phones connect, exchange everything they each lack, then disconnect. One rich exchange replaces the need for a persistent connection.

**Flooding:** Every broadcast gets a hop count (TTL, default 7). Each node that receives a new message rebroadcasts it once with TTL decremented. Message dies at zero. Manual relay by a user resets TTL — good information travels as far as the community decides.

**Direct messages:** No TTL. Travel until delivered or every path is exhausted.

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
│  MAC-   │  │          │  │  │ Meshtastic    │  │
│ agnostic│  │device-   │  │  │ Bridge        │  │
│ signal  │  │ quirk    │  │  ├───────────────┤  │
│  only   │  │ aware    │  │  │ MeshCore      │  │
└─────────┘  └────┬─────┘  │  │ Bridge        │  │
                  │         │  └───────────────┘  │
                  │  PeerExchangeResult            │
                  │         └────────────────────┬─┘
                  │                              │
┌─────────────────▼──────────────────────────────▼─┐
│  Gossip Engine                                   │  Pure protocol logic, no radio code
│  - Duplicate suppression (size-based LRU)        │
│  - TTL flood / DM relay                          │
│  - Manual relay with TTL reset                   │
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
| Identity manager | `core/identity/` | Keypair, passphrase protection |
| Plugin API | `plugin/` | `RumorPlugin`, `PluginContext`, `BasePlugin` |
| Meshtastic bridge | `plugin/meshtastic/` | LoRa bridge stub |
| MeshCore bridge | `plugin/meshcore/` | LoRa bridge stub |
| Orchestrator | `service/MeshService.kt` | Wires everything, manages lifecycle |
| UI | `ui/` | Compose screens and ViewModels |

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
    sequenceNumber  Long      Per-sender monotonic counter. Recipients detect gaps.
    elapsedMs       Long      Milliseconds since creation. Each relay adds hold time.
                              Clock-agnostic — no NTP dependency.
    type            Enum      BROADCAST | DIRECT | PING | PONG
    ttl             Int       Remaining hops (BROADCAST only). 0 = no TTL (DIRECT).
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

**Direct message encryption:** Sender generates an ephemeral X25519 keypair, performs DH with the recipient's long-term public key, derives an AES-256-GCM session key. The ephemeral public key travels with the ciphertext so the recipient can recompute the shared secret.

**Broadcast messages:** Signed but not encrypted. Anyone on the mesh can read them.

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

## License

See `LICENSE`.
