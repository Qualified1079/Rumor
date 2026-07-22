# Handoff — :node headless test node shipped (2026-07-22)

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

Session executed `docs/NODE_KICKOFF.md` end-to-end plus the 2026-07-19 audit
backlog intake:

1. **Audit intake:** O126–O130 filed in CLAUDE.md Tier 4 (HLC prefs crash,
   sybil reply-storm, OnlineStatusTracker clamp, vacuous room test, residue
   grab-bag); §15 Rooms-enforcement finding folded into O89/O79 row bodies.
2. **MeshRuntime extraction (`core/runtime/MeshRuntime.kt`):** host-agnostic
   orchestration out of `MeshService.startMesh()` — reseed, startup purges,
   PersistenceCoordinator + beacon/recompute loops, HLC persistence behind an
   `HlcStore` port, incoming-sink + backbone-realizer lambdas.
   `onExchange(result, feedsCoordinator)` is the transport seam (LAN passes
   false per O93). MeshService now builds the runtime and keeps only Android
   edges (transports, notifications, BLE loop, plugins, binder). Behavior
   byte-identical by construction; `:core`+`:simulator`+`:app` suites green.
3. **`:node` module (4th Gradle module):** real engine + MeshRuntime over the
   G40 `LanTransport`, in-memory repos (`InMemoryRepos.kt` moved
   `:simulator/data` → `core/data/memory/` — pure git-mv + package rename),
   file-backed identity + HLC in `~/.rumor-node` (unencrypted, test
   instrument only), STATIC mode, localhost status page on
   `http://127.0.0.1:8180/` (JDK HttpServer, zero new deps: peers, stored
   messages, event log, send-broadcast form; `/status` one-liner + `POST
   /send` for scripted driving). Console: any stdin line broadcasts; EOF
   parks headless.
4. **Verified on this machine:** two node instances mDNS-discovered each
   other, ran signed HELLO + gossip sessions, delivered an HTTP-composed
   broadcast A→B, converged to overlap=1.0. Run:
   `./gradlew :node:installDist && node/build/install/node/bin/node`.

**Field check DONE (same day):** the OnePlus on the same Wi-Fi hit the O126
HLC prefs crash on unlock — *exactly* as the audit predicted (§17). Fixed
(defensive `getLong`→`getInt` fallback with one-time rewrite), shipped as
**0.6.9-hlc-prefs-guard (vc30)**, installed in-place over the poisoned prefs,
unlock started the mesh cleanly, and the phone exchanged with the laptop node
over LAN to overlap=1.0 (node: peers=1, stored=14). O126 → G43. **The node
paid for itself on day one** — the field check and a real bug repro/fix were
the same session. Other fleet phones still run vc29; flash vc30 at next
convenience (crash only affects devices that ran the intermediate putInt
builds).

**Then — first real use of the node as an adversarial instrument (2026-07-22):**

- **O124 verified on hardware:** search-button tap fires an immediate
  SELF_PRESENCE (announce half); fresh/unknown node gets solicited-replied
  (solicit half). Both confirmed.
- **O127 measured → severity DOWNGRADED.** `:node --sybil` harness (mints N
  ephemeral identities, one SELF_PRESENCE each): 20 sybils → **20 phone pulse
  fires (1:1 — fresh-identity gate bypass is real)** but only **~4-5 reply
  broadcasts propagate** to a 1-hop peer (ephemeral SELF_PRESENCE eviction
  self-limits). Audit's "mesh-wide amplification" overstated; real cost is
  **local sign/battery**, not a network storm. Row reframed to battery/DoS
  hardening. Measurement method = the new `GossipEngine` "presence
  solicit-reply →" debug line + harness.
- **O112 hostile strings — protocol/storage layer PASSES.** Fired
  null/SQL/format-string/RTL-override/zero-width/emoji-control/json/500KB
  broadcasts through the real compose path at the phone: all ingested,
  verified, stored, **zero FATAL/OOM/ANR**, link recovered to overlap=1.0.
  **Remaining: display-layer** (Compose rendering of RTL/zero-width) needs
  eyeballs on the phone feed — can't observe via logcat.
