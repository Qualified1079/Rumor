# :node — rules that apply when you work here

Headless JVM runtime (O106): `LanTransport`, in-memory repos, HTTP status page. A test instrument today; the product node is O44/O104.

- `Main.kt` constructs a `MeshGraph` and runs host loops through `MeshRuntime`. It adds transports and the status page only; no engine-graph constructor calls here.
- `SybilDriver` (`node --sybil`) is an attack tool and is allowed a deliberately bare engine. It exists so adversarial input reaches a real node over the real wire.
- Status page: `http://127.0.0.1:8180/`, with room controls at `/act/<create|join|invite|post|act>` and `/rooms`. Windows launcher: `scripts/rumor-node.cmd` (runs `node/build/libs/node.jar`).
- The user's PC has Ethernet only, no Wi-Fi adapter: a phone must be on the same LAN subnet for the LAN path, and Wi-Fi Direct to the PC is impossible.
- No shutdown hook yet (O247), and `LanTransport` start/stop has no generation token (O230); keep those in mind before relying on restart behaviour.
