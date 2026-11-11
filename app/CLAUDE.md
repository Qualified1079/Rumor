# :app — rules that apply when you work here

Android host: Room, Koin, Compose, Wi-Fi Direct/BLE/LAN transports, bridges. The root `CLAUDE.md` invariants apply (MAC is never identity; one engine graph via `MeshGraph`; O56 no-GMS rules).

## Storage

- **Room schema change: bump `RumorDatabase.version` AND commit the newly exported `app/schemas/…/<version>.json` in the same commit** (O116). `fallbackToDestructiveMigration()` is dev-only; release needs a real `Migration` per bump once shipped. Current version: **13** (version history KDoc in `RumorDatabase.kt`).
- A new `RumorMessage` field needs its `MessageEntity` column in the same commit, plus `MessageEntityExtRoundTripTest`.
- A new repository method needs the Room adapter and the in-memory impl together. Room-only semantics need an `androidTest` parity test like `RoomRepositoryParityTest`; run it with `am instrument`, not `connectedAndroidTest`, which uninstalls the app and its identity.
- Graph parts reach ViewModels as `single { get<MeshGraph>().x }`; never construct an engine class in `AppModule`.

## Tests

- **Don't remove `junit-vintage-engine`.** Without it every JUnit4 class in this module is skipped silently and the run stays green.
- `:app` cannot be built in the cloud container (no Android SDK); hand-review there and say so. It builds on the Windows machine: see `handoff.md` §1 for adb, fleet serials and clock offsets.
- Identity screens are `FLAG_SECURE` (screenshots are 0 bytes); read the UI with `uiautomator dump`.

## Transport

- `WifiDirectTransport.kt` is field-tuned and verifiable only on the phone fleet. Do not restructure it standalone (O248); its state model changes with the transport SPI (O107).
- Negotiated `connect()` ignores `setGroupOperatingFrequency`; only autonomous `createGroup` honours the channel (O99 tombstone).

## Bridges (`plugin/meshtastic`, `plugin/meshcore`) → `docs/BRIDGING.md`

- BLE-only, broadcast-only, v0.1.0. DM bridging is unimplemented (O5). For broadcasts Rumor hands the radio plaintext and the radio applies its own channel PSK; inbound arrives as plaintext (`MeshPacket.decoded` on Meshtastic, after firmware decryption on MeshCore). Rumor never touches channel keys.
- **Encrypted bridging (O5): read `docs/BRIDGING.md` before designing it.** Default is Architecture B (envelope passthrough, the bridge sees ciphertext only) through the shipped `DmEnvelope` registry (O5a). Its six security constraints are in § Security constraints; the headline is that `selfAuthenticating` is honoured only for `MessageSource.LOCAL_BRIDGE`.
- Bridged traffic is never re-relayed (root invariant; O17 is the path to wider reach).