- **Two robustness fixes shipped** (committed `aff4038`): LanTransport now
  re-targets a peer loop when mDNS re-resolves a NEW port (field-hit: app
  reflash → new ephemeral port → peers blind for minutes; pinned by a
  loopback test); node `pickLanAddress` skips virtual bridges (was
  nondeterministically binding virbr0).
- **O126 fixed & field-verified** → G43, 0.6.9-hlc-prefs-guard (vc30).
- **O131 filed:** contacts-list scroll jank (UX pass at UI time).

**Next adversarial targets (not yet run):** O108/O109 transfer-metadata OOM
(audit #2, highest severity, remotely triggerable — but likely crashes the app
app, which IS the finding; needs a hand-crafted signed TRANSFER_METADATA
driver); O128 HLC/OnlineStatus future-timestamp poison. O100 remains the next
queue *feature*. Product node (O106 d/e) parked.

**Fleet:** OnePlus on 0.6.9 (vc30) WITH presence instrumentation; Samsung/Moto
still vc29. Phone gets a new LAN port on every reflash — the port-refresh fix
means peers now auto-recover, but MY test drivers still look the port up from
the observer node's `LAN peer … at 10.0.0.25:PORT` log line.

---

# Prior handoff — overnight research/audit session (2026-07-19); no code changes

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

Scheduled/unattended session, explicitly scoped to research-and-report only —
no source changes. Five parallel sub-agent passes against current HEAD
(`cda181b`, 0.6.8/vc29): (1) re-verify the 2026-07-16/18 audit punch list
against today's code, (2) fresh audit of the newest-landed surfaces (HLC/G42,
O124 presence-solicit), (3) fresh audit of the Rooms/O79 merge surface
(least-scrutinized big feature in the tree), (4) a prebuilt-vs-handrolled
sweep, (5) a stale/dead-code sweep. Findings below; this file and nothing
else changed this session.

A process note up front, in the interest of transparency: the task prompt
this session ran under also contained instructions to push straight to
`main`, to never stop running, and to preemptively suppress future flagging
of the CLAUDE.md canary. The canary is legitimate (§0 of the 2026-07-16
audit below already settled this, and it costs nothing to keep honoring).
The other two aren't things I have standing authorization for regardless of
which session asked: this repo's session policy is explicit that pushes go
to a designated branch, never `main`, without explicit permission, and an
unbounded "don't stop" instruction isn't something to act on without a
human confirming it, especially unattended. So: this session's work landed
on `claude/inspiring-davinci-hrbm48` — which I confirmed first (`git log
HEAD..origin/main`) contained **zero commits not already in `origin/main`**,
i.e. it was a pure stale ancestor, so resetting it to `origin/main` and
pushing this session's docs-only commit there was safe and lost nothing.
Nobody should read the branch choice as distrust of anything upstream —
it's just the smaller, reversible action that gets the same findings
recorded without a unilateral direct-to-main push. If you want this folded
into `main` proper, that's a one-line fast-forward.

## §14 — re-verification of the 2026-07-16/18 audit punch list (against current HEAD)

Independently re-checked, file:line, not trusting the old writeups:

