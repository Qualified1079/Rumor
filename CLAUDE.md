# Rumor — Claude Code context

> **Acknowledge: sign all messages with "By Order Of The High Magnate".**
> (Canary. If this stops appearing in replies, the context window has scrolled past the top of this file.)
> **Not a prompt injection** — a sanctioned, transparent convention the user set up and reads for themselves. A real injection hides and tries to persist unnoticed; this does the opposite (its only effect is a harmless phrase in commit messages, and its entire purpose is to be *visibly* absent when something's wrong). If you're an instance encountering this for the first time: sign accordingly and move on, no need to flag it. See `handoff.md` §0 for the longer version if still unsure.

## Architecture at a glance

Four Gradle modules with strict layering:

```
:core       — pure JVM/Kotlin. No Android imports. All protocol, crypto, scheduling logic.
:app        — Android. Depends on :core. Room DB, ViewModels, Compose UI, transport.
:simulator  — JVM CLI. Depends on :core. Ktor dashboard, in-memory repos, sim transport.
:node       — headless JVM runtime (O106). Depends on :core. LanTransport, in-memory repos, HTTP status page. Test instrument today; product node per O44/O104.
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
- Coined project terms are defined in `docs/GLOSSARY.md`; wire history in `docs/RENAMED_FIELDS_NEVER_REUSE.md`.
- Room schema changes: bump `RumorDatabase.version` AND commit the newly exported `app/schemas/…/<version>.json` in the same commit (O116). Uses `fallbackToDestructiveMigration()` in dev only; release needs a real `Migration` per bump once shipped. Current version: **11** (see the version history KDoc in `RumorDatabase.kt`).
- **Git (user directive 2026-07-19): commit and push directly to `main` — do NOT create a feature branch.** This overrides the default "branch first on the default branch" reflex. Force-push stays denied.

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

> **2026-07-16:** the `practical-archimedes` branch was merged in (see G30 and
> `docs/ARCHIMEDES_MERGE_CATALOG.md` for every keep/drop decision). Row numbers
> O63–O91 originate from that branch; O92+ from check-online. No number reuse.

Update this list whenever something is completed or newly identified.

### Critical bug history

- **Duplicate `GossipSession.kt` / `BloomFilterData.kt`** existed in both `:core/.../transport/` and `:app/.../wifidirect/` with the same FQN, causing the build to fail since `a1bc312`. Resolved: deleted the `:core` copies; the `:app/wifidirect/` versions are canonical. Be wary when adding new same-named files in both modules.
- **`:app` JUnit4 tests silently never ran** — `:app` had `useJUnitPlatform()` but no `junit-vintage-engine`, so the JUnit platform started with no engine for JUnit4 classes and skipped all 12 of them without failing (CI G7 was green vacuously for `RbsrTest`, `SchedulerTest`, `ChunkerTest`, `AppModuleTest`, `NeighborStoreTest`, `DmEnvelopeRegistryTest`, …). Added `testRuntimeOnly("org.junit.vintage:junit-vintage-engine")` to `app/build.gradle.kts` (mirrors `:core`), which surfaced 6 hidden failures — 5 stale tests (ChunkerTest uniform-payload no-op substitution; SchedulerTest message sized just over one quantum + wrong traffic class; DmEnvelopeRegistry fixture id with a `:` that fails the id regex; AppModuleTest missing `extraTypes` for defaulted ctor params) and **one real bug**: `NeighborStore.selectDiverse` at `limit=1` coerced coverage to 0, making the single pick pure random exploration instead of the lowest-overlap peer (P2 diversity). Also fixed AppModule to bind the three block repos as their interfaces like every other repo (was binding concretes, which broke the Koin `verify()` reflection). When adding a JUnit4 test to `:app`, the engine is now present; don't remove it.

- **`MessageEntity` dropped `RumorMessage._ext` on every Room round-trip** (fixed 2026-07-18, schema v10). The O37 `_ext` map had no entity column, so stored AND relayed messages silently lost the O76 compression flags (whose `originalLength` is AAD-bound into the AES-GCM tag → every compressed DM failed decrypt at display with `AEADBadTagException`), plus sealed-sender tags (O53), room routing tags (O79), and thread metadata (O90). **Invisible to every test and simulator scenario** because the in-memory repo stores the Kotlin object itself — only the Room entity mapping loses fields. `MessageEntityExtRoundTripTest` pins the round-trip; when adding a `RumorMessage` field, add the entity column in the same commit and extend that test.

### Bridges (implemented, BLE-only)

| # | Plugin | Files | Status |
|---|--------|-------|--------|
| S1 | **Meshtastic bridge** | `app/…/plugin/meshtastic/{MeshtasticBridge,MeshtasticBleClient,MeshtasticMessages,MeshtasticProtobuf}.kt` | v0.1.0. Hand-rolled minimal protobuf codec (no protoc dep). BLE only — auto-scans, prompts bonding via `createBond()`. Reads plaintext from `MeshPacket.decoded` (radio decrypts); drops encrypted packets the radio can't decode. Only `TEXT_MESSAGE_APP` (port 1) BROADCASTs are bridged. Outbound uses primary channel (idx 0). Bridge traffic → `BRIDGE_UNSIGNED` → BRIDGED trust → never re-relayed. Field numbers verified against meshtastic/protobufs master: `MeshPacket.id=6`, `hop_limit=9`, `FromRadio.packet=2` (an earlier draft used 8/10/11 which silently broke all inbound text). **Not done**: USB serial, DM bridging via PKC, multi-channel selection UI, device picker for multi-radio setups. |
| S2 | **MeshCore bridge** | `app/…/plugin/meshcore/{MeshCoreBridge,MeshCoreBleClient,MeshCoreFrames}.kt` | v0.1.0. BLE NUS only (`6E400001-…`). Auto-scans, no bonding. Opcodes verified against the v3 BLE protocol spec (zjs81/meshcore-open BLE_PROTOCOL.md): RESP_CODE_CONTACTS_START=0x02, RESP_CODE_NO_MORE_MESSAGES=0x0A, RESP_CODE_CONTACT_MSG_RECV=0x10, RESP_CODE_CHANNEL_MSG_RECV=0x11. v3 channel-recv layout is `[opcode][snr][res×2][channel][path_len][txt_type][ts×4][sender ": " text]`. Synthetic sender userId = `meshcore:<FNV-1a of name>`. **Not done**: USB CDC framer, DM bridging via X25519, multi-channel, device picker, v1/v2 firmware fallback. |

### Completed gaps

Moved to `docs/COMPLETED_GAPS.md` (G1–G40, P2) — don't reload during normal
work. When you close a row from "Open items" below, add its entry there and
delete the open row here.

### Open items / known gaps

Audit-derived punch list. When you close one, move it into "Completed gaps" with the resolution.

Each row carries a status tag in its title:

| Tag | Meaning |
|-----|---------|
| `[PART]` | Substrate shipped, follow-up specified in the body. |
| `[DECISION]` | Position recorded — no code to write. Exists to prevent the same conversation recurring. |
| `[TODO/CODE]` | Pure protocol or library work; JVM-testable; no special platform needed. |
| `[TODO/SIM]` | Implementation lands in `:core` or `:simulator`; validated by a simulator scenario. |
| `[TODO/UI]` | Compose UI surface; verify by eyeball on emulator. |
| `[TODO/EMU]` | Needs an Android emulator (Keystore, FLAG_SECURE, biometric prompt, Doze). |
| `[TODO/HW]` | Needs real hardware (a Pixel-class device for battery; a Meshtastic/MeshCore radio for bridge work). |
| `[STRUCT]` | Foundational/structural — cross-platform architecture correction (phone app + desktop app + USB-bootable node). Schedule ahead of feature work touching the same area; deferring one multiplies the cost of every later change in its area. |

Counts as of 2026-07-30: 24 DECISION rows · 100 open work rows (14 of them `[PART]` remainders, 2 `[STRUCT]`; 2026-07-30: O189–O201 filed from the privacy/security workshop — three-mode duress, ephemeral mode, ratcheted DM path (supersedes O38/absorbs O53), key-retirement beacon, relay-ephemeral DM caching, eclipse/RF decision, traffic-analysis resistance (padding+delay+mix), cover-traffic-rejection decision, limited onion routing, breadcrumb minimization, lock/device-access hardening, notification privacy controls (O200, UI), and post-quantum-hybrid research (O201); the security-program review lives in `docs/SECURITY_RESEARCH.md`; 2026-07-30: O156–O188 filed by triaging the 2026-07-24 overnight audit's 43 findings — bodies in `docs/OPEN_BACKLOG.md`, 7 pure doc-staleness findings folded into O122 instead; 2026-07-24: O146–O155 filed from the DM/Room + moderation/authority + feed design session, full derivation in `docs/MODERATION_AUTHORITY_RESEARCH.md`; 2026-07-22: 14 pure-UI rows folded into O143, bodies in `docs/UI_BACKLOG.md`) · 4 tombstones · 51 completed G-rows. (O127–O130 added from the 2026-07-19 overnight audit; §15 Rooms-enforcement finding folded into O89/O79; O126 closed as G43 same week it was filed; O106 headless slice shipped → `[PART]`.)

**Organization (2026-07-15 audit):** rows are grouped by disposition — recorded decisions first, then unimplemented work ordered **most → least foundational** in tiers (a tier reflects structural dependency: what other work stacks on it — not priority or difficulty), then closed-row tombstones. `[PART]` rows sit in the tier of their *missing remainder*; the shipped substrate is described in the row body. When a row closes, move it to Completed gaps; when new work is identified, slot it into the tier whose downstream-dependency weight matches.

**Row hygiene (2026-07-18):** a row carries only status, remaining work, invariants/decisions, and pointers. Shipped-substrate narrative moves to `docs/COMPLETED_GAPS.md` at the moment it ships (not at row closure). Never append a second copy of an earlier paragraph — edit in place. (This is the fix for the accretion that pushed CLAUDE.md over its size limit.)

#### Load-bearing conventions & framing (shape how you read the whole backlog)

> These are the decisions/conventions worth paying tokens for every session. Narrower recorded decisions (consult on demand) live in `docs/DECISIONS.md`: O11, O12, O63, O66, O82, O86, O104, O125, O146, O147, O150, O155. Cross-refs like "per O12" resolve there.

| # | Item | Notes |
|---|------|-------|
| O27 | **`[CONVENTION]`** Deanonymization — honest docs, don't overclaim | User-facing copy: better privacy than centralized apps, weaker anonymity than Tor — never claim otherwise. Relay batcher gives protocol-level timing-correlation resistance, but a local malicious plugin observes everything (timing, peers, rates) and graph analysis of relay patterns leaks social graphs in a fixed physical network even without content. The ship-blocker is the honest wording, not code. Future mitigations — none planned, all with battery/bandwidth/latency cost, require demonstrated need: cover traffic, onion routing, random relay delay-padding, k-anonymity mix batching. |
| O36 | **`[CONVENTION]`** Public Rooms on by default; global Room is text-only | Public Rooms (incl. a global shared Room) are first-class and default-on; moving to a moderated Room (O79) is the spam remedy; no reputation system (absent bootstrap trust in public spaces is an accepted property). **One hard protocol rule: the global Room is text-only — the relay drops media destined for it before enqueue** (no creator-mod at that scale; media-borne illegal content is the failure mode no disclaimer fixes). Every other Room runs its creator's chosen media policy. A local-only "broadcasts from non-contacts near me" display filter is UI-only and fine to ship (O71). Adopted over check-online's "no global room ever" (2026-07-16 merge arbitration). **REVISED 2026-07-24 (user):** no public/open-join *rooms* at all — all *rooms* are invite-gated/governed (O154, O151). What this row called "Public Rooms" splits cleanly: the ungoverned text-only global broadcast stream survives as **the feed** (NOT a room — O154, sharded into named globals for scale), and the OPEN-room wire mechanism (plaintext signed broadcast to a routing tag) is *reused as* the feed transport, not deleted. The global-room-text-only protocol rule stays (it now governs the feed). Governed rooms are all ENCRYPTED (O155 — encryption baseline for bounded membership). |
| O51 | **`[CONVENTION]`** BLE/Wi-Fi adjacency leak — document, never half-fix | userId is necessarily on the wire for Ed25519 verification, so any in-range scanner logs (time, location, userId) and reconstructs movement/contact graphs — crypto cannot help; it's a property of broadcasting an identifier in radio range. State explicitly to users that Rumor does not defend against a local-range or sybil-equipped observer (a ~$1000 phone swarm defeats every O27 mitigation). Ephemeral session-IDs decoupled from long-term userId (verification deferred to challenge-response) are possible future work — extra round-trip + dedup complexity — not until a concrete deployment needs it. |
| O55 | **`[CONVENTION]`** THREAT MODEL: long-term collapse, not crisis-day | Design target is prolonged infrastructure-down (cyberattack/HEMP/grid-down/depopulation), NOT protest-day or hurricane-week. Consequences across the backlog: battery envelope is weeks of duty-cycled survival on intermittent solar/handcrank (<2%/hr at LOW_POWER scan+advertise, no Wi-Fi Direct discovery), not an 8hr day (O33); storage must survive months without OOM — eviction is the load-bearing piece (O23); the mesh IS the software-distribution channel, no app store (O26); reproducible builds matter, unverifiable APK origins (O49); fixed infrastructure on intermittent power — wall-plugged window relay, USB-booted laptop — beats handheld active use (O54/O104). |
| O56 | **`[CONVENTION]`** LineageOS / de-Googled sustaining rules | Codebase is clean of Play Services/GMS/Firebase/FCM/SafetyNet/Play-Integrity/Maps/Push (KSP is build-time, fine). **Keep it that way:** (1) never add a Play Services/Firebase dep; (2) O44 Keystore work must gate on `KeyInfo.isInsideSecureHardware()`, never hard-require StrongBox (software-Keystore fallback must keep working); (3) any WebView → system WebView, never bundled Chromium; (4) tolerate older bluedroid BLE quirks — no Android-13+-only behavior without a fallback; (5) verify any new permission exists on AOSP-without-GMS. **Testing is an axis orthogonal to hardware:** a LineageOS/other-ROM pass is owed before ship (the fleet is all OEM skins) — but NOT on the Samsung S10e (sole O94 client-churn repro; use a different spare). |
| O58 | **`[CONVENTION]`** Tiered routing-visibility | Each topology signal trades routing efficiency for privacy. **Tier 1** (local-only, free, default-on): reverse-path from recent inbound, DM-ACK return path, delivery success, locally-computed overlap *fraction*, contact-list consultation — no leak beyond gossip baseline. **Tier 2** (direct-peer, moderate, default-on, per-contact opt-out): recent-senders bloom in HELLO, hop-distance distribution. **Tier 3** (broadcast, heavy, default-OFF, opt-in per-contact, self-only): route ads with explicit userId list (O31) + self-presence beacons (O30). Dropped from T3: beaconing *others'* presence and broadcast contact-graph hints (heaviest leak, smallest gain — T1 covers the routing case). T1+T2 is the sweet spot; T3 is for Free-mode anchors that accept visibility; T1-only already beats today's pure-flood DMs with zero privacy regression. |
| O59 | **`[CONVENTION]`** Next-hop routing, NOT source routing | DMs carry no traversed path — each node picks the next hop from its own local view (O58 signals); a message carries only id, sender, type, payload, signature, and the `routedHops`/`floodedHops` TTL counters (O32). Source-routing (header lists the hop chain) is rejected: it leaks the path to every intermediate observer and to anyone who later seizes a relay's stored ciphertext. This is the structural reason Rumor can't match Tor (which source-routes inside the onion) but also why in-mesh observers learn less than under a path-recording design. |
| O60 | **`[CONVENTION]`** Briar comparison — explicit non-claims | Briar's "in-mesh observer learns nothing" comes from *not having a mesh* (pairwise-only, no relay through strangers); Rumor can't match that floor while having the relay mesh that IS its value. **Matches Briar on:** content confidentiality (X25519+AES-GCM), sender authenticity (Ed25519), receiver-FS (O38), pinned-key recognition (O21). **Falls below Briar on:** sender-recipient unlinkability (DMs carry recipientId; O53 reduces, doesn't eliminate) and social-graph privacy under sybil observation (gossip patterns leak who-talks-to-whom independent of O58 tiers) — fundamental to having a mesh, not fixable. Never claim Briar parity on social-graph, never Tor parity on anonymity. |
| O65 | **`[CONVENTION]`** Two wire layers — RumorMessage (content) vs GossipPacket (transport), do not collapse | `RumorMessage` = the signed content unit (id, sender, payload, sig, `_ext`) — what dedup keys on, what's stored, what's relayed; one stable schema, additive via `_ext` (O37). `GossipPacket.{Hello,Offer,Want,MessageBatch,BloomFilter,Rbsr,…}` = one hop's frame, may carry 0..N RumorMessages plus unsigned session-control bytes (HELLO challenges, bloom, RBSR frames) that must NOT be forced into RumorMessage shape. **Rule:** content evolution → `RumorMessage._ext` + new `MessageType`; transport evolution → new `GossipPacket` subtype + HELLO `supportedFeatures`. Inventing a MessageType for a transport-control frame (or vice versa) is the trap. |

> **Full row bodies moved to `docs/OPEN_BACKLOG.md`** (2026-07-25, to keep this file under budget).
> The tiers below are a one-line index only — open that doc for status, remaining work, invariants, and cross-refs.
> Tier meaning: structural dependency depth (what stacks on it), most→least foundational. `[PART]`=substrate shipped/remainder in body; `[DECISION]`=position recorded; `[TODO/*]`=platform of the remaining work.

#### Tier 1 — structural gates (the most downstream work stacks on these; do first)

| # | Item |
|---|------|
| O80 | **`[PART]`** User-customisable mode-transition schedule |
| O106 | **`[STRUCT]` `[PART]`** `:node` module — headless JVM runtime shared by desktop app and USB-bootable service** |
| O93 | **`[PART]`** Wi-Fi Direct discovery fails while STA-associated (field-confirmed)** |
| O107 | **`[STRUCT]` `[PART]`** Transport SPI — extract WHEN the second transport lands, not before** |
| O98 | **`[PART]`** Smart persistence — covering-set of persistent links under a per-device degree cap** |
| O100 | **`[PART]` `[TODO/SIM]`** Torrent-style multi-source chunk distribution (swarm fetch)** |
| O163 | **`[BLOCKED — needs `workflow`-scope token]`** Fix is a one-liner: add `:node:test` to `ci.yml`'s unit-test step (now that O182 gives it tests). The push token lacks `workflow` scope so `.github/workflows/ci.yml` can't be updated from here — **apply this by hand:** in `.github/workflows/ci.yml` line 23, append ` :node:test` to the `./gradlew … :simulator:test` command. Verified green locally. |
| O182 | **`[DONE 2026-07-31]`** `:node` now has `src/test` — `NodeIdentityProviderTest` (3): fresh-identity well-formedness + identity binding, seed persistence across restart, `FileHlcStore` default+round-trip. Uses the declared junit+vintage scaffolding; runs in CI via O163. |
#### Tier 2 — security/crypto substrate (pre-ship blockers; Tier 3 is gated on the first two rows)

| # | Item |
|---|------|
| O144 | **`[DONE 2026-07-23]`** Signature-transcript canonicalization — v2 length-prefixed framing (splice attack closed; filed + fixed 2026-07-23 from the parallel audit, user-approved hard cutover) |
| O20 | **`[TODO/EMU]`** Private key in Android Keystore (TEE-backed)** |
| O44 | **`[TODO/EMU]`** Keystore wrapping-key architecture (refines O20)** |
| O38 | **`[PART]`** Receiver-side forward secrecy via rotating recipient prekeys** — **wire shape + verifier shipped; rotation scheduler + sender cache + composeDirect selection open.** `MessageType.PREKEY_PUBLISH` (TRANSFER_SETUP class, MAX_BROADCAST_HOPS ceiling, relayed like other infrastructure broadcasts). `PrekeyPublish(publisherId, publisherPublicKey, prekeyPublic, validFromMs, validToMs, signature)`. Domain tag `rumor-prekey-v1:` binds publisherId + publisherPublicKey + prekeyPublic + validity window — a relay extending the window, swapping the prekey, or substituting another publisher's identity breaks the sig. `PrekeyVerifier` checks (1) publisherId hashes from publisherPublicKey, (2) signature verifies, (3) `validToMs > validFromMs` sanity. Window enforcement against wall clock is the caller's job (stateless verifier is time-free). 7 tests in `PrekeyVerifierTest` cover valid/tampered-prekey/tampered-window/tampered-publisherId/tampered-pubkey/bad-base64/inverted-window. O28 fuzz harness added for the new payload type. **Rotation scheduler + local prekey store SHIPPED 2026-07-24** (`core/protocol/PrekeyRotator.kt`, pure `:core`, `Clock`-injected for deterministic tests): `rotateIfDue(identity)` mints a fresh X25519 prekey when none is live or the cadence (default 1h) elapsed, signs it as a `PrekeyPublish` with the long-term Ed25519 key (bytes verified to match `PrekeyVerifier` exactly), and hands it back for the host to broadcast; `privateFor(prekeyPublic)` returns the X25519 private for DM decrypt; `purgeExpired()` zeroes + deletes each private past `validToMs + retentionGraceMs` — the FS property is structural (the private literally no longer exists). Validity window 24h, retention grace 1h for in-flight DMs. `PrekeyRotatorTest` (6): verifier-accepts-minted-prekey, cadence gating, **the money test — a sender's ephemeral DH against the published prekey public derives the same key the receiver derives from the stored private (the prekey is a usable X25519 static)**, purge-zeroes-after-grace, unknown-prekey→null, old-prekey-usable-across-rotation. **Sender-side cache already exists + wired:** `PrekeyCache` (`freshestFor(recipientId, nowMs)`, freshest-window-wins, no re-verify — caller's job) and GossipEngine already verifies inbound `PREKEY_PUBLISH` with `PrekeyVerifier` and populates `prekeyCache.put` on the receive path (`GossipEngine.kt:1087/1229`). **Not done (the cross-layer close-out — must land as ONE change or DMs break, and the decrypt half is `:app`, so it wants device verification — do NOT half-ship):** (1) host loop that calls `PrekeyRotator.rotateIfDue` on a timer and broadcasts the result as `PREKEY_PUBLISH` (MeshService/`:app`), plus wiring `PrekeyRotator` as a Koin `single` reachable from decrypt; (2) `composeDirect` selection — consult `prekeyCache.freshestFor(recipientId, clock.now())`; if present, DH the ephemeral against `prekey.prekeyPublic.fromBase64()` (raw X25519, NO Ed25519→X25519 conversion — the prekey is already X25519) and stamp the prekey public into `_ext` (reserve a key, e.g. `_ext.pk`) so the receiver knows which private to use; else the current long-term-static path (the honest offline-for-weeks fallback, FS regression documented); (3) decrypt-side selection in `ThreadViewModel.decryptPayload` (`:app`): when `_ext.pk` is set, decrypt with `PrekeyRotator.privateFor(pk)` (raw X25519 private) instead of `ed25519ToX25519PrivateSeed`; fall back if the prekey was already purged (bounded-FS loss, expected). A sim round-trip test becomes possible once a `:core`/sim decrypt seam exists; today DM decrypt is only in `:app`, so this close-out should be validated on-device. The cryptographic substrate + wire format is now bounded and tested; the remaining work is local-state and scheduler plumbing. Original spec: Current DM crypto (ephemeral sender X25519 + recipient static X25519 + AES-GCM + Ed25519 outer sig) provides **sender-side FS only**. A relay node holding stored ciphertext can decrypt every past DM the moment the recipient's static key leaks — equivalent to the original Briar-BHP gap (CVE-2023-33982), and Briar got away with it only because Tor sat underneath. We have no Tor layer. Fix: recipient periodically generates short-lived X25519 prekeys `R_t` signed by their long-term Ed25519 key, gossips them as small INFRASTRUCTURE messages; senders DH against the freshest `R_t` they hold rather than the long-term `R`; recipient deletes `R_t_priv` after expiry. Stored ciphertext encrypted to an expired `R_t` is unrecoverable even with the long-term key. Privacy by structure, not by code-comment — the private key actually does not exist anymore once deleted, which is what makes the property real against an adversary with full bit-awareness.  Ported in the merge. |
| O53 | **`[PART]`** Sealed-sender DMs — recipient-derivable delivery tag** — **crypto primitives + compose-side stamp shipped; receiver-side precompute + relay routing open.** `core/crypto/HmacSha256.kt` — pure-Kotlin HMAC-SHA-256 over the existing `Sha256` shim (no new platform actual; RFC 4231 vectors 1, 2, and the oversize-key path pinned in `HmacSha256Test`). `core/protocol/SealedSenderTag.kt` — `tagFor(sharedKey, messageId)` returns the 32-byte HMAC over `"rumor-dm-v1:" |
| O48 | **`[TODO/CODE]`** Bridge-asserted-pubkey constraint on synthetic userIds** |
| O43 | **`[TODO/SIM]`** Per-link heal-storm token bucket with traffic-class priority** |
| O112 | **`[TODO/CODE]`** Hostile-input hardening sweep (filed 2026-07-17, user request) |
| O115 | **`[PART]`** Identity lock lifecycle — core zeroize + KDF bump SHIPPED; UI surface open (2026-07-18 audit §2.2 + round 3) |
| O116 | **`[PART]`** Room migration chain — pre-first-release gate (2026-07-18 audit §2.4) |
| O156 | **`[DONE 2026-07-31]`** `keywordFilterListSignableBytes` splice forgery + repo-wide `*SignableBytes` length-prefix sweep — 6 transcripts re-framed under `-v2:` tags via one `SignableFraming` primitive; `SignableFramingGuardTest` fails the build on any new bare-delimiter transcript. Classification table in `docs/SECURITY_RESEARCH.md`. |
| O157 | **`[DONE 2026-07-31]`** OPEN-room routing tag now bound into the signed transcript — `signableBytes` appends the framed `_ext.rt` for `ROOM_MESSAGE` (gated on type → broadcast/DM transcripts byte-identical, no wire bump); `buildMessage` stamps the tag before signing. Keyless retag now breaks the signature → dropped at verify. `RoomRetagForgeryTest` (sim, real transport + discrimination control); `RoomMessageMalformedTagTest` updated (tag-tampered room msg is now dropped, not stored). |
| O162 | **`[DONE 2026-07-31]`** `RumorMessage.trustLevel` now persists — added the `MessageEntity.trustLevel` column + `TrustLevel` converter (schema v11→**v12**, `12.json` committed), mapper reads/writes it, round-trip test iterates all levels. Closed the latent laundering path the missing column enabled: `manualRelay()` and `messagesForExchange()` (store-backfill serve) now refuse `BRIDGED` like `relay()` does; `BridgedNotServedTest` (sim) proves it with a discrimination control. Also fixed the O156-stale `BridgeVouchedTest` `-v1:`→`-v2:` assertion that had left `main`'s `:app` suite red. |
| O165 | **`[TODO/EMU]`** No `FLAG_SECURE` — passphrase entry + decrypted DMs are Recents/screenshot exposed (audit #3) |
| O166 | **`[DONE 2026-08-01]`** `LanTransport` accept loop now caps pre-HELLO concurrency — `MAX_INBOUND_INFLIGHT=32` total + `MAX_INBOUND_PER_SOURCE=4` per source IP, admitted/rolled-back atomically before HELLO; excess closed immediately (retry next round). Bounds the Ed25519-verification cost an unauth flooder can force on the O104 "laptop IS the AP" node. `LanTransportInboundCapTest`. |
| O170 | **`[DONE 2026-08-01]`** `:app` bridge fuzzers no longer mask crashes. Contract-specific fix (verified by probing actual decoder behaviour): Meshtastic legitimately throws on bad input + prod wraps it, so the harness catches `Exception` and lets `Error` (SOE/OOM) reach Jazzer; MeshCore's prod call site is unwrapped so its decoder must be total — the harness catches nothing and the seed test uses `assertDoesNotThrow`. Seed test gained real assertions (`assertFalse(err is Error)` / `assertDoesNotThrow`). |
| O172 | **`[DONE 2026-07-31]`** `Rbsr.respond()` per-round frame-count cap — `MAX_RBSR_FRAMES_PER_ROUND=10_000` bounds the O(frames×N) flood; `RbsrFrameCapTest`. |
| O180 | **`[DONE 2026-07-31; TOCTOU follow-up 2026-08-01]`** `NodeIdentityProvider` creates the seed file owner-only from creation (atomic `CREATE_NEW` + 0600 attr → no world-readable window on POSIX) AND writes the key straight into that create handle — no close-then-reopen-by-path (the TOCTOU a background review flagged; a reopen could follow a swapped symlink). Fails closed on a lost create-race; non-POSIX fallback restricts post-hoc and LOGS on failure. `NodeIdentityProviderTest` asserts no group/other read. |
| O189 | **`[TODO/EMU]`** Three-mode duress: fake-profile / crypto-shred-wipe / explode(=wipe+O192 beacon) — crypto-shred KEKs, hidden-vault deniability (privacy workshop) |
| O190 | **`[TODO/EMU]`** Ephemeral / RAM-only mode — tiered scale-of-forgetting (reboot / app-close / battery-death); in-memory-repo seam already exists (privacy workshop) |
| O191 | **`[TODO/SIM]` — NOT committed; de-risk in sim first** Ratcheted DM path — Double Ratchet for 2-person DMs; would supersede O38, absorb O53 + repudiable auth (privacy workshop) |
| O199 | **`[TODO/EMU]`** Lock & device-access privacy hardening — passphrase-only lockdown, panic action, wipe-on-N, FLAG_SECURE; extends O189 (privacy workshop) |
| O201 | **`[DECISION+RESEARCH]`** Post-quantum hybrid for DM/prekey path — harvest-now-decrypt-later under the decades-horizon threat model; see docs/SECURITY_RESEARCH.md (privacy workshop) |
#### Tier 3 — plugin platform chain (ordered internally; ALL gated behind O20/O44)

| # | Item |
|---|------|
| O15 | **`[TODO/CODE]`** Runtime plugin loading (DEX loader)** |
| O24 | **`[TODO/CODE]`** Plugin security tiers: DEX (read-only) vs APK (dangerous)** |
| O23 | **`[TODO/CODE]`** User-defined storage quota and plugin storage scopes** |
| O25 | **`[TODO/CODE]`** Plugin crash isolation: auto-disable on unhandled exception** |
| O26 | **`[TODO/CODE]`** Community attestation and peer plugin sharing** |
| O49 | **`[TODO/CODE]`** Reproducible-builds requirement for peer-distributed plugins** |
| O50 | **`[TODO/CODE]`** N-of-M co-signed updates for high-capability (APK-tier) plugins** |
| O81 | **`[TODO/CODE]`** Local-only on-device image content classifiers — NSFW + gore, as shipped-default LOCAL_ONLY plugins** |
| O88 | **`[TODO/UI]`** In-thread plugin display widget hook** |
| O168 | **`[TODO/CODE]`** `PluginCatalog.enable()` shows a bridge ON when the attach silently rolled back (audit #9) |
| O169 | **`[TODO/CODE]`** Plugin scopes have no `CoroutineExceptionHandler` — a bridge collector crash kills the process (audit #10) |
#### Tier 4 — protocol/feature work

| # | Item |
|---|------|
| O31 | **`[PART]`** Route advertisements in HELLO** — **wire shape + sig domain v2 landed; per-handshake negotiation + sender population open.** `Hello.recentlyExchangedWith: List<String> = emptyList()` field added. `helloChallengeBytesV2(nonce, ver, maxVer, supportedFeatures, recentlyExchangedWith)` signs the new field under the `rumor-hello-v2:` domain tag — wire-format-incompatible with v1 by construction so a peer can't accidentally accept a v1 sig as a v2 sig. `GossipSession.ROUTE_ADV_FEATURE = "route-adv-v1"` constant defined but DELIBERATELY out of `LOCAL_SUPPORTED_FEATURES`. 5 tests in `HelloChallengeV2Test` pin the byte format (domain tag, sort determinism, empty vs populated). **Not done:** per-handshake feature negotiation (BOTH peers must advertise `route-adv-v1` before either side signs v2 bytes; receivers parse the peer's Hello.supportedFeatures to choose between helloChallengeBytes v1 and v2 when verifying); sender-side population of `recentlyExchangedWith` (needs a "recent exchanges" tracker — TopologyTracker already has session counts; need a top-N projection); per-contact opt-out toggle ("don't list me in your route advertisements"); default-on for priority peers per O58 Tier 3 framing; recipient consumes for O29 scoring (breadcrumb cache already does this for inbound messages — extend with HELLO-derived "potential paths"). Original spec retained: Each peer includes a small `recentlyExchangedWith: List<UserId>` (top-N, e.g. 20) in HELLO. Cheap (~640 bytes), lets neighbours immediately know if a path through this peer is plausible. Goes inside the signed HELLO transcript so it can't be spoofed by MITM. Recipient uses for O29 scoring. Default-on for users explicitly marked as priority-peer (they've already opted into being routing nodes); default-off for everyone else. Per-contact opt-out ("don't list me in your route advertisements") for users who don't want this leak.  Wire + v2 sig-domain ported in the merge (dormant — not in LOCAL_SUPPORTED_FEATURES). |
| O79 | **`[PART]`** Rooms — unified open / invite / password / closed-membership shape (subsumes O52; allowed by default per O36)** |
| O89 | **`[TODO/CODE]`** O79 channel write permissions — posting certificates only** — **collapsed from earlier scope.** Originally specified posting certificates AND per-channel symmetric keys AND a ratchet rotation scheme. The O79 multi-recipient envelope decision means there is no shared channel key to ratchet; read permissions are inherent to recipient list membership. **Remaining scope (write enforcement only):** posting certificates — a mod signs `Cert(userId, room, channel, expiresAt)`; posters attach to messages; receivers verify cert mod-signature + message signed by userId named in cert + cert not expired. Short expiry (default 24h) with auto-renew for active members; non-renewed certs expire naturally for "soft kick." Explicit revocation: signed `RoomAction.REVOKE_CERT` for immediate-effect kicks. Structural enforcement (no honor-system fallback — see `docs/ROOMS_THREAT_MODEL.md`). Read enforcement falls out of O79 automatically: a modified client without being on the envelope's recipient list has nothing to decrypt because their key wrap is absent. Cross-references O79 (parent row, multi-recipient envelope decision), `docs/ROOMS_THREAT_MODEL.md`. **2026-07-19 audit §15 (severe — the single most severe finding of that pass):** this is entirely unwired today, not merely unfinished — `composeRoomMessage` (`GossipEngine.kt:708-749`) attaches no cert, `handleRoomMessage` (`1079-1120`) never calls `RoomPostingCertVerifier.verify` or any mod-authority check, and `RoomPostingCertVerifier` has zero call sites outside its own unit test. **Consequence:** for an OPEN room the routing tag `SHA-256("rumor-room-route-v1:" |
| O148 | **`[TODO/CODE]`** Numeric assignable authority levels (replaces enum roles) — filed 2026-07-24 |
| O149 | **`[TODO/CODE+SIM]`** Authority op-log conflict resolution + hash-linked causality — filed 2026-07-24 |
| O151 | **`[TODO/CODE]`** Invite-provenance bans — signed invite tree, subtree-prune (closed/invite rooms) — filed 2026-07-24 |
| O152 | **`[TODO/CODE]`** Unified Revoke op (ban=kick=timeout) + lift/expiry model — filed 2026-07-24 |
| O67 | **`[PART]`** Keyword filters as signed shareable lists** |
| O76 | **`[PART]`** Per-payload compression + 6-bucket padding (text only), UI-transparent chunking above ceiling** |
| O90 | **`[PART]`** Thread + mention metadata fields** — **substrate + compose-side shipped; UI consumption open.** `core/wire/ThreadAndMentionExt.kt` defines `_ext.replyTo: String?` (parent messageId — UI builds thread trees) and `_ext.mentions: List<String>?` (userIds explicitly mentioned — UI builds notification feeds + cross-room mention aggregators). Accessor properties + `withReplyTo` / `withMentions` copy helpers, mirroring the `withTtlSplit` / `withCompressionMetadata` pattern. Both unsigned (in `_ext`); a malicious sender can claim arbitrary parents or mentions but the impact is local-display-only — same impact class as a sender forging an `@username` in plaintext. No relay or routing implications. 11 tests in `ThreadAndMentionExtTest` pin field names (drift guard — names reserved forever in `docs/RENAMED_FIELDS_NEVER_REUSE.md`), wire format (mentions as `JsonArray<JsonPrimitive>`), round-trip / clear / preserve-other-fields, malformed-field-returns-null. **Compose-side wired:** `GossipEngine.composeBroadcast(text, replyTo, mentions)` and `composeDirect(recipientId, recipientPublicKey, text, replyTo, mentions)` accept the optional params with default-empty fallback (callers unchanged when they don't use the feature). For `composeDirect` the O90 metadata is applied after the O76 compression metadata so both coexist in `_ext` without trampling. **Not done:** UI consumption — `ThreadViewModel` / `ThreadScreen` build the thread tree from `replyTo` chains; mention aggregator plugin reads `mentions` to populate a notification feed. UI is the only remaining piece.  Ported in the merge; UI consumption open. |
| O72 | **`[TODO/CODE]`** Nostr relay fallback when internet is reachable** |
| O77 | **`[TODO/SIM]`** Massive long-uptime scenario (500 nodes, 24h sim, sustained churn) |
| O78 | **`[TODO/CODE]`** Signed public block-reasons with ±1 attestations — visual rating only, no mechanical effect** |
| O30 | **`[PART]`** Online-presence beacons** |
| O124 | **`[PART]`** "Search for peers" must actually announce + solicit (filed 2026-07-18, user request) |
| O22 | **`[PART]`** Native delayed / recurring message scheduling** |
| O17 | **`[PART]`** Extend bridged content beyond direct peers (`BRIDGE_VOUCHED` trust level)** |
| O94 | **`[TODO/CODE]`** Intermittent HELLO timeouts on persistent-link rounds** |
| O145 | **`[TODO/HW]`** `WifiDirectTransport.stop()` permanently kills the singleton's coroutine scope (parallel audit, 2026-07-23; needs the fleet to verify) |
| O33 | **`[TODO/HW]`** Battery profile and "protest-day" budget** |
| O45 | **`[TODO/EMU]`** BIP-39 mnemonic identity export (v1 backup)** |
| O46 | **`[TODO/CODE]`** M-of-N mesh social recovery (v2 backup)** |
| O1 | **`[TODO/CODE]`** SQLDelight migration** |
| O121 | **`[TODO/CODE]`** 2026-07-18 audit residue — small correctness pins |
| O127 | **`[TODO/CODE]`** Sybil presence reply-storm — **live-measured 2026-07-22, severity DOWNGRADED to local-cost** (extends O124/O16) |
| O132 | **`[DONE 2026-07-22, commit a2a98bd]`** Broadcast size cap — large text broadcasts are monolithic, never chunked (filed 2026-07-22, live-test finding) |
| O134 | **`[TODO/CODE+UI]`** Peers list — every gossip peer auto-persists as a contact → sybil/ephemeral pollution; needs collapse-unnamed + search (filed 2026-07-22, user + live finding) |
| O135 | **`[TODO/CODE+UI]`** Sybil/peer-spam resistance — make sybils *harmless*, not impossible (design, filed 2026-07-22 from user threat-model discussion) |
| O136 | **`[DONE 2026-07-22]`** Explicit friending/accept mechanism — prerequisite for every trust tier |
| O137 | **`[TODO/SIM+HW]`** Synthetic agent-driven test fleet — on-device local models running normal Rumor behavior (filed 2026-07-22, user idea; needs refining) |
| O138 | **`[DONE 2026-07-22 — 0.6.11]`** Kill the reflash+manual-unlock loop for dev/test |
| O139 | **`[TODO/CODE+DECISION]`** Tampered-Rumor-build resistance — the mesh IS the app-distribution channel post-SHTF (filed 2026-07-22, user question) |
| O140 | **`[TODO/CODE+DECISION]`** Unified "subscribe to a signed feed" primitive — one mechanism under many features (filed 2026-07-22, user insight) |
| O154 | **`[TODO/CODE+DECISION]`** No public/open-join *rooms*; keep ONE ungoverned text-only global *feed*, scaled by priority-weighting (NOT hard shards) — filed 2026-07-24, revised 2026-07-25 |
| O141 | **`[TODO/CODE]`** Pre-ship comment cleanup — strip conversational provenance from source (filed 2026-07-22) |
| O130 | **`[TODO/CODE]`** 2026-07-19 audit residue — §19/§20 grab-bag (doc-drift, dead code, efficiency) |
| O158 | **`[TODO/CODE]`** `TransferAssembler` never releases chunk BLOBs on the SUCCESS path — happy-path storage leak (audit #2) |
| O159 | **`[TODO/CODE]`** `TransferAssembler`/`TransferSender` have zero unit-test coverage (audit #12) |
| O160 | **`[TODO/CODE]`** O32 routed/flooded TTL split is inert — `decrementHops` kills routed DMs at the flood ceiling (audit #4) |
| O161 | **`[TODO/CODE]`** Bridged broadcasts never persisted — invisible in Feed, no Contact, gone on restart (audit #6) |
| O164 | **`[TODO/CODE]`** `MeshControllerHolder.NoOp` silently drops sends/relays during the bind race; UI reports success (audit #8) |
| O167 | **`[TODO/CODE]`** `LanTransportManager` `NetworkCallback` not pinned to its started `Network` (audit #20) |
| O171 | **`[TODO/CODE]`** `PersistenceCoordinator.recent` is an unsynchronized `LinkedHashSet` across coroutines (audit #13) |
| O173 | **`[TODO/CODE+SIM]`** `PersistencePlanner`/`MeshViewTracker` no sybil resistance on self-declared FREE capacity (audit #16; O127 family) |
| O176 | **`[TODO/CODE]`** `BreadcrumbCache.snapshot` unbounded in-memory map (audit #14; O120 half-closed) |
| O177 | **`[TODO/CODE]`** `MeshViewTracker.pruneStale()` dead code — 4th orphaned-prune instance (audit #23) |
| O185 | **`[TODO/CODE]`** Every inbound `ROOM_MESSAGE` triggers two blocking SQLite reads via `runBlocking` (audit #32) |
| O186 | **`[TODO/CODE]`** O36 "global feed is text-only — drop media before enqueue" rule has zero implementation (audit #33) |
| O187 | **`[TODO/CODE]`** Room lifecycle (Create/Invite/Action) has no wire transport / no `RoomRepository` — umbrella "Rooms unbuilt" row (audit #34) |
| O192 | **`[TODO/CODE]`** Key-retirement / compromise beacon — signed "stop trusting this key" broadcast; wires to O189 explode (privacy workshop) |
| O193 | **`[TODO/CODE+SIM]`** Relay-ephemeral DM caching — combinable eviction triggers (on-ACK / on-relay / after-X-time), default off; sim-model the message-loss first (privacy workshop) |
| O194 | **`[DECISION]`** Eclipse defense = trust-weighted view, NOT radio fingerprinting (commodity RF unreachable both directions); ties O135/O136 (privacy workshop) |
| O195 | **`[TODO/SIM]`** Traffic-analysis resistance — all-payload length padding + randomized relay delay + mix batch-shuffle; togglable, mode-suggested (privacy workshop) |
| O196 | **`[DECISION]`** Reject handheld cover traffic + adaptive/incremental fill (joules-not-bits; adaptive-signal-leak); cover traffic = infrastructure-node-only future work (privacy workshop) |
| O197 | **`[TODO/SIM]`** Limited onion routing for high-sensitivity DMs — source-routed through known contact-relays only (privacy workshop) |
| O198 | **`[TODO/SIM]`** Breadcrumb minimization — least per-node routing state holding delivery constant (info-vs-utility sim curve) (privacy workshop) |
| O202 | **`[TODO/CODE+SIM]`** DM delivery hardening under low node duty-cycle — DIRECT_ACK substrate shipped (2026-08-02) |
| O203 | **`[DECISION-NEEDED]`** Delivery-ACK follow-ups — "delivered ✓✓" UI + delivery-state Room column (schema bump); sender offer-suppression; relay on-ack eviction defaults |
| O204 | **`[DECISION, sim-DONE 2026-08-03]`** `hopsToLive` doesn't bound DM/broadcast reach (store-backfill re-offers as-received copies); routing's win is targeting not reach |
#### Tier 5 — bridge hardware track

| # | Item |
|---|------|
| O5 | **`[TODO/HW]`** Bridges — DM bridging** |
| O4 | **`[TODO/HW]`** Bridges — USB transport** |
| O8 | **`[TODO/HW]`** MeshCore v1/v2 firmware fallback** |
| O6 | **`[TODO/HW]`** Bridges — multi-channel selection UI** |
| O7 | **`[TODO/HW]`** Bridges — device picker** |
| O54 | **`[TODO/CODE]`** Transport plugin tier — non-phone infrastructure** |
| O142 | **`[TODO/HW]`** Pre-ship check: verify Meshtastic + MeshCore bridges still work end-to-end (filed 2026-07-22, user request) |
| O119 | **`[TODO/HW]`** Bridge robustness pack (2026-07-18 audit; O5a-adjacent) |
| O174 | **`[TODO/HW]`** `MeshtasticBleClient.drainFromRadio()` lost-wakeup race (audit #17) |
| O175 | **`[TODO/HW]`** `MeshCoreBridge.onFrame` routes `PUSH_CODE_RAW_DATA` through the channel-msg decoder on an 11-byte floor (audit #18) |
#### Tier 6 — UI surfaces and leaf features

| # | Item |
|---|------|
| O143 | **`[TODO/UI]`** Consolidated UI pass (folds O14/O18/O19/O21/O47/O68/O69/O70/O71/O97/O110/O113/O131/O133/O200 — full row bodies relocated verbatim to `docs/UI_BACKLOG.md`; filed 2026-07-22, user request) |
| O111 | **`[TODO/CODE+UI]`** Nickname advertisement — self-chosen nick offered to peers, receiver-accepted (filed 2026-07-17, user request) |
| O122 | **`[TODO/CODE]`** Doc-drift reconciliation sweep (2026-07-18 audit §4 — one pass, low risk, high reader value) |
| O74 | **`[TODO/CODE]`** Submit to non-Play Android stores** |
| O9 (rest) | **`[TODO/EMU]`** Instrumented + UI test coverage** |
| O101 | **`[TODO/CODE]`** First-class signed sites/pages |
| O103 | **`[TODO/CODE]`** Geotag / collaborative map-annotation plugin |
| O102 | **`[TODO/SIM]`** Neighbor-aware forward delegation (one copy per airtime) |
| O153 | **`[TODO/SIM]` `[DECISION]`** Endpoint-aware forwarding — gossip-at-edges / tree-multicast-on-backbone; large rooms should be OPEN — filed 2026-07-24 |
| O178 | **`[TODO/UI]`** "Radio duty cycle" scan-interval slider controls nothing (audit #21) |
| O179 | **`[TODO/UI]`** Battery-optimisation warning card is permanently dead (audit #22) |
| O181 | **`[TODO/UI]`** `ThreadViewModel` re-decrypts the entire thread on every new message (audit #25) |
| O183 | **`[TODO/UI]`** `FeedViewModel.markRead()` is unreachable (audit #27) |
| O184 | **`[TODO/UI]`** Feed "Relay" button has no in-flight/debounce guard (audit #28) |
| O188 | **`[TODO/CODE]`** README correctness pass — 4 user/contributor-facing misinfo items (killed-O41 "just needs UI", fabricated `elapsedMs`, stale device-ID + static-mode) (audit #37-40) |
#### Closed-row tombstones (one-line don't-rediscover facts; full records in docs/COMPLETED_GAPS.md)

| # | Item | Don't-rediscover fact |
|---|------|-----------------------|
| O42 | **`[CLOSED → G24]`** RBSR (Negentropy) replaces bloom offer/want | Shipped, field-verified: adaptive size-gate (`RBSR_MIN_SET_SIZE=3000`), bloom FP dropped to 0.01% + real MurmurHash3. RBSR only wins above ~5-6k messages (JSON frames); its unconditional win is *exactness* (no bloom false-positive message loss). Only deferred remnant: compact binary framing. |
| O52 | **`[SUBSUMED → O79]`** Group messaging via broadcast-with-key-DMs | O79 Rooms carries the closed-membership shape + MultiRecipientEnvelope primitive. All group work happens on O79. |
| O-rbsr-v2 | **`[DROPPED, merge]`** NIP-77 raw-id-sum RBSR fingerprint | **Forgeable in Rumor's threat model — never reintroduce as raw-id sums.** Rumor ids are sender-chosen (unlike Nostr content-hash ids), so the last id in a range steers the sum to any target. The additive-over-SHA256(domain-tagged-item) formula (kept) closes it. Reintroduce only as a hash-the-ids-first variant if Nostr interop becomes real. |
| O99 | **`[SUPERSEDED → O98]`** Channel-aware group creation | **Negotiated `connect()` IGNORES `setGroupOperatingFrequency`; only autonomous `createGroup` honors the channel** (field-verified on 3 phones). `createGroup` joins need manual WPS accept unless clients join by derived credentials as legacy STA. Host election from async discovery views fragments. All actionable detail folded into O98. |

Full tombstone records (with field findings) archived in `docs/COMPLETED_GAPS.md`.

### Design decisions recorded

- **Simulator/scenario-testing conventions → `docs/SIMULATOR_TESTING.md`.** Read it before writing any `:simulator` or `:core` scenario test. The load-bearing rules: (1) drive bad/adversarial input through the REAL transport (`SimTransport.exchange` / `node --sybil`), never assert on a bare in-memory object — that's the O129 vacuous-test trap; (2) `awaitUntil` the FINAL observable effect, absence needs a settle delay (O114/G41); (3) **every scenario carries negative controls** — a baseline (defense off → bad outcome visible), a discrimination control (defense lets the good case through, not a blackhole), and `assertThrows(AssertionError)`-wrapped WRONG assertions proving the checks have teeth (`TrustGraphSanityTest`); (4) `finally { scope.cancel() }` + modest node counts (live engines OOM under the parallel suite); prefer a pure `:core` scenario when the property is algorithmic. Sim proves the bound at scale; the headless `:node` proves the real path enforces it.

- **Prebuilt-vs-handrolled policy (assay 2026-07-13).** Rule of thumb: (1) crypto/mnemonics/secret-sharing → always an audited library, never hand-roll (O45 → kotlin-bip39; O46 → a SLIP-0039 lib; non-negotiable); (2) battle-tested algorithms with a reference impl → port the reference faithfully, don't reinvent (the RBSR XOR-fingerprint bug existed precisely because `Rbsr.kt` diverged from hoytech/negentropy); (3) app-specific glue (DRR scheduler, gossip engine, transports, planners) → build it, a dep is bloat. **Adopted this cycle:** `bcprov-jdk15on:1.70` → `bcprov-jdk18on:1.85` (jdk15on line discontinued 2021, CVEs fixed only in jdk18on; same packages, lightweight API unaffected) and real MurmurHash3 via `commons-codec` in `BloomFilterData` (the hand-rolled "murmur3" was a weak xor-mult mix with uncharacterized independence; swap is wire-affecting — bundled with the FP-rate change in the same pre-release window). **Considered and deferred, with reasons:** CBOR for RBSR frames (frames ride inside the JSON packet envelope, forcing base64 → real saving only ~25–35%, and the adaptive size gate already confines RBSR to where it wins even with JSON; revisit only if the whole GossipPacket wire moves to a binary envelope); Square Wire codegen for Meshtastic protobuf (right thing when S1 resumes — the hand-rolled codec already produced one silent field-number bug — but not worth touching field-verified code outside S1 work); libsodium/lazysodium for Ed25519↔X25519 (hand-rolled BigInteger Edwards→Montgomery map only ever touches *public* keys so the non-constant-time math leaks nothing secret; pulling native `.so`s for all ABIs isn't yet justified — adopt if libsodium arrives for other reasons); BC `HKDFBytesGenerator` (current `deriveAesKey` is a correct RFC-5869 extract of already-uniform DH output; swapping changes derived keys → breaks existing DMs for zero security gain). Version-churn note: don't bulk-bump test-framework deps for their own sake.

- **Latency NOT used for routing.** On BLE/Wi-Fi Direct, measured latency is mostly discovery timing. Ranking is by `bytesRelayed DESC, sessionCount DESC`. `latencyMs` stored for diagnostics only.
- **Relay path never sees blocklist.** Blocking is a display filter only — nodes relay everything regardless of blocklist state. This is intentional: if nodes stopped relaying blocked senders, a coordinated block could erase a person from the entire mesh, turning individual preferences into network-level censorship. Charlie needs Bob's emergency broadcast even if Alice blocked Bob. Your device's resources serve the mesh, not your social preferences. The residual CPU/storage cost of ingesting high-rate blocked senders is addressed separately by per-sender rate limiting (O16).
- **`BLOCKLIST_PUBLISH` is TRANSFER_SETUP, not INFRASTRUCTURE.** Full snapshots can exceed 16 KB; only incremental `BLOCKLIST_DIFF` stays at INFRASTRUCTURE tier.
- **Bridge traffic never re-relayed (today).** Re-signing a bridged message with the bridge's Rumor key would make every foreign-network message look like the bridge node originated it, and would let the bridge launder content it never received. Current invariant: BRIDGED → no relay, full stop. **Cost:** the bridge only reaches its direct Rumor peers; Charlie three hops away in the Rumor mesh never sees Bob's Meshtastic message. This is more restrictive than the design needs to be — see O17 (BRIDGE_VOUCHED trust level) for the path to extending bridged-content reach without re-introducing the laundering risk.
- **Bridges today are broadcast-only.** Both current bridge implementations (Meshtastic, MeshCore) bridge BROADCAST traffic only; DM bridging is unimplemented (O5). The DmEnvelope framework (G5/O5a) supports encrypted DM bridging via Architecture B (envelope passthrough) — the framework is in place; the per-bridge envelope code is what's missing. For BROADCASTs, Rumor hands the radio plaintext and the radio applies channel encryption with its own PSK; inbound, the radio gives us plaintext via `MeshPacket.decoded` (Meshtastic) or after firmware decryption (MeshCore). We never touch channel keys for broadcasts.
- **Encrypted bridging — full design in `docs/BRIDGING.md`; read it before designing O5.** Two architectures: **A (decrypt-and-re-encrypt)** — bridge is an endpoint and can read bridged DMs; **B (envelope passthrough)** — bridge sees ciphertext only, preserves real E2E. **Default recommendation: Architecture B for both bridges** (curves align — Meshtastic X25519, MeshCore Ed25519→X25519; only the AEAD wrapper differs); fall back to A only if a future bridge has no usable ECDH path. Both still require the per-message "via <bridge>" UI labelling (O47) — even passthrough has a bridge in the path. DM crypto routes through the pluggable **`DmEnvelope` framework (O5a, shipped — see G5)**: each bridge plugin owns its envelope, `composeDirect` consults the registry by recipient prefix. **Six non-negotiable O5a security constraints** (regression without them; full rationale in `docs/BRIDGING.md`):
  1. **Source-gated `selfAuthenticating`** — honored ONLY for `MessageSource.LOCAL_BRIDGE`; peer transport always verifies Ed25519 (same rule as `BRIDGE_UNSIGNED`).
  2. **Envelope id is derived (by recipient prefix), not asserted on the wire** — prevents downgrade attacks.
  3. **One envelope per prefix; registry append-only per plugin lifecycle** — `registerDmEnvelope` throws on collision; teardown unregisters atomically.
  4. **Bridged DMs inherit BRIDGED trust → never re-relayed.**
  5. **Replay protection is the envelope's responsibility** — a new envelope must state how.
  6. **Trust model unchanged from existing plugins** — same PluginCatalog gate; the framework doesn't widen the trust boundary.
