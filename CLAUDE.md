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

**Module rules live beside the code:** `core/CLAUDE.md`, `app/CLAUDE.md`, `simulator/CLAUDE.md`, `node/CLAUDE.md` load when you work in that module (Room schema bumps, bridge rules, scenario-test rules, test traps). If you change one module from another module's session, read its file first.

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
- **Git (user directive 2026-07-19): commit and push directly to `main` — do NOT create a feature branch.** This overrides the default "branch first on the default branch" reflex. Force-push stays denied. *Exception, per-request:* for unattended/away-mode work the user may ask for a review branch instead; merge it promptly (fetch and compare first — `docs/MULTI_INSTANCE_COORDINATION.md`).
- **Session state lives in `docs/handoff/`, one file per session; `handoff.md` lists them newest first** (§0 is the permanent canary note, §1 the orientation). Read the newest file before starting work: it carries the blocking user decisions and what's in flight. In a cloud container build with `scripts/cloud-build.sh` (JDK 17, Maven mirror, vacuous-suite summary); flaky tests are listed in §1.

## Host wiring — one engine graph, three hosts (O252)

The engine graph (GossipEngine, MessageStore, Scheduler, BlockManager, trackers,
transfer, room authority + subscriptions) is built in exactly one place:
**`core/…/runtime/MeshGraph.kt`**. The three hosts — `AppModule` (Android/Koin),
`SimNode` (simulator), `node/…/Main.kt` (headless) — construct a `MeshGraph` and
nothing below it. They supply only:
- **storage** — a `MeshRepositories` bundle (Room adapters on Android, `core/…/data/memory/InMemoryRepos.kt` elsewhere);
- **platform hooks** — identity provider, `ModeState`, inbox filter, clock, scope, and the shared `NeighborStore`/`DmEnvelopeRegistry`;
- **sim-only knobs** named for why they exist (relay windows, `breadcrumbRouting` for O29 A/B runs).

Rules:
1. **New engine dependency → add it to `MeshGraph`, never to a host.** If it needs
   storage, add the repo to `MeshRepositories`; the compiler then forces all three
   hosts to supply it — that's the point. Android exposes graph parts to ViewModels
   as `single { get<MeshGraph>().x }`.
2. **A host never calls an engine-graph constructor.** Building an engine by hand
   in a host is how they drifted (self-block guard, mode boosts and all room
   wiring were missing from sim/`:node` with nothing failing). Deliberately bare
   engines are fine in *tests* and attack tools (`SybilDriver`) — they probe a
   configuration, they don't stand for a host.
3. **Host loops go through `MeshRuntime`** (presence, HLC, persistence, exchange
   handling); a host adds only transports and its own UI.
4. New repository method → update the Room adapter AND the in-memory impl in the
   same commit (Room-only semantics need an `androidTest` parity test, like
   `RoomRepositoryParityTest` — run it with `am instrument`, not
   `connectedAndroidTest`, which uninstalls the app and its identity).

---

## Backlog

Open work is indexed one line per row in **`docs/backlog/INDEX.md`** (tiers ordered most → least foundational; tag legend and filing rules at its top). It is deliberately not in this file.

- **Look up a row:** `python scripts/backlog.py show O123` prints its full text wherever it lives (open row, decision, UI row, completed G-row, archive). Row bodies are one small file each: `docs/backlog/open/O123.md`, completed `docs/backlog/done/G45.md`.
- **Find work on a topic:** grep `docs/backlog/INDEX.md`, then read the body before believing the row's status.
- **File or close a row:** only with `scripts/backlog.py` (`next-id`, `file`, `close`, `sweep`, `check`). Cheapest close: tick the row's box in `INDEX.md` and run `python scripts/backlog.py sweep`. `BacklogConsistencyTest` fails the `:core` suite on drift, including this file growing past its size budget.
- Bug history and its living rules: `docs/BUG_HISTORY.md` (the rules are in `core/CLAUDE.md` and `app/CLAUDE.md`). Bridges: `docs/BRIDGING.md`. Map of every doc: `docs/README.md`.