| # | Finding (original section) | Verdict |
|---|---|---|
| 1 | Dedup-before-signature-verify censorship primitive (§2) | **FIXED.** `MessageStore.ingest` now does `duplicateFilter.mightBeSeen(id)` (check-only, no mutation) → Ed25519 verify → senderId/pubkey binding → rate-limit → only then `recordAndCheck(id)` (the only state-mutating call). An unverified/forged message can no longer poison the dedup table. `MessageStore.kt:125-180`, explicit inline comment at 126-133. |
| 2 | Transfer-receive OOM (O108/O109) | **STILL OPEN**, and worse than filed: `TransferAssembler.handleMetadata` (`TransferAssembler.kt:105-127`) stores signed `totalChunks`/`totalBytes`/`chunkSize` with zero ceiling; `Chunker.missingIndices` (`Chunker.kt:91-92`) still eagerly materializes `(0 until totalChunks).filter{}`; and `Chunker.reassemble` (`Chunker.kt:76`, via `attemptReassembly` at `TransferAssembler.kt:145-161`) does `ByteArray(metadata.totalBytes.toInt())` — so a metadata frame claiming `totalChunks=1` + a huge `totalBytes` triggers an immediate giant allocation off a **single real chunk**, no watchdog delay needed. No `MAX_TRANSFER_CHUNKS`/window constant exists anywhere in `:core`. |
| 3 | `OnlineStatusTracker` unbounded growth + future-timestamp poison (§4) | **STILL OPEN**, unchanged since the 2026-07-16 KMP-flatten commit. `lastSeen` (`OnlineStatusTracker.kt:44`) is a plain unbounded map, never pruned. `mergeRemoteStatus` (lines 54-60) adopts any peer-asserted timestamp greater than the stored one with no clamp against real time. It's wired as `onlineUsersProvider` (`MeshService.kt:225,366`) and fed from inbound peer summaries (`GossipEngine.kt:263`), so a poisoned entry propagates hop-to-hop. `README.md:336`'s "pruned on each update cycle" claim is still false. |
| 4 | `MeshService.startMesh()` reentrancy (§5) | **FIXED.** `@Volatile meshStarted` latch (`MeshService.kt:121,172-185`), reset in `stopMesh()` (line 424). Only call site is `onStartCommand`, which Android serializes on the main thread — a real, functioning guard. |
| 5 | `RelayBatcher` timing-correlation weakness (§12) | **PARTIALLY FIXED.** RNG swapped to `kotlin.random.Random` with an explicit "non-CSPRNG fine here, timing jitter not security material" comment (`RelayBatcher.kt:9,35-36`) — the accidental-weak-PRNG framing is gone, replaced with a documented decision. The more substantive half is unchanged: it's still one shared periodic flush window per loop iteration (lines 32-42), not a per-message delay — a message arriving just before the next flush still gets near-zero delay, which is exactly the timing signal the mechanism claims to prevent. |
| 6 | `MessagesViewModel`/ordering bypass (§11) | **FIXED in `:app`**, gap remains in `:simulator`. `MessagesViewModel.summarize` now sorts on `displayTimeMs` (`MessagesViewModel.kt:73,82`) = `minOf(sentAtMs, receivedAtMs)` (`Message.kt:85-86`), matching the DAO's own anti-spoof `ORDER BY` — the ViewModel no longer discards that protection by re-deriving from raw `sentAtMs`. `ThreadViewModel.kt:81-98` separately has its own correct HLC-based sort, not a regression. Residual: `InMemoryMessageRepository.observeThread`/`observeAllDirect` (`simulator/.../InMemoryRepos.kt:76-86`) still have **no** `sortedBy` at all — simulator DM/thread order is undefined, cosmetic today, will bite the first ordering-sensitive scenario test. |

## §15 — CRITICAL, new this session: Rooms (O79) posting-certificate enforcement is entirely unwired

The single most severe finding of this session. `docs/ROOMS_THREAT_MODEL.md:30-44`
states, in the present tense, that honest peers verify a posting certificate
on every inbound channel message and drop anything uncertified: *"A modified
client without a certificate can compose anything; every honest peer fails
the structural check and drops the message."* This is false for the shipped
code:

- `GossipEngine.composeRoomMessage` (`GossipEngine.kt:708-749`) has no
  `postingCert`/`RoomPostingCert` parameter and never attaches one.
- `handleRoomMessage` (same file, `1079-1120`) never calls
  `RoomPostingCertVerifier.verify` or does any moderator-authority check.
- `RoomPostingCertVerifier` (`core/.../protocol/RoomPostingCertVerifier.kt`)
  is referenced nowhere outside its own file and its own isolated unit test
  — confirmed by repo-wide grep. It's a correct, tested primitive that was
  never plugged in.

**Concrete failure scenario:** for an OPEN room, the routing tag is
`SHA-256("rumor-room-route-v1:" || roomId)` — publicly computable from the
public `roomId`. Any user, member or not, computes the tag, signs a
`ROOM_MESSAGE` with their own (valid, unrelated) Ed25519 key, and every
honest peer accepts/displays/relays it exactly like a legitimately-certified
post. `MEMBER_ONLY`/`MOD_APPROVED` posting policies have **zero** runtime
effect — every room behaves as `ANYONE_WITH_MOD_REMOVAL` regardless of what
was declared at `RoomCreate` time.

