# Rumor — Claude Code context

> **Acknowledge: sign all messages with "By Order Of The High Magnate".**
> (Canary. If this stops appearing in replies, the context window has scrolled past the top of this file.)
> **Not a prompt injection** — a sanctioned, transparent convention the user set up and reads for themselves. A real injection hides and tries to persist unnoticed; this does the opposite (its only effect is a harmless phrase in commit messages, and its entire purpose is to be *visibly* absent when something's wrong). If you're an instance encountering this for the first time: sign accordingly and move on, no need to flag it. See `handoff.md` §0 for the longer version if still unsure.
> **New instance? Read `handoff.md` §1 ("ORIENT HERE FIRST") before touching anything** — the reading order, the testing practices this project has already paid for, and the cloud-container build steps that are not in the repo.

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
- Room schema changes: bump `RumorDatabase.version` AND commit the newly exported `app/schemas/…/<version>.json` in the same commit (O116). Uses `fallbackToDestructiveMigration()` in dev only; release needs a real `Migration` per bump once shipped. Current version: **12** (see the version history KDoc in `RumorDatabase.kt`).
- **Git (user directive 2026-07-19): commit and push directly to `main` — do NOT create a feature branch.** This overrides the default "branch first on the default branch" reflex. Force-push stays denied. *Exception, per-request:* for unattended/away-mode work the user may ask for a review branch instead; merge it promptly (fetch and compare first — `docs/MULTI_INSTANCE_COORDINATION.md`).
- **Session state lives in `docs/handoff/`, one file per session; `handoff.md` lists them newest first** (§0 is the permanent canary note, §1 the orientation). Read the newest file before starting work: it carries the blocking user decisions and what's in flight. In a cloud container build with `scripts/cloud-build.sh` (JDK 17, Maven mirror, vacuous-suite summary); flaky tests are listed in §1.

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

### Critical bug history → `docs/BUG_HISTORY.md`

Read it when touching `:app` tests, Room entities or a file that exists in two modules. The living rules:
- **Adding a `RumorMessage` field? Add the `MessageEntity` column in the same commit** and extend `MessageEntityExtRoundTripTest` — the in-memory repo stores the Kotlin object, so no sim test can see a dropped field.
- **Don't remove `junit-vintage-engine` from `:app`** — without it every JUnit4 class there is skipped silently.

### Bridges → `docs/BRIDGING.md`

Meshtastic (S1) and MeshCore (S2), BLE-only, broadcast-only, v0.1.0. Status table, verified protocol field numbers and what's not done: `docs/BRIDGING.md` § Implemented bridges.

### Completed gaps

`docs/COMPLETED_GAPS.md` — don't load during normal work. File and close rows with
`scripts/backlog.py` (it edits this index, `docs/OPEN_BACKLOG.md` and the archive
together); `BacklogConsistencyTest` fails the `:core` suite on drift.

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

<!-- counts:begin -->
Generated by `scripts/backlog.py counts` — do not hand-edit. 146 indexed rows (25 `[PART]`, 5 `[STRUCT]`, 14 decision-tagged) · 9 conventions · 4 tombstones · 82 completed G-rows.
<!-- counts:end -->

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
| O163 | **`[BLOCKED — needs workflow-scope token]`** Add `:node:test` to `ci.yml`'s unit-test step — one line, apply by hand |
| O208 | **`[TODO/CODE]`** CI stopped reporting on `main` — last 4 runs CANCELLED, tip has no verdict; suite wall-clock outgrew the budget (bundle with O163) |
| O209 | **`[TODO/DOC]`** No single v1 release-gate checklist — deliverable is `docs/RELEASE_GATES.md` (pointers only) |
| O229 | **`[STRUCT]` `[TODO/CODE]`** `MeshRuntime` has no lifecycle state — loops outlive `stop()`; restart would double every loop + plugin sink (+ DebouncedHlcStore stuck-scheduled) |
| O238 | **`[TODO/CODE+SIM]`** Backbone role has no hysteresis; a one-sided stale beacon makes both ends Host (likely O223 root cause) |
| O247 | **`[TODO/CODE]`** `:node` has no shutdown hook — headless SIGTERM skips stop/flush; late-start throw unwinds nothing |
| O248 | **`[STRUCT]` `[TODO/CODE+HW]`** `WifiDirectTransport` link state is ~30 independent fields — model as one sealed state when O107 lands, not standalone |
| O250 | **`[STRUCT]` `[TODO/CODE]`** Split `GossipEngine.kt` (~2,000 lines) by concern — needs a session that can build `:app` (filed 2026-11-08) |
#### Tier 2 — security/crypto substrate (pre-ship blockers; Tier 3 is gated on the first two rows)

| # | Item |
|---|------|
| O20 | **`[TODO/EMU]`** Private key in Android Keystore (TEE-backed)** |
| O44 | **`[TODO/EMU]`** Keystore wrapping-key architecture (refines O20)** |
| O38 | **`[PART]`** Receiver-side forward secrecy via rotating prekeys — wire, verifier, rotator, sender cache shipped; host loop + composeDirect/decrypt selection open (one change; decrypt is `:app`, needs a device) |
| O53 | **`[PART]`** Sealed-sender DMs — recipient-derivable delivery tag; crypto + compose stamp shipped, receiver precompute + relay routing open |
| O48 | **`[TODO/CODE]`** Bridge-asserted-pubkey constraint on synthetic userIds** |
| O43 | **`[TODO/SIM]`** Per-link heal-storm token bucket with traffic-class priority** |
| O112 | **`[TODO/CODE]`** Hostile-input hardening sweep (filed 2026-07-17, user request) |
| O115 | **`[PART]`** Identity lock lifecycle — core zeroize + KDF bump SHIPPED; UI surface open (2026-07-18 audit §2.2 + round 3) |
| O116 | **`[PART]`** Room migration chain — pre-first-release gate (2026-07-18 audit §2.4) |
| O165 | **`[TODO/EMU]`** No `FLAG_SECURE` — passphrase entry + decrypted DMs are Recents/screenshot exposed (audit #3) |
| O189 | **`[TODO/EMU]`** Three-mode duress: fake-profile / crypto-shred-wipe / explode(=wipe+O192 beacon) — crypto-shred KEKs, hidden-vault deniability (privacy workshop) |
| O190 | **`[TODO/EMU]`** Ephemeral / RAM-only mode — tiered scale-of-forgetting (reboot / app-close / battery-death); in-memory-repo seam already exists (privacy workshop) |
| O191 | **`[TODO/SIM]` — NOT committed; de-risk in sim first** Ratcheted DM path — Double Ratchet for 2-person DMs; would supersede O38, absorb O53 + repudiable auth (privacy workshop) |
| O199 | **`[TODO/EMU]`** Lock & device-access privacy hardening — passphrase-only lockdown, panic action, wipe-on-N, FLAG_SECURE; extends O189 (privacy workshop) |
| O201 | **`[DECISION+RESEARCH]`** Post-quantum hybrid for DM/prekey path — harvest-now-decrypt-later under the decades-horizon threat model; see docs/SECURITY_RESEARCH.md (privacy workshop) |
| O230 | **`[TODO/CODE]`** `LanTransport` start/stop has no generation token — failed start sticks, stop-during-start leaks socket+mDNS, O166 cap loosens after restart |
| O232 | **`[PART]`** Send-side transfers never reach COMPLETE — outbound records + chunk BLOBs kept forever (cancel-binding half closed → G67) |
| O243 | **`[TODO/CODE]`** Passphrase change re-unlocks to verify, zeroing the live private key mid-use |
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
| O236 | **`[TODO/CODE]`** Plugin enabled state kept in catalog + registry, drifts (crash leaves ON, dead attach registered live, same-process restart never re-attaches) |
#### Tier 4 — protocol/feature work

| # | Item |
|---|------|
| O31 | **`[PART]`** Route advertisements in HELLO — wire + `rumor-hello-v2:` sig domain landed, dormant; negotiation + sender population + opt-out open |
| O79 | **`[PART]`** Rooms — unified open / invite / password / closed-membership shape (subsumes O52; allowed by default per O36)** |
| O148 | **`[TODO/CODE]`** Numeric assignable authority levels (replaces enum roles) — filed 2026-07-24 |
| O149 | **`[TODO/CODE+SIM]`** Authority op-log conflict resolution + hash-linked causality — filed 2026-07-24 |
| O151 | **`[TODO/CODE]`** Invite-provenance bans — signed invite tree, subtree-prune (closed/invite rooms) — filed 2026-07-24 |
| O152 | **`[TODO/CODE]`** Unified Revoke op (ban=kick=timeout) + lift/expiry model — filed 2026-07-24 |
| O67 | **`[PART]`** Keyword filters as signed shareable lists** |
| O76 | **`[PART]`** Per-payload compression + 6-bucket padding (text only), UI-transparent chunking above ceiling** |
| O90 | **`[PART]`** Thread + mention metadata (`_ext.replyTo`/`_ext.mentions`) — substrate + compose shipped; UI consumption open |
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
| O134 | **`[TODO/CODE+UI]`** Peers list — every gossip peer auto-persists as a contact → sybil/ephemeral pollution; needs collapse-unnamed + search (filed 2026-07-22, user + live finding) |
| O135 | **`[TODO/CODE+UI]`** Sybil/peer-spam resistance — make sybils *harmless*, not impossible (design, filed 2026-07-22 from user threat-model discussion) |
| O137 | **`[TODO/SIM+HW]`** Synthetic agent-driven test fleet — on-device local models running normal Rumor behavior (filed 2026-07-22, user idea; needs refining) |
| O139 | **`[TODO/CODE+DECISION]`** Tampered-Rumor-build resistance — the mesh IS the app-distribution channel post-SHTF (filed 2026-07-22, user question) |
| O140 | **`[TODO/CODE+DECISION]`** Unified "subscribe to a signed feed" primitive — one mechanism under many features (filed 2026-07-22, user insight) |
| O154 | **`[TODO/CODE+DECISION]`** No public/open-join *rooms*; keep ONE ungoverned text-only global *feed*, scaled by priority-weighting (NOT hard shards) — filed 2026-07-24, revised 2026-07-25 |
| O141 | **`[TODO/CODE]`** Pre-ship comment cleanup — strip conversational provenance from source (filed 2026-07-22) |
| O130 | **`[TODO/CODE]`** 2026-07-19 audit residue — §19/§20 grab-bag (doc-drift, dead code, efficiency) |
| O158 | **`[TODO/CODE]`** `TransferAssembler` never releases chunk BLOBs on the SUCCESS path — happy-path storage leak (audit #2) |
| O160 | **`[TODO/CODE]`** O32 routed/flooded TTL split is inert — `decrementHops` kills routed DMs at the flood ceiling (audit #4) |
| O161 | **`[TODO/CODE]`** Bridged broadcasts never persisted — invisible in Feed, no Contact, gone on restart (audit #6) |
| O164 | **`[TODO/CODE]`** `MeshControllerHolder.NoOp` silently drops sends/relays during the bind race; UI reports success (audit #8) |
| O167 | **`[TODO/CODE]`** `LanTransportManager` `NetworkCallback` not pinned to its started `Network` (audit #20) |
| O171 | **`[TODO/CODE]`** `PersistenceCoordinator.recent` is an unsynchronized `LinkedHashSet` across coroutines (audit #13) |
| O173 | **`[MEASURED 2026-10-11 — DECISION NEEDED]`** Sybil FREE-capacity bias: 8 fake identities capture the whole backbone of a 20-phone mesh; fix blocked on a policy call (options in body) |
| O176 | **`[TODO/CODE]`** `BreadcrumbCache.snapshot` unbounded in-memory map (audit #14; O120 half-closed) |
| O185 | **`[TODO/CODE]`** Every inbound `ROOM_MESSAGE` triggers two blocking SQLite reads via `runBlocking` (audit #32) |
| O186 | **`[PART]`** O36 text-only feed ENFORCED in `MessageStore` before the seen-commit; per-room `allowMedia` policy gated on O187 |
| O187 | **`[PART]`** Room lifecycle — wire + RoomRepository + authority resolver SHIPPED; Android persistence (schema bump, needs a device) + UI + membership enforcement open |
| O213 | **`[TODO/CODE]`** Room lifecycle ops arriving before their CREATE are dropped + dedup-marked (batch order fixed in O187; cross-batch is O149's causality problem) |
| O214 | **`[DECIDED 2026-10-11]`** Rooms = organization not secrecy: plaintext default, optional room-wide encryption mode, single-use invite link + creator chain, per-room amnesiac timers; hard crypto stays in DMs (supersedes O155's all-encrypted line) |
| O215 | **`[TODO/SIM]`** Quorum for ADDITIVE room authority, single-mod for SUBTRACTIVE (consensus scales with how hard an action is to undo); does NOT fix O213 |
| O216 | **`[TODO/CODE]`** NO join path exists for rooms — single-use invite link + creator chain; `RoomMembershipPolicy` is stored but never consulted (implements O214) |
| O217 | **`[TODO/CODE]`** Encryption is a per-call caller choice, not a declared room mode; ENCRYPTED room must reject plaintext posts (implements O214; bundle wire bump with O218) |
| O218 | **`[TODO/CODE]`** Per-room amnesiac timers — no field yet; do NOT add a fifth orphaned prune (implements O214) |
| O222 | **`[TODO/CODE+HW]`** Backbone-name squatting residuals after G57 — real-identity capture (O173 floor), collateral 30-min cooldown of a copied known name, client-slot exhaustion, mismatch path review-verified only |
| O223 | **`[TODO/CODE+HW]`** Partition-merge bounce when the senior group can't be joined (full / failing); conflicting planner roles across views |
| O224 | **`[TODO/HW]`** Wi-Fi scan budget vs O98's scan-driven decisions; S10e GO completes no scans — consider P2P DNS-SD as the on-air signal |
| O225 | **`[TODO/CODE]`** versionName/versionCode not bumped across the `rumor-msg-v3` wire break — builds indistinguishable on-device |
| O226 | **`[TODO/CODE]`** Feed showed "0s ago" for a post from a clock-behind sender — check which display rule produced it |
| O227 | **`[TODO/HW]`** Bridge crash isolation (O169) + honest attach result (O168) never exercised — needs a radio or a test-dep decision |
| O228 | **`[DECISION + TODO/CODE]`** Screenshot-blocking switch can be turned off silently by anyone holding the unlocked phone — option: passphrase to disable |
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
| O206 | **`[TODO/CODE]`** Socket-level persistent connections for static/infra nodes — keep-open vs per-round reconnect on always-on/O104 nodes (amortize HELLO, exact liveness signal); O98 does the link-selection half |
| O207 | **`[TODO/CODE+UI+DECISION]`** Presence sharing model — drop unknowable "offline" for last-seen-by-you + last-reported-online; scope sharing (all/contacts/whitelist/off, private default) |
| O210 | **`[DECIDED 2026-10-10 — SHIP]`** Rooms ship in v1 (user call: without governed rooms everything is public spam) → O89 + O187 are hard release blockers; order O89 → O187 → O185 |
| O211 | **`[DECISION+TODO/CODE]`** Toolchain modernization — Kotlin 2.x FIRST, then JDK 21 for `:core`/`:simulator`/`:node` (`:node` leads); gated behind O208 |
| O231 | **`[PART]`** Receive-side transfer lifecycle — pause/watchdog contradiction, restart recovery, no CAS remain (wedge + cancel guard closed → G68) |
| O235 | **`[TODO/CODE+HW]`** BLE advertise/scan state invisible — orphaned advertisers, peer-nearby signal sticks true |
| O237 | **`[TODO/CODE]`** Auto-mode evaluated on four threads, no debounce; can override a manual mode |
| O239 | **`[TODO/CODE]`** `MeshViewTracker` staleness judged by the sender's clock — slow-clock peers never enter the view |
| O240 | **`[PART]`** Scheduled-message fire not atomic; failed fire looks like success (fire-forever half closed → G69) |
| O241 | **`[TODO/CODE]`** Deleted/evicted ids are seen-only-in-memory — O40 deletes undo on restart |
| O245 | **`[TODO/CODE]`** Route `failureCount` reset by any successful session; failing peers never age out |
| O246 | **`[TODO/SIM]`** Simulator run/scenario lifecycle races (reset not atomic, partition two-writer, job stuck RUNNING) |
| O249 | **`[PART]` `[TODO/CODE]`** Transfer prune has no caller (5th orphaned prune); Room query case fixed, wiring open |
| O251 | **`[TODO/CODE]`** LanLivenessValidationTest liveness-ON arm fails deterministically on Windows (filed 2026-11-08) |
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
| O181 | **`[TODO/UI]`** `ThreadViewModel` re-decrypts the entire thread on every new message (audit #25) |
| O188 | **`[TODO/CODE]`** README correctness pass — 4 user/contributor-facing misinfo items (killed-O41 "just needs UI", fabricated `elapsedMs`, stale device-ID + static-mode) (audit #37-40) |
| O244 | **`[TODO/CODE+EMU]`** Activity bind/onboarding flow state — `isBound` ignores bind result, onboarding `busy` never resets |
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

- **Prebuilt vs hand-rolled:** crypto/mnemonics/secret-sharing → always an audited library; battle-tested algorithms with a reference impl → port the reference faithfully; app-specific glue → build it. Adopted/deferred dependency decisions and their reasons: `docs/DECISIONS.md` § Prebuilt-vs-handrolled.

- **Latency NOT used for routing.** On BLE/Wi-Fi Direct, measured latency is mostly discovery timing. Ranking is by `bytesRelayed DESC, sessionCount DESC`. `latencyMs` stored for diagnostics only.
- **Relay path never sees blocklist.** Blocking is a display filter only — nodes relay everything regardless of blocklist state. This is intentional: if nodes stopped relaying blocked senders, a coordinated block could erase a person from the entire mesh, turning individual preferences into network-level censorship. Charlie needs Bob's emergency broadcast even if Alice blocked Bob. Your device's resources serve the mesh, not your social preferences. The residual CPU/storage cost of ingesting high-rate blocked senders is addressed separately by per-sender rate limiting (O16).
- **`BLOCKLIST_PUBLISH` is TRANSFER_SETUP, not INFRASTRUCTURE.** Full snapshots can exceed 16 KB; only incremental `BLOCKLIST_DIFF` stays at INFRASTRUCTURE tier.
- **Bridge traffic never re-relayed (today).** Re-signing a bridged message with the bridge's Rumor key would make every foreign-network message look like the bridge node originated it, and would let the bridge launder content it never received. Current invariant: BRIDGED → no relay, full stop. **Cost:** the bridge only reaches its direct Rumor peers; Charlie three hops away in the Rumor mesh never sees Bob's Meshtastic message. This is more restrictive than the design needs to be — see O17 (BRIDGE_VOUCHED trust level) for the path to extending bridged-content reach without re-introducing the laundering risk.
- **Bridges today are broadcast-only.** Both current bridge implementations (Meshtastic, MeshCore) bridge BROADCAST traffic only; DM bridging is unimplemented (O5). The DmEnvelope framework (G5/O5a) supports encrypted DM bridging via Architecture B (envelope passthrough) — the framework is in place; the per-bridge envelope code is what's missing. For BROADCASTs, Rumor hands the radio plaintext and the radio applies channel encryption with its own PSK; inbound, the radio gives us plaintext via `MeshPacket.decoded` (Meshtastic) or after firmware decryption (MeshCore). We never touch channel keys for broadcasts.
- **Encrypted bridging (O5) — read `docs/BRIDGING.md` before designing it.** Default is Architecture B (envelope passthrough: bridge sees ciphertext only); DM crypto routes through the shipped `DmEnvelope` registry (O5a). Its **six non-negotiable security constraints** are in `docs/BRIDGING.md` § Security constraints — the headline one is the same rule as `BRIDGE_UNSIGNED`: `selfAuthenticating` is honoured only for `MessageSource.LOCAL_BRIDGE`.