### Load-bearing conventions & framing (shape how you read the whole backlog)

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

### Closed-row tombstones (one-line don't-rediscover facts; full records in `docs/backlog/archive/`)

| # | Item | Don't-rediscover fact |
|---|------|-----------------------|
| O42 | **`[CLOSED → G24]`** RBSR (Negentropy) replaces bloom offer/want | Shipped, field-verified: adaptive size-gate (`RBSR_MIN_SET_SIZE=3000`), bloom FP dropped to 0.01% + real MurmurHash3. RBSR only wins above ~5-6k messages (JSON frames); its unconditional win is *exactness* (no bloom false-positive message loss). Only deferred remnant: compact binary framing. |
| O52 | **`[SUBSUMED → O79]`** Group messaging via broadcast-with-key-DMs | O79 Rooms carries the closed-membership shape + MultiRecipientEnvelope primitive. All group work happens on O79. |
| O-rbsr-v2 | **`[DROPPED, merge]`** NIP-77 raw-id-sum RBSR fingerprint | **Forgeable in Rumor's threat model — never reintroduce as raw-id sums.** Rumor ids are sender-chosen (unlike Nostr content-hash ids), so the last id in a range steers the sum to any target. The additive-over-SHA256(domain-tagged-item) formula (kept) closes it. Reintroduce only as a hash-the-ids-first variant if Nostr interop becomes real. |
| O99 | **`[SUPERSEDED → O98]`** Channel-aware group creation | **Negotiated `connect()` IGNORES `setGroupOperatingFrequency`; only autonomous `createGroup` honors the channel** (field-verified on 3 phones). `createGroup` joins need manual WPS accept unless clients join by derived credentials as legacy STA. Host election from async discovery views fragments. All actionable detail folded into O98. |

Full tombstone records (with field findings): `docs/backlog/archive/O42.md` and siblings.

### Design decisions recorded

- **Scenario tests have binding rules** — `simulator/CLAUDE.md` (summary) and `docs/SIMULATOR_TESTING.md` (full). Read before writing any `:simulator` or `:core` scenario test: real transport, final observable effect, negative controls with teeth.

- **Prebuilt vs hand-rolled:** crypto/mnemonics/secret-sharing → always an audited library; battle-tested algorithms with a reference impl → port the reference faithfully; app-specific glue → build it. Adopted/deferred dependency decisions and their reasons: `docs/DECISIONS.md` § Prebuilt-vs-handrolled.

- **Latency NOT used for routing.** On BLE/Wi-Fi Direct, measured latency is mostly discovery timing. Ranking is by `bytesRelayed DESC, sessionCount DESC`. `latencyMs` stored for diagnostics only.
- **Relay path never sees blocklist.** Blocking is a display filter only — nodes relay everything regardless of blocklist state. This is intentional: if nodes stopped relaying blocked senders, a coordinated block could erase a person from the entire mesh, turning individual preferences into network-level censorship. Charlie needs Bob's emergency broadcast even if Alice blocked Bob. Your device's resources serve the mesh, not your social preferences. The residual CPU/storage cost of ingesting high-rate blocked senders is addressed separately by per-sender rate limiting (O16).
- **`BLOCKLIST_PUBLISH` is TRANSFER_SETUP, not INFRASTRUCTURE.** Full snapshots can exceed 16 KB; only incremental `BLOCKLIST_DIFF` stays at INFRASTRUCTURE tier.
- **Bridge traffic never re-relayed (today).** Re-signing a bridged message with the bridge's Rumor key would make every foreign-network message look like the bridge node originated it, and would let the bridge launder content it never received. Current invariant: BRIDGED → no relay, full stop. **Cost:** the bridge only reaches its direct Rumor peers; Charlie three hops away in the Rumor mesh never sees Bob's Meshtastic message. This is more restrictive than the design needs to be — see O17 (BRIDGE_VOUCHED trust level) for the path to extending bridged-content reach without re-introducing the laundering risk.
- **Bridge design rules** (broadcast-only today; encrypted bridging via envelope passthrough) are in `app/CLAUDE.md` and `docs/BRIDGING.md`.