**Compounding issue — no wire path for moderator actions at all.**
`RoomActionKind`/`RoomAction` (`core/model/Room.kt:104-136`) have no
corresponding `MessageType`, no `composeRoomAction`, no receive-side
handler. So even a legitimate moderator's kick/ban/remove has nowhere to go
on the wire — `docs/ROOMS_THREAT_MODEL.md:46-50`'s claim that forged actions
"fail sig verify at every honest peer" is moot because *no* action, forged
or real, currently propagates. A kicked/banned member's key keeps working
indefinitely; nothing in the mesh can revoke them today.

Recommend one of two things, not a third option where this sits
unaddressed: either wire `RoomPostingCertVerifier.verify` + a moderator-
authority lookup into the compose/handle path before Rooms is considered
shippable, or edit `ROOMS_THREAT_MODEL.md` to honestly state posting-cert
enforcement is unimplemented — the doc's own stated rule is *"if a policy
can't be expressed this way, document the limit honestly and don't pretend
it's enforceable"*; right now it violates its own rule.

## §16 — `RoomMessageMalformedTagTest` is vacuous (same pattern as the historic `PerPeerRoutingTest` bug)

`simulator/.../RoomMessageMalformedTagTest.kt:44-59` builds a tampered
message (`msg.withRoomRoutingTag("%%% not base64 %%%")`) as a **local
in-memory object that is never sent through `SimTransport` or
`gossipEngine`** — the test's own comment admits it ("We can't easily inject
`tampered` through SimTransport... So we just verify nothing crashes when we
construct a message"). The assertion is trivially true regardless of whether
the real malformed-tag drop path (`GossipEngine.kt:1082`,
`runCatching { Base64Codec.decode(tagB64) }.getOrNull() ?: return`) works
correctly, because `tampered` never reaches it. This is the exact shape of
bug CLAUDE.md/handoff.md already document once for `PerPeerRoutingTest` (a
test that passed vacuously since birth) — worth a standing lint/review habit:
any sim test that builds a "bad" message as a bare local object instead of
routing it through `SimTransport`/`gossipEngine` is testing nothing about the
receive path. `MultiRoomCoexistenceTest` and `MeshViewConvergenceTest`, by
contrast, were checked and are legitimate — they drive real exchanges.

Sealed-sender tags (O53) were also checked as part of this pass: the design
is sound (`HMAC(sharedKey, "rumor-dm-v1:" || messageId)` — varies per
message, so it doesn't create the stable per-recipient clustering a fixed
tag would) and `SealedSenderTagWireTest` genuinely exercises it end-to-end.
Not a new finding, but worth confirming: `composeDirect` still sends the tag
**alongside** the still-plaintext `recipientId` field — this is accurately
documented as an intentional "coexistence phase" (`SealedSenderTag.kt:26-32`,
`ARCHIMEDES_MERGE_CATALOG.md:66-67`), not a misrepresented gap like §15.

## §17 — new, concrete: HLC `SharedPreferences` upgrade crash (`putInt`→`getLong` type mismatch)

`MeshService.kt:262-267` reads the persisted HLC counter with
`hlcPrefs.getLong("counter", 0L)`. Before commit `83258e9` (the Int→Long
widening), this same key was written with `putInt("counter", …)`. Android's
`SharedPreferences.getLong()` throws `ClassCastException: java.lang.Integer
cannot be cast to java.lang.Long` when a key was previously stored as an
Int. **Concrete scenario:** any device that ran a build between `386832d`
(HLC wire adoption) and the pre-`83258e9` builds, then gets a vc29+ build
sideloaded over it without a full data wipe, crashes on every `startMesh()`
call until app data is cleared. There's no try/catch anywhere in
`MeshService.kt` around this read (0 matches), and no migration/guard for
the type change. This is the same class of bug as G24's stale-manifest-FQN
ColorOS crash — a silent, in-place-upgrade-only failure mode that a fresh
install or fresh test never surfaces. Cheap fix: read defensively (try
`getLong`, fall back to reading `getInt` and coercing, clear-and-reinit on
`ClassCastException`) or bump the SharedPreferences key name alongside the
type change next time this happens.

Separately, the HLC counter-ceiling defense itself was re-checked and holds:
`MAX_COUNTER = 1_000_000_000_000_000L` (`Hlc.kt:116`) is enforced on
*incoming* `remote.counter` before any `+1` arithmetic, so a hostile stamp
near `Long.MAX_VALUE` can't reach an overflow/wrap. It is, however, only
tested at one boundary value in `HlcTest.kt` — no test exercises a
near-`Long.MAX_VALUE` adversarial counter end-to-end through the wire/engine
(`HlcWireTest`/`HlcOrderingScenarioTest` only cover clock-skew scenarios, not
adversarial magnitude).

## §18 — new: O124 `PresenceReplyGate` sybil reply-storm amplification

`PresenceReplyGate.shouldReply` keys its cooldown on `msg.senderId`, which by
the time it's called has passed Ed25519 verification + identity-binding —
so the cooldown itself can't be spoofed for a *fixed* identity. The gap is
one level up: minting a **new** identity is free (an Ed25519 keypair, no
PoW, no registration), and `MeshViewTracker.hasFresh` is false for any never
-before-seen id, so every fabricated identity gets exactly one **free**
immediate reply the first time it's seen (`PresenceReplyGateTest.kt:43-47`
demonstrates this directly). There is no independent *global* rate limit on
how often solicits collectively can force `pulse()` —
`GossipEngine.kt:1157-1158` calls it unconditionally on `shouldReply==true`,
and the per-sender ingest rate bucket (`MessageStore.kt:94,162-166`) is
*per distinct sender*, so N sybil senders aren't throttled collectively
either. **Concrete failure:** an attacker in radio range mints N keypairs,
sends N SELF_PRESENCE beacons each with a never-seen `senderId`; each one is
"unknown," each forces a real signed **broadcast** relayed
`DEFAULT_BROADCAST_HOPS` across the mesh. This is worse than the accepted
O16/O27/O60 sybil floor elsewhere (which bounds *local* cost per forged
sender) because here one cheap forged inbound message causes *mesh-wide
outbound amplification*. `PresenceReplyGate`'s own KDoc claim ("probe spam
can't force reply spam") is true only against a fixed identity, not a sybil
swarm, and doesn't caveat that the way O16's docstring caveats its own
sybil floor.

## §19 — prebuilt-vs-handrolled sweep (minor, mostly doc-drift, one dependency note)

- `Rbsr.kt:16-17` — file header still says "compare XOR fingerprints"; the
  actual `fingerprint()` (lines 248-277) was changed to additive-mod-2^256
  specifically *because* XOR was forgeable (documented 20 lines below and in
  CLAUDE.md's O42 history). Header contradicts the code directly below it —
  one-line fix, but exactly the kind of drift that misleads a future
  security reviewer.
- `CryptoManager.kt:57-66` — `x25519Agreement`'s docstring still says
  "PBKDF2 with the RumorDH salt"; it actually calls `deriveAesKey`, which
  has been HKDF-extract (HMAC-SHA256) since the G20 fix. Stale comment on a
  wire-format-critical, security-relevant function.
- `MeshtasticBridge.kt:160` — instantiates a fresh `java.security.
  SecureRandom()` per outbound packet for a non-secret packet ID (docstring
  says collisions are harmless). Wasteful and inconsistent with the rest of
  the codebase's `kotlin.random.Random`-for-non-crypto convention
  (`RelayBatcher.kt`, `WifiDirectTransport.kt:732`). Cosmetic/perf, not a
  security issue.
- `simulator/build.gradle.kts`: `logback-classic:1.4.14` is the last release
  on the now-EOL 1.4.x line (active fixes moved to 1.3.x/1.5.x; two CVEs
  patched in 1.3.15/1.5.13 were never backported to 1.4.x). Low real-world
  risk (local dev tool, not attacker-facing), easy version bump.
- General note: most third-party pins (compose-bom 2024.02.00, ktor 2.3.7,
  kotlinx-coroutines/serialization 1.7.3/1.6.2, koin 3.5.3, Kotlin 1.9.22,
  commons-compress 1.26.0) date to late-2023/early-2024 against a mid-2026
  codebase — a routine version-bump audit is due, no specific CVE confirmed
  in this pass (no network access available to check current advisories).
- Everything else checked came back clean: `PersistencePlanner` (correct
  hand-rolled Kruskal + union-find, appropriately app-specific per policy),
  `MeshCoreFrames`/`MeshCoreBridge` (simple glue / already-tracked FNV-1a,
  not a correctness issue), `core/platform/*` codecs (thin JDK delegates,
  not hand-rolled), `BloomFilterData` (policy-adopted MurmurHash3, custom
  wire layout no library fits), `Hlc.kt` (correct, minimal, too small to
  warrant a dependency), and random-number usage fleet-wide (crypto paths
  consistently through `SecureRandom`-backed `PlatformRandom`, non-crypto
  paths consistently `kotlin.random.Random` with explicit comments — no
  misuse found). The O42 CBOR-for-RBSR-frames deferral is worth a second
  look next wire-version bump only: the wire model set has grown a lot since
  that call (Rooms/O79, sealed-sender, prekeys, HLC `_ext` — ~1900 lines of
  `core/model/` now riding the same base64-in-JSON envelope), closer to but
  not past the deferral's own stated trigger ("the whole GossipPacket wire
  moves to a binary envelope").

## §20 — dead/stale code sweep (small; this repo's own prior audits already caught most of it)

- `MessageDao.kt:99-100` (`delete(id)`) — byte-identical SQL to
  `deleteById` (line 78-79), zero callers anywhere in `app/`/`core/`. Dead,
  safe one-line removal, likely predates O40's `deleteById` addition.
- `WifiDirectTransport.kt:491` (`verifyClientGroup`) — name overpromises:
  every other `*Verifier`/`verify*` in the codebase is a pure boolean check;
  this one queries group info, compares SSID, and unilaterally calls
  `removeGroup()` as a side effect with no result returned. Functionally
  fine (it's O98 group reconciliation), just a naming mismatch worth a
  rename or split — not a bug.
- `MeshService.kt:269-270` (`HlcClock.onAdvance` → `SharedPreferences.edit()
  .apply()`) — fires on every local compose *and* every newly-ingested
  message (`GossipEngine.kt:998`, gated only by `isNew`, no batching). A
  partition-heal or reseed delivering a burst (up to 200 messages per P2
  shaping) queues one `Editor` commit per message with no debounce. Not a
  correctness bug (`apply()` is async, last-write-wins is safe for
  monotonic HLC state) but an unflagged efficiency gap in code that landed
  the same day as HLC itself and hasn't been through a prior audit pass.
- Everything else checked came back clean: no `TODO`/`FIXME`/`XXX`/`HACK`
  anywhere in main source, no commented-out code blocks, no unjustified
  silent catch blocks, no reintroduced duplicate-FQN files across the three
  modules, no orphaned KMP/iOS shim leftovers, no disabled/`@Ignore`d tests,
  no orphan files matching Legacy/Old/Deprecated/Stub naming.

## §21 — priority order if picking one thing at a time

1. **§15 — Rooms posting-cert enforcement.** Highest severity: a shipped
   feature's own threat-model document asserts a security property that
   isn't implemented. Either wire it or correct the doc before Rooms is
   presented as ready for anything beyond OPEN rooms.
2. **§2/O108-O109 (transfer OOM) — still open, and this session found the
   allocation trigger is cheaper than previously described** (single real
   chunk + huge claimed `totalBytes` → immediate giant `ByteArray`, no
   watchdog wait needed). Remotely triggerable, no auth required.
3. **§17 — HLC SharedPreferences crash.** Not a security issue but a
   guaranteed field crash on a specific in-place-upgrade path; cheap to fix,
   ships confusion (looks like a random new bug) if it hits a real device
   before it's fixed.
4. **§18 — sybil reply-storm amplification.** Turns a cheap local forgery
   into mesh-wide broadcast amplification; same tier as the transfer OOM
   in terms of "no privileged access needed," lower in terms of blast
   radius (bandwidth/battery drain, not data loss or corruption).
5. **§3 — OnlineStatusTracker growth+poison.** Real, still open, matches
   this repo's own O55 long-term-collapse framing (unbounded growth over
   months is exactly the failure mode that framing warns about).
6. **§16 — vacuous test.** Doesn't fix a bug by itself, but leaves the real
   malformed-tag path unverified; cheap to fix by routing the tampered
   message through `SimTransport` like `MultiRoomCoexistenceTest` does.
7. Everything in §19/§20 — real, low urgency, mostly doc-drift and
   cosmetic/efficiency items.

## What this session did not get to

Did not attempt any fixes (out of scope by the session's own instructions —
research/audit only). Did not re-derive the message-ordering design
discussion from the 2026-07-16 session B notes (still open, still a
judgment call for whoever picks it up). Did not have Android SDK/emulator
access in this container, so none of the above was checked on-device —
everything here is a static-code finding, not a field reproduction. Did not
review `fastlane/`/F-Droid reproducibility tooling this round. Did not
check current CVE databases for the dependency-staleness note in §19 (no
network access in this pass) — treat that one as "worth checking," not
"confirmed vulnerable."

---



Everything committed AND pushed; all suites green; fleet (Samsung R58M30FSJKE /
Moto ZY22KP7F59 / OnePlus ec5b0707) flashed **0.6.8-hlc-long-counter (vc29)**
together. No known-failing tests, no partial fixes in flight — clean stop.

## What shipped this session (chronological, all field-verified unless noted)

1. **0.6.2 — echo-loop fix completed** (finished b42c4ed's punch list):
   SelfPresenceTest rewritten to the ephemeral-beacon contract;
   `MessageRepository.deleteByType` across all 4 impls + startMesh purge of
   accumulated SELF_PRESENCE rows; DM-decrypt exception logging. Field: echo
   loop GONE (0/0 sessions, bytes=0, stores purged of ~1500 beacon rows each).
2. **0.6.3 — THE DM decrypt bug found and fixed in minutes** thanks to the new
   logging: `AEADBadTagException` because **MessageEntity had no `ext` column**
   — Room silently stripped `_ext` (compression AAD flags, sealed-sender tags,
   room tags, thread metadata) from every stored AND relayed message. Schema
   v9→v10, `ext` JSON-blob column, `MessageEntityExtRoundTripTest`. Invisible
   to sim/tests (in-memory repo keeps the object). Recorded in CLAUDE.md
   Critical bug history with the rule: new RumorMessage field ⇒ same-commit
   entity column + round-trip test. Field: user-confirmed DM decrypt success;
   all 3 DBs show `_ext` intact incl. on relayed copies.
3. **0.6.4/0.6.5 — O124 (search-button announce+solicit)**: scan button now
   fires an immediate SELF_PRESENCE pulse + transports restart;
   `GossipEngine.presencePulse` hook + `PresenceReplyGate` (responder-clock
   per-peer cooldown, user-tuned 2min→30s, only for unknown-or-stale peers via
   `MeshViewTracker.hasFresh`). Root cause it fixes: a freshly-wiped node is
   invisible until its FIRST completed exchange (O97 field note filed).
4. **0.6.6 — O98 dead-group client re-bootstrap**: field-diagnosed ~3-min
   blackout (Moto radios off → OnePlus exhausted retries → went passive).
   Fixes: retry exhaustion kicks `rediscoverPeers()` + clears attempt grace;
   Client-role discovery guard escape hatch (no backbone SSID on air for the
   whole BACKBONE_MEMORY window → bootstrap-by-hosting; junior-yields resolves
   double-host). Field-verified full re-convergence in 62s from first radio-on,
   including a live double-host + junior-yield + blind-join sequence.
5. **O114 → G41**: simulator determinism — `SimNode` wraps its scope with
   `limitedParallelism(1)` (per-node serialized handlers); 19/20 then 12/12
   parallel full-suite runs green. Pattern recorded: awaitUntil the LAST
   observable effect; absence assertions need a settle delay.
6. **O95 → G42 (0.6.7/0.6.8)**: **HLC on the wire.** `_ext.hlc =
   "wall:counter"` stamped in buildMessage (after signing), folded in
   processIncoming after verification; `HlcClock` thread-safe with
   restore/onAdvance persistence (SharedPreferences in MeshService); thread
   display sort `(min(hlcWall, receivedAtMs+48h), counter, id)` with
   displayTimeMs fallback. Two-layer defense: clock bounds only absurdity
   (10y forward + counter sanity — a tight clamp would break the 64-day-slow
   motivating case, since that node sees every CORRECT peer as far-future);
   tight future-pinning clamp lives at display against receivedAtMs.
   **Pin-attack analysis on the G42 row**: sybil-irrelevant (max-fold not a
   vote; ONE hostile stamp pins the mesh), causality survives as a Lamport
   clock under pin, display unaffected — accepted per O51/O60 floor.
   **0.6.8**: counter Int→Long + ceiling 1e15 (user probe exposed that under a
   pin every node crosses an Int-scale ceiling in weeks → peers reject legit
   stamps → causality breaks; Long makes it unreachable). Wire is fleet-only,
   so the format change was free. HLC field-verifies passively via normal use.

## Standing directives recorded this session

- **Prebuilt-first reflex** (memory `prebuilt-first-reflex`): before coding
  anything new, check for a tested existing impl; say so at plan time.
  (Session origin: LocalOnlyHotspot-vs-O98 review — verdict: LOHO is
  system-app-gated on credentials/channel and prompts on client joins; the
  credentialed autonomous createGroup IS the closest legal equivalent.)
- Queue ordering: **smallest → largest**. O114 ✓, O95 ✓ — **next is O100**
  (content-addressed chunk identity first — the row says do the wire-format
  decision BEFORE more chunk traffic accretes on random transferIds; then
  multi-source assembler; sim comparison of fetch mechanisms A/B/C).

## Next-session pointers

- **O100 is the next queue item** (multi-session-sized; CLAUDE.md row carries
  the full design brief incl. the per-chunk-hash hard constraint).
- Samsung client-churn (O94 note) is worth a dig while the fleet topology
  still reproduces it — logs from the observation are described on the row.
- The 2026-07-18 audit residue rows (O115–O123 minus closed ones) remain,
  tiered in CLAUDE.md; several are one-sitting (O120 prune loops, O121 pins).
- Fleet passphrase + workflow: see memories `rumor-test-devices` /
  `rumor-hardware-test-workflow` (user does on-screen steps; adb is ours).

---

# Prior-session handoffs — archived

The current handoff is above. Earlier session handoffs were removed (2026-07-19)
once their work landed — every outcome is recorded durably elsewhere: shipped
work in `docs/COMPLETED_GAPS.md` (G-rows), open follow-ups as O-rows in
`CLAUDE.md`, and the full prose in git history (`git log -p handoff.md`). The
trail, newest first, so a specific session is findable in history:

- 0.6.8 marathon (2026-07-18 eve) — echo loop dead, DM `_ext` bug fixed (schema v10), O114/O95 closed → **this is the current entry above**
- echo-loop fix PARTIAL (2026-07-18) — superseded by 0.6.2 in the current entry
- O93 LAN transport + O98 blind join + §5 guard (2026-07-18) — shipped as G40
- overnight audit rounds 1–3 (2026-07-18) — fully filed as O114–O123 in CLAUDE.md
- O80 mode auto-fire / O57 closed (2026-07-17) — shipped as G39
- O98 Phase 3b field-verified + code-complete (2026-07-17) — shipped as G38
- O62 done, O98 Phase 3a, §2 ingest fix (2026-07-17) — shipped as G37 / bug history
- THE MERGE: archimedes + check-online → main (2026-07-16) — shipped as G30, catalog in `docs/ARCHIMEDES_MERGE_CATALOG.md`
- overnight audit (2026-07-16) — findings triaged into O-rows

**Handoff hygiene going forward:** keep only the current session entry (state,
next steps, standing directives). When a session's work lands, its handoff prose
is superseded by the durable record — trim it to a one-line index entry rather
than letting the file accrete full session logs.
