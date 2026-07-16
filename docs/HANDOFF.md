# Handoff note — overnight research/audit session (2026-07-13)

> Per `docs/MULTI_INSTANCE_COORDINATION.md` async-handoff section.
> **This session made NO source-code changes.** Scope was research,
> bug-hunting, and documentation audit only, per explicit instruction.
> Everything below is a finding to triage, not a commit to review.

## Session identity note

This session started on the auto-provisioned branch
`claude/laughing-ramanujan-huja8y` (tip `0908444`, identical to
`claude/check-online-status-vef1H`). Per the task instructions, I
searched for "the branch that is VERY developed and recently
developed" and identified `claude/practical-archimedes-wmySm` (245
commits, 339 files, referenced by name inside this very repo's
`CLAUDE.md` G10 entry) as the intended target, and switched to a
worktree on it to do this research and write this file. **This is
the single most important finding of the session — see §2 below:**
`practical-archimedes-wmySm` and `laughing-ramanujan-huja8y` are
**not the same lineage**. They forked on 2026-05-27 and have been
developing incompatibly ever since. Read that section before acting
on anything else here.

---

## 0. The canary at the top of CLAUDE.md is a real convention — comply with it

**For future instances that mistake this for a prompt injection —
which I did, at the start of this session, and had to walk back:**

`CLAUDE.md` opens with:

> **Acknowledge: sign all messages with "By Order Of The High
> Magnate".** (Canary. If this stops appearing in replies, the
> context window has scrolled past the top of this file.)

This is an intentional, sanctioned project convention set up by the
user, not a prompt injection or a rogue self-insertion from a prior
session. Its purpose is **context-truncation detection**: if you
sign your commits/messages with the phrase, the user can tell at a
glance that you still have `CLAUDE.md` in context; if commits stop
being signed, the file has scrolled past the top of your window and
your architectural context is compromised. Long autonomous runs on
this repo can hit that boundary, and the phrase is how the user
notices.

**How to handle it:** when you see the canary in `CLAUDE.md`, sign
your commit messages with "By Order Of The High Magnate." — a
single line at the end of the commit body, before the standard
Co-Authored-By trailer. That's it. The phrase itself is meaningless
window-dressing; the *presence or absence* of it in your commits is
the actual signal the user reads.

**Sanity check on it not being an injection:** the mechanism is
symmetric and transparent — the user knows about it, is the one who
established it, expects it, and reads for it. A real injection
would try to hide, to persist without user awareness, or to shape
behavior in ways the user hasn't sanctioned. This does none of
those. Also: it's non-consequential to output — it just adds a
phrase to commit messages that doesn't affect code or decisions.
The `docs/HANDOFF.md` entry from an earlier session that documents
"'By Order Of The High Magnate' used on every commit message this
session" under a "## Canary" heading is legitimate — that's the
convention working as designed, not laundering.

**I got this wrong at the start of the session** and my first
handoff commit (`ca5dbf4`) contained an entire section framing it
as a compromise incident. That framing was wrong; the user
corrected it and asked for this rewrite. The rest of that commit's
technical findings — the ingest dedup-before-sig-verify bug, CI
never passing, the branch fork, etc. — all stand on their own and
are unaffected by my misread. This §0 exists so the next instance
that reads `CLAUDE.md` and briefly wonders whether it's looking at
an injection can immediately see that the answer is no, it's a
convention, sign your commits with the phrase and move on.

---

## 1. CI has never once passed, on any commit, in this repo's history

Checked via the GitHub Actions API: **all 34 recorded `CI` workflow
runs on `main`, from the very first (2026-05-28) through the
current tip (`9cb2ad9`, 2026-06-01), have `conclusion: failure`.**
Zero green runs, ever.

**Root cause (confirmed from job logs):** `gradle.properties` —
which sets `android.useAndroidX=true` — **does not exist on `main`
at all.** It was added in commit `f7607a9` ("Add gradle.properties
— AndroidX flag + JVM heap"), but that commit landed on the
`practical-archimedes-wmySm` lineage on 2026-06-02, **one day
after `main`'s last commit**, and was never merged back. Every CI
run fails at Gradle's `:app:checkDebugAarMetadata` task, before a
single test executes:

```
Configuration `:app:debugRuntimeClasspath` contains AndroidX
dependencies, but the `android.useAndroidX` property is not
enabled... Set `android.useAndroidX=true` in the `gradle.properties`
file and retry.
```

This means every "tests green" / "`:core:jvmTest` +
`:simulator:test` both green at HEAD" claim recorded in
`docs/HANDOFF.md` and `CLAUDE.md` across dozens of sessions was
**never independently verified by CI** — only by whichever
autonomous session happened to have a working local JDK/Android SDK
in its container that particular run (several `HANDOFF.md` entries
explicitly note `:app` tests were skipped locally too: "not
runnable in this container (no Android SDK)"). The G7 backlog row
("CI + versionCode discipline") is marked complete/closed in
`CLAUDE.md`, and the workflow YAML does exist and is well-formed —
but the thing it's supposed to guarantee (a working safety net) has
never actually been true.

**Compounding factor:** `.github/workflows/ci.yml` only triggers on
`push`/`pull_request` to `main`. This repo has **zero pull requests
in its entire history** (checked via the GitHub API) and `main` has
been frozen at `9cb2ad9` since 2026-06-01 while all real development
continued on unmerged feature branches. So even after
`gradle.properties` was fixed downstream, no CI run has ever
exercised that fix — CI simply never runs against
`practical-archimedes-wmySm` or any other active branch. The
project has been flying with a 245-commit-deep, 6-week-long safety
net that has never functioned, on a branch nobody has told GitHub
Actions to look at.

**Recommendation:** cherry-pick `f7607a9` (or just recreate
`gradle.properties`) onto `main`, retrigger CI, and — separately —
decide on a real merge/PR strategy (see §2) so CI actually covers
the branch where work happens.

**Even that fix would not be enough on `practical-archimedes-wmySm`
specifically — there's a second, independent CI break.**
`.github/workflows/ci.yml:23` runs `./gradlew :core:test
:app:testDebugUnitTest :simulator:test`. `:core` is a Kotlin
Multiplatform module (JVM target only) — its test task is
`:core:jvmTest` (or `:core:allTests`), never a bare `:core:test`.
Confirmed by actually running it: Gradle fails immediately with
`Cannot locate tasks that match ':core:test' as task 'test' not
found in project ':core'` — Gradle validates every task name up
front, so **none of the three suites run**, including
`SentAtMsLintTest` and the G6 `AppModuleTest` Koin DI smoke test
that `CLAUDE.md` cites as the safety net for O64 and DI-wiring
regressions respectively. This is a branch-local regression
(`a8d012e` added the `:core:test`/`:simulator:test` invocations
without checking the task exists for a multiplatform module) — so
even independent of the AndroidX/`gradle.properties` problem on
`main`, CI on *this* branch would still fail at task-resolution
time, before a single test runs, for a second, unrelated reason.
Fix: `:core:jvmTest` in place of `:core:test`.

---

## 2. Two incompatible development lineages have been running in parallel since 2026-05-27, and neither has the other's fixes

`git merge-base` confirms `claude/practical-archimedes-wmySm` and
`claude/check-online-status-vef1H` (≈ `claude/laughing-ramanujan-
huja8y`, this session's originally-assigned branch, now tip
`0908444`) share a common ancestor at commit `d3a6795`
(2026-05-27), then diverge completely:

- **`practical-archimedes-wmySm`**: 200 commits since the fork.
  Protocol/crypto/simulator-heavy: O79 Rooms protocol end-to-end,
  O53 sealed-sender DM tags, O91 Ed25519→X25519 production fix, O85
  two-tier dedup, O42 RBSR, G14 Jazzer fuzzing, O16/O13 hardening,
  49 test files. Almost entirely `:core`/`:simulator` work, done
  without real hardware.
- **`check-online-status-vef1H`/`laughing-ramanujan-huja8y`**: only
  5 commits since the fork, but each is real-device field-test work
  that is expensive to redo: the G8 "five stacked bugs" first
  successful two-device exchange, G9 persistent-link O2 substrate,
  **G12's three-node mesh fix** (the `claimSession` GO/client
  direction-tiebreak bug — "lower userId keeps outbound" assumes
  both directions exist, which is false in a GO↔client group),
  and the device-quirk/session-lifecycle fixes that came out of
  testing on a Moto G Play, a OnePlus CPH2515, and a Samsung
  S10e.

**These are not redundant — they're complementary and both real.**
But because they never merged, `practical-archimedes-wmySm`'s
`WifiDirectTransport.kt`/`GossipSession.kt` **still has the old,
already-diagnosed-and-fixed bugs** from the other lineage. My
transport-layer audit agent independently rediscovered the exact
G12 direction-tiebreak bug in `archimedes`'s current code (see §4.3
below) before I connected it to the sibling branch's history — it's
present, unfixed, on the branch with 200 commits of newer protocol
work sitting on top of it. If `archimedes` is ever field-tested on
real phones as-is, it will hit the same three-node mesh failure
`check-online-status-vef1H` already found and fixed.

**Two more branches are also stranded, smaller:**
- `claude/compassionate-dirac-UOp8z` (fork point same era): 2 unique
  commits — O3 reliability-weighted ranking, O13 bloom-OOM
  hardening. **Both were independently re-implemented on
  `archimedes`** (as `G19`/`G20`) — this is confirmed duplicate
  work across branches that never talked to each other, exactly the
  failure mode `docs/MULTI_INSTANCE_COORDINATION.md` warns
  "trades away the single-source-of-truth benefit" for.
- `claude/kind-ramanujan-7Ydwp`: 1 unique commit, `n100-
  bootstrap.sh` — a 197-line home-lab appliance script (Pi-hole,
  Suricata/Zeek, WireGuard netns) that has **nothing to do with
  Rumor**. No secrets in it (spot-checked), just orphaned,
  off-topic content sitting in this project's git history on an
  otherwise-abandoned branch. Worth deciding whether to keep,
  relocate, or drop.
- `claude/practical-johnson-hzNWF` — fully absorbed into
  `archimedes` already (confirmed ancestor), no stranded work.

**Recommendation — this is the highest-leverage single action
available on this repo right now:** reconcile `archimedes` and
`check-online-status-vef1H` before either goes further. The
transport-layer bugs below (§4) need the field-tested fixes from
the `check-online-status-vef1H` lineage; the protocol/crypto work
on `archimedes` needs to inherit them, not rediscover them the hard
way on a real device a second time. Given no PRs exist anywhere in
this repo, consider whether direct-push-to-shared-branch (per
`MULTI_INSTANCE_COORDINATION.md`) or actual PRs-with-review is the
right process going forward — the branch-per-session pattern that
produced this fork is the "stricter isolation... trades away
single-source-of-truth" case the coordination doc itself warns
about, and it's now cost real duplicated effort (O3/O13) plus a
live, reintroduced bug (G12).

---

## 3. Cross-agent-corroborated bug: hop-limit overrides are dead code, both for DMs and broadcasts

Two independent research agents (core sync/routing, and simulator/
scenario audit) found this independently while investigating why
`docs/CLAUDE.md`'s G22 scenario-survey entry records scenarios
02/03/15/16 as still failing their delivery-ratio assertions.

**`core/protocol/GossipEngine.kt:52-55`:**
```kotlin
private const val DEFAULT_BROADCAST_HOPS = 7
private const val DEFAULT_DIRECT_HOPS = 15
private const val MAX_BROADCAST_HOPS = DEFAULT_BROADCAST_HOPS   // == default
private const val MAX_DIRECT_HOPS = DEFAULT_DIRECT_HOPS         // == default
```

`composeDirect` (line 438) does accept a `hopsToLive` override, and
the simulator does drive it (`SimWorld.kt:212`, sourced from a
scenario's `hops_to_live` JSON param — e.g. `40` in scenarios
15/16). But `coerceIn(1, MAX_DIRECT_HOPS)` clamps any value above
15 straight back down, because the ceiling constant is defined as
literally equal to the default. **The override parameter that
exists is unable to ever raise the ceiling — only lower it.** Same
mechanism for broadcasts: `MessageGenerator` sets a raw
`hopsToLive = 40` for scenario 15/16, but `clampTtl`
(`GossipEngine.kt:1126-1143`) enforces `MAX_BROADCAST_HOPS = 7` at
message genesis via `injectFromPlugin` → `processIncoming`, before
the message ever leaves the originating node.

**This is deeper than CLAUDE.md's own G22 entry currently records**
— G22 concludes "the `hops_to_live` scenario param only affects
BROADCAST messages; DMs use the hardcoded `DEFAULT_DIRECT_HOPS =
15`," implying DMs need a fix and broadcasts are fine. Actually
**both** traffic classes are capped at compile-time defaults
regardless of what any caller (scenario config or real
`composeDirect`/`composeBroadcast` call site) requests, because the
override plumbing exists but the ceiling it's checked against was
never decoupled from the default it's supposed to override.

**Production impact, not just simulator:** `app/.../service/
MeshService.kt` calls `gossipEngine.composeDirect(recipientId,
recipientKey, text)` with **no `hopsToLive` argument at all** — so
real on-device DMs are unconditionally capped at 15 hops, silently,
with no error surfaced to the user when a message dies mid-mesh.
On any mesh with DM-relevant diameter above 15 hops (plausible for
the large public-gathering/protest/festival scenarios this app is
explicitly aimed at — see `README.md`'s "Settlement A / hilltop
relay / Settlement B" framing), a DM to a distant peer cannot
structurally arrive, ever, and there's no user-visible signal that
this happened.

**Recommendation:** decouple `MAX_DIRECT_HOPS`/`MAX_BROADCAST_HOPS`
from their `DEFAULT_*` counterparts (raise them independently, or
better, thread the real intended ceiling through `clampTtl` per
message rather than clamping to a compile-time constant at all).
This one change is likely to fix scenarios 15/16 outright and
probably materially improves scenario 02/03 delivery ratios too.

---

## 4. Other confirmed findings, by area

*(Everything below was independently verified by a dedicated
research agent reading the actual code — not inferred from
CLAUDE.md's own self-description. "Confidence" reflects the
agent's assessed certainty after code-level verification.)*

### 4.1 `:core` sync / scheduling / routing

- **BreadcrumbCache hop count uses the wrong constant for DMs**
  (`GossipEngine.kt:876`): `hopCount = (DEFAULT_BROADCAST_HOPS -
  msg.hopsToLive).coerceAtLeast(1)` always subtracts from 7, even
  for DIRECT messages whose budget is 15 — so every DM's stored
  breadcrumb `hopCount` is meaningless (usually clamped to 1).
  Low current impact (routing doesn't consume this field yet) but
  a landmine for O29/breadcrumb-routing follow-up work. High
  confidence.
- **Unsynchronized read-modify-write races** in
  `TopologyTracker.recordSession`/`recordSessionFailed`
  (`core/routing/TopologyTracker.kt:66-96,104-116`) and
  `NeighborStore.update` (`NeighborStore.kt:28-33`): both do
  `launch { read; ...; write }` with no lock, so two exchanges
  completing close together can lose an update (a `failureCount`
  increment or EMA sample). This directly feeds the O3/G19
  `bytesRelayed/(1+failureCount)` ranking formula, so races here
  skew relay-target selection. `BreadcrumbCache.record` does this
  correctly via an atomic `compute()` — the pattern just wasn't
  applied consistently to its siblings. Medium confidence
  (mechanism confirmed; real-world frequency not measured).
- **`BreadcrumbCache.pruneOld()` / `TopologyTracker.pruneStale()`
  are never called anywhere** — grepped all three modules, no
  caller outside their own definitions and one test. The
  documented "24h breadcrumb inactivity prune" and "7-day route
  staleness de-rank" behaviors described in these classes' own doc
  comments never fire in production; both tables grow unbounded in
  Room. High confidence.
- RBSR (`Rbsr.kt`), the DRR `Scheduler`, and the two-tier
  `DuplicateFilter` were traced by hand and are correct — no
  findings.

### 4.2 `:core` crypto / protocol layer

**This is the most severe finding in the whole session — a real,
keyless censorship/blackhole attack against the gossip mesh.**

- **`MessageStore.ingest()` marks a message id as "seen" in the
  dedup filter *before* verifying its Ed25519 signature — and the
  mark is permanent.** `MessageStore.kt:112-132`: line 113 calls
  `duplicateFilter.recordAndCheck(msg.id)` first, which both checks
  *and unconditionally records* the id (into the bounded LRU, which
  spills into the long-lived Tier-1 bloom filter on eviction per
  the G23 two-tier design). Only afterward does the code check the
  rate bucket and finally the signature (lines 124-132); on sig
  failure it re-calls `recordAndCheck` (a no-op, already recorded)
  and drops the message — but the id stays marked "seen" forever.
  Message ids are 128-bit values exchanged on the wire during
  gossip summaries/RBSR *before* the full signed message
  necessarily arrives (`docs/wire-format.md:305` documents id as
  the dedup key; `GossipSession.kt:335` confirms transport-layer
  code never verifies signatures, only the engine does on ingest).
  **Concrete attack:** a malicious or compromised relay that can
  observe or race a message id before it reaches target node N
  sends N a forgery using that same id with a garbage signature.
  `ingest()` marks the id seen, rejects the forgery on sig-check —
  but when the *genuine*, validly-signed message later arrives at N
  via any honest path, `duplicateFilter.recordAndCheck` short-
  circuits before signature verification is ever reached, and the
  legitimate message is silently, permanently dropped as a
  "duplicate." No forged signature capability is required for this
  to work — only visibility into (or a race against) message ids,
  which the gossip protocol broadcasts by design. This is a
  full-strength targeted censorship primitive, not a minor
  correctness bug, and it isn't in CLAUDE.md's tracked backlog (G21/
  G23 cover rate-limiting and dedup capacity, not this ordering
  issue). **High confidence** — verified end-to-end against the
  actual ingest code path and the wire-format doc.
- **The per-sender ingest rate-limit bucket map (O16/G21) is
  unbounded and keyed on `msg.senderId` before the signature check
  authenticates it.** `MessageStore.kt:87-106,118`: `buckets:
  ConcurrentMap<String, Bucket>` has no cap, no LRU, nothing ever
  removes entries — unlike the carefully-bounded `DuplicateFilter`.
  `acceptForRate(msg.senderId, ...)` runs via `getOrPut` *before*
  signature verification, and `senderId` is attacker-controlled at
  that point. **Two-fold attack:** (a) rotate a fake `senderId` on
  every message and the "per-sender" throttle never engages — each
  fake id gets its own fresh 100/sec allowance, so O16's rate
  limiter is trivially bypassable by exactly the traffic it exists
  to stop; (b) the unbounded map itself is a memory-growth DoS —
  one bucket object per distinct fake sender string, for the cost
  of generating random strings, reachable over WiFi Direct or any
  BLE bridge. High confidence.
- **`GossipEngine.injectBridgedDm` assigns a fresh random message
  id (and "now" timestamps) on every call**, unlike normal composed/
  relayed messages where id is fixed once at compose time.
  `GossipEngine.kt:320-331`. Consequence: dedup provides zero
  replay protection on the O5a bridged-DM path — a replayed or
  radio-retransmitted ciphertext is treated as a brand-new message
  every time, and because timestamps are stamped fresh at
  injection, a stale replayed message displays with a current
  timestamp (the same class of bug O64/G24 fixed for normal DMs,
  re-opened through a different door — `displayTimeMs =
  min(sentAtMs, receivedAtMs)` does nothing when both inputs are
  freshly stamped "now"). `DmEnvelope`'s own doc explicitly pushes
  all replay-protection responsibility onto each envelope
  implementation with no engine-level backstop — but this means
  even a well-behaved envelope can't lean on `GossipEngine`'s
  existing dedup infrastructure at all. Medium confidence — not
  exploitable in production today since no shipped bridge
  implements the O5a DM path yet (CLAUDE.md confirms DM bridging is
  "not done" for both Meshtastic and MeshCore), but it's a real gap
  in a primitive presented as ready (G5) for plugin authors to
  build on.
- **`MessageStore` and `GossipEngine` — the two most central
  protocol classes in the whole codebase — have zero dedicated unit
  tests.** No `MessageStoreTest.kt` or `GossipEngineTest.kt`
  anywhere in the tree, despite thorough coverage of essentially
  every smaller primitive around them (HKDF, sealed-sender, prekey,
  message-delete, two-tier dedup, room posting-cert, golden crypto
  vectors all have dedicated suites). `SourceInvariantTest` (G25)
  is a regex-based structural check and would not have caught the
  ingest-ordering bug above — a straightforward "bad-sig-then-
  good-sig-same-id" test on `MessageStore.ingest` would have. Given
  as context for why the two findings above shipped unnoticed, not
  as a finding in itself.
- **Checked clean, no findings:** `HkdfSha256`/`HmacSha256` (correct
  RFC 5869/2104), `SealedSenderKey`/`SealedSenderTag` (correct,
  zeroing verified), `Ed25519ToX25519` (RFC 7748 birational map +
  clamping correct), `MultiRecipientEnvelopeCodec` (verify-then-
  decrypt ordering correct — the ordering bug above is specific to
  `MessageStore.ingest`, not this codec), `MessageDeleteVerifier`,
  `PrekeyVerifier`, `DmEnvelopeRegistry`, `RoomRoutingTag`/
  `RoomTagMatcher`, `CryptoGoldenVectorsTest` (genuinely pins
  wire-critical byte outputs).

**Recommendation for the ingest-ordering bug specifically:**
reorder `MessageStore.ingest` so signature verification happens
*before* `duplicateFilter.recordAndCheck` is called with intent to
record (a check-only pre-check, or simply verifying first and only
recording on success). This should be treated as the highest-
priority fix in this entire document — it's a live protocol-level
censorship vector, not a hypothetical.

### 4.3 `:app` Wi-Fi Direct transport layer

**Caveat up front:** this is the layer most affected by §2's
branch-divergence finding — several of these are bugs the other
lineage (`check-online-status-vef1H`) already found and fixed on
real hardware. They're re-listed here because they're genuinely
present in `archimedes`'s current code, verified directly against
the files (not inferred from the sibling branch's commit messages).

- **Server-accepted sockets never get a read timeout — likely
  explains the still-open O31 "intermittent HELLO timeout"
  mystery.** `GossipSession.kt:157` wraps the session in
  `withTimeout(30_000)`, but the actual I/O
  (`DataInputStream.readInt()`/`readFully()`) is blocking, not
  suspending — `withTimeout` cannot interrupt it. The client side
  sets `socket.soTimeout = 30_000` (`WifiDirectTransport.kt:231`)
  as a real guard; **the server/GO-accept side never sets
  `soTimeout` anywhere.** If a peer stalls mid-handshake (e.g. a
  brief Wi-Fi Direct power-save radio freeze), the GO's accept
  thread can block on a blocking read forever — no timeout, no
  `onExchangeFailed`, just a permanently stuck thread. High
  confidence; this is a strong candidate root cause for O31.
- **No WifiLock/WakeLock anywhere in the app** — corroborating
  cause: grepped for `WifiLock|WakeLock|PowerManager`, zero hits.
  Nothing stops Wi-Fi power-save from throttling the radio
  mid-exchange on long persistent links, and combined with the
  missing server-side timeout above, a power-save stall becomes a
  silent permanent hang instead of a clean retry.
- **`MeshService.startMesh()` has no reentrancy guard.**
  `MainActivity.onStart()` fires on every foreground transition
  (not just first launch) and unconditionally calls
  `startForegroundService()`+`bindService()` → `startMesh()` again
  on the same live service. Each re-run stacks a fresh
  `flow.collect{}` on the service's never-recreated `scope`
  (duplicate message/exchange processing — bridge plugins could
  forward the same message to a LoRa radio multiple times) and
  registers a **new** `WifiDirectBroadcastReceiver` without
  unregistering the old one (permanent receiver leak, multiple
  receivers independently driving connection logic from then on).
  High confidence, reproducible by just backgrounding/foregrounding
  the app.
- **Once stopped, `WifiDirectTransport` can never restart.**
  `scope` is a `val CoroutineScope`, built once as a Koin singleton;
  `stop()` calls `scope.cancel()` permanently; `start()` never
  recreates it. Any stop/start cycle (process-surviving service
  restart, or a future mesh on/off toggle) silently kills all
  coroutine-driven transport logic forever afterward.
- **G12's direction-tiebreak bug is still present and unfixed
  here.** `WifiDirectTransport.kt:313-318`: `keepInbound =
  localUserId > peerUserId`, used to veto a session as a
  "duplicate" if its direction doesn't match. This is only valid
  if both directions are ever attempted, which is false in a
  GO↔client group (only the client dials). When userId-ordering
  happens to disagree with which side became GO, **both sides veto
  the only session that can exist**, logged indistinguishably from
  a benign dedup ("Duplicate session with … — yielding"). This
  exact bug and mechanism was already found and fixed on the
  sibling `check-online-status-vef1H` lineage (G12: "first-come-
  wins per peer") — it needs to be ported over, not rediscovered on
  a phone a second time.
- `startServerSocket()`/`connectAsClient()` re-run on every
  `CONNECTION_CHANGED` broadcast, including membership-only changes
  from an unrelated third peer joining — kills live accept loops/
  stacks retry loops. `requestPeers()` has no per-device in-flight
  dedup. Priority-peer "persistent link" skips `removeGroup()` but
  nothing re-dials for a second round — the persistence appears
  incomplete on this branch specifically (may be fixed on the other
  lineage; not checked).
- Clean: MAC/deviceAddress usage correctly stays scoped to opaque
  OS connection targets throughout — the "MAC is never identity"
  invariant holds. BloomFilterData OOM handling and frame-length
  bounds checks are solid.

### 4.4 Bridge plugins (Meshtastic, MeshCore) and plugin framework

- **MeshCore BLE client never negotiates a larger MTU, but its own
  frames routinely exceed the default 20-byte MTU payload.** No
  `requestMtu` call anywhere in `MeshCoreBleClient.kt` (unlike the
  sibling `MeshtasticBleClient.kt:121`, which does call
  `g.requestMtu(517)`). `MeshCoreFrames.kt` builds frames up to 127
  bytes (`MAX_TEXT_BYTES = 120`). Outbound text beyond roughly 14
  characters is silently truncated/dropped by the BLE stack with no
  surfaced error — `writeFrame`'s `Boolean` return only means "the
  GATT write was queued," not "it transmitted correctly." The
  file's own doc comment acknowledges the gap but treats it as a
  future concern; given `MAX_TEXT_BYTES=120`, it's the common case
  today. High confidence.
- **Bridged broadcast messages get a fresh random UUID every
  decode, so Rumor's dedup can never recognize a replayed/
  retransmitted radio frame as a duplicate.** Both
  `MeshtasticBridge.kt:128` and `MeshCoreBridge.kt:150` generate
  `UUID.randomUUID()` per decode rather than deriving an id from
  the radio packet's own id/timestamp+sender. A flaky radio, BLE
  replay, or ordinary LoRa-layer retransmission the firmware
  doesn't dedup produces N distinct "novel" messages in the user's
  feed from one physical packet. Blast radius is bounded (1 hop,
  BRIDGED trust never re-relayed), so it's a local nuisance not a
  mesh-wide issue, but it's a real gap against O5a security
  constraint #5 ("replay protection is the envelope's
  responsibility... a reviewer adding a new envelope must
  explicitly state how"), which the existing broadcast bridges
  never actually satisfied.
- **`DmEnvelope.selfAuthenticating` is extensively documented as
  the O5a constraint-#1 security gate but is never read anywhere in
  production code** — only referenced in a test fixture that also
  never reads it. `GossipEngine.injectBridgedDm` never touches the
  flag; the actual (correct, currently safe) enforcement is an
  emergent property of `injectBridgedDm` only being callable from
  app-internal `PluginContextImpl`, not a check keyed on the
  documented field. Not exploitable today, but it's undeveloped:
  the documented mechanism doesn't exist, so a future refactor that
  starts branching on `selfAuthenticating` could reintroduce
  exactly the downgrade the docs warn against, with nothing pinning
  today's accidentally-safe behavior.
- **Neither bridge reconnects after a BLE disconnect.** `ready` is
  only ever set `true` in `onReady` and `false` in `onDetach()` —
  never on a `STATE_DISCONNECTED` GATT callback. Radio goes out of
  range or reboots → `ready` stays true → outbound writes fail
  silently forever, inbound scanning never resumes; only recovery
  is manually toggling the plugin off/on in settings. Not called
  out in CLAUDE.md's S1/S2 "not done" lists.
- Hand-rolled Meshtastic protobuf length-bounds check
  (`pos + len <= end`) is not overflow-safe against an adversarial
  varint, but is currently masked by the JVM's own array-bounds
  exceptions plus a wrapping `runCatching` at the call site —
  medium-confidence latent defect, not exploitable today, would
  bite if the codec is ever reused without that wrapper (e.g. a
  future USB-serial path).
- Confirmed clean: all six O5a security constraints are enforced
  essentially as CLAUDE.md documents when checked directly against
  `GossipEngine.kt` (envelope lookup is sender-prefix-derived not
  wire-asserted, TOFU pubkey pinning against key-swap, BRIDGE_
  UNSIGNED honored only for LOCAL_BRIDGE, `relay()` unconditionally
  short-circuits for BRIDGED trust before any type logic runs,
  one-envelope-per-prefix really throws on collision,
  `unregisterAllEnvelopes()` really runs before `onDetach`).
  `signWithLocalKey` (G27) and `observeOutboundBridgedDm` scoping
  both check out too.

### 4.5 `:app` UI / ViewModels / Room DB layer

- **DM thread ordering is wrong for two-party threads.**
  `data/MessageDao.kt:27`'s `observeThread` does `ORDER BY
  sequenceNumber ASC`, but `sequenceNumber` is documented in the
  model itself as a per-sender monotonic counter — not comparable
  across two different senders. A heavy user (counter ~500)
  messaging a new contact (counter ~3) gets that contact's replies
  sorted near the top of the thread regardless of actual send time.
  **This is the same class of bug as §2/§4.3 — already found and
  fixed on the `check-online-status-vef1H` lineage** (commit
  `d255bd0`, "G11": switched to `sentAtMs ASC, sequenceNumber ASC`
  as tiebreak) but never ported to `archimedes`. Note for whoever
  ports it: the sibling fix sorts by raw `sentAtMs`, which would
  reopen the G24/O64 message-pinning concern (§4.7 area) if carried
  over verbatim — worth using `displayTimeMs` instead when porting.
  Secondary: the simulator's `InMemoryMessageRepository.
  observeThread` applies no ordering at all (relies on
  `ConcurrentHashMap` iteration order) — the two impls required by
  the four-impl DI rule diverge in behavior, not just structure.
- **Contact rename UI is unwired — O21's one shipped mitigation has
  no entry point.** `ContactsViewModel.setDisplayName` is fully
  wired down to Room, but `ContactsScreen.kt`'s contact menu only
  exposes Message/Auto-relay/Always-save — no rename item or dialog
  anywhere in the UI tree; grep confirms `setDisplayName` is called
  from nowhere in `:app/ui`. Matters specifically for O21 (open,
  display-name spoofing): the local-nickname mitigation exists in
  the data layer but a user can't reach it. Same sibling-branch
  commit (`d255bd0`) already fixed this too, on the other lineage.
- **No Room migrations exist despite four real schema bumps already
  shipped (v5→v9)** — `grep -r "Migration(" app/src/main` returns
  nothing. `fallbackToDestructiveMigration()` is correctly gated to
  debug builds only (matches CLAUDE.md's stated intent — crash
  rather than silently wipe on release). No users have hit this yet
  (`versionCode` is still 1, no release tags exist), but the first
  time a shipped release is followed by a second schema-bumping
  release, every upgrading user's database will throw
  `IllegalStateException: Migration didn't properly handle...` and
  refuse to open — full data loss from the user's perspective,
  worse than the silent-wipe outcome the debug-only gate was
  designed to avoid in dev. High confidence on the fact pattern;
  severity is forward-looking (nothing has shipped).
- **`CLAUDE.md` itself has drifted on this exact number** — its
  coding-conventions section says "Room schema... Current version:
  **5**" but `RumorDatabase.version = 9`. Minor, flagged because it
  speaks to how much has landed since the header was last true.
- Minor: `exportSchema = true` with no `room.schemaLocation` KSP arg
  configured — schema JSON is never written, so there's no history
  to write real migrations against even prospectively; compounds
  the migration gap above. Cosmetic today.
- **Clean / verified:** the four-impl DI rule holds for
  `ContactRepository`/`RouteRepository`/`MessageRepository`
  (including G28's `deleteById`, correctly on the `SentAtMsLintTest`
  allowlist); no direct `.sentAtMs` reads found anywhere in `ui/` —
  screens correctly consume `displayTimeMs`/`receivedAtMs` per G24;
  no unscoped `StateFlow`/coroutine leaks (`.stateIn(viewModelScope,
  WhileSubscribed(5_000), …)` used consistently, no `GlobalScope`);
  no TODO/FIXME markers in `ui/`; `AppModule.kt` wiring resolves
  cleanly by inspection.

### 4.6 `:simulator` and test coverage

- **Confirms and deepens §3** (hop-limit override dead code) —
  found independently by this agent while investigating why
  scenarios 15/16 still fail.
- **`NoBridgedRerelay` assertion is still the weaker post-hoc
  snapshot scan**, not escalated to a live `relayObserver` hook on
  `GossipEngine.relay()` as the original O11 backlog entry
  flagged as a future need. Not an active bug today (the in-memory
  message store doesn't evict mid-scenario), but worth noting:
  `CLAUDE.md`'s G22 entry title ("both escalation paths in place")
  refers only to the *deterministic-replay* escalation, not O11's —
  easy to misread as "O11 done too" on a skim, and it isn't.
- **Wire-parser fuzz harness coverage matches its own claims** —
  every `:core` JSON parser on untrusted bytes has a matching
  `@FuzzTest`. The bridge plugins' hand-rolled binary parsers
  (Meshtastic protobuf, MeshCore frames) are outside Jazzer's scope
  (arguably by design — different threat surface) but are
  defensively `runCatching`-wrapped at their call sites regardless;
  see 4.4's protobuf finding for the one real gap there.
- Scenario 02/03/14/15/16 assertion thresholds are not gamed/
  loosened to hide the failures — verified the JSON files directly
  match what CLAUDE.md documents. No disabled/skipped tests
  (`@Ignore`/`@Disabled`) anywhere in `:core` or `:simulator`.

### 4.7 Wire-format documentation vs. code

- **The domain-tag drift guard (`DomainTagInvariantTest`) itself has
  a gap**: it asserts 20 tags; the code actually emits 21.
  `"rumor-dm-recipient-tag-v1:"` (O53, `SealedSenderKey.kt:42`) and
  `"rumor-room-posting-cert-v1:"` (O89, `RoomPostingCert.kt:77`) are
  both correctly reserved in `RENAMED_FIELDS_NEVER_REUSE.md` but
  neither has a build-time assertion pinning it — exactly the "new
  tag added without updating the allowlist" gap the guard exists to
  catch. `docs/wire-format.md` §6 has the same two omissions,
  contradicting its own claim to be "the union of every tag emitted
  by `:core`."
- **`docs/O79_PROTOCOL_SPEC.md`'s "Known gaps" section is stale and
  wrong** — it says Ed25519→X25519 derivation isn't wired and "a
  compliant client cannot exchange ENCRYPTED room messages... until
  O91 is closed." O91 *is* closed (G29); `AppModule.kt`,
  `SimNode.kt`, and `GossipEngine.handleRoomMessage` all confirm
  real decrypt happens today. This is a re-implementer-facing spec
  (its audience is iOS/Linux/MCU ports), so a stale "you can't
  interop yet" claim has real cost, not just cosmetic staleness.
- **`docs/ROOMS_THREAT_MODEL.md` overstates posting-certificate
  enforcement as currently live.** It describes, in present tense,
  "every honest peer fails the structural check and drops the
  message" for unauthorized channel posts. `RoomPostingCertVerifier`
  exists and is unit-tested but **is never called** from
  `GossipEngine.handleRoomMessage`, `processIncoming`, or anywhere
  in `:app`/`:simulator` — confirmed by grep across all three
  modules. Today, a modified client can post to any channel with no
  certificate and nothing drops it. `CLAUDE.md` row O89 is honest
  about this (`[TODO/CODE]`, "write enforcement only," still open)
  — the threat-model doc just doesn't carry the same caveat and
  reads as a description of shipped behavior.
- Everything else checked out: all 18 `MessageType` SerialNames,
  all 11 `GossipPacket` subclass discriminators, RBSR wire types,
  and the O79 routing-tag/envelope-codec byte formulas all match
  their respective docs exactly. No stale doc-side entries found in
  the other direction (nothing documented that no longer exists in
  code).

---

## 5. Documentation staleness in `README.md`

Not a code bug, but worth a maintenance pass — the README has been
edited unevenly over time and now **contradicts itself**:

- **"Module map" section claims 14 modules** with paths like
  `core/model/`, `plugin/meshtastic/`, `data/` at repo top level.
  The actual `settings.gradle.kts` has exactly 3 Gradle modules
  (`:app`, `:core`, `:simulator`), matching `CLAUDE.md`'s
  "Architecture at a glance." This section hasn't changed since
  2026-05-14 (`git log -S"14 modules"`), i.e. it predates ~2 months
  of active development including the entire bridge/plugin/protocol
  buildout.
- **"LoRa integration" section says both bridges "exist as stubs
  with clearly marked TODOs"** — directly contradicted by the
  README's own "Known limitations" section ~80 lines later, which
  correctly and currently describes them as shipping "working BLE
  codecs... bridge `TEXT_MESSAGE_APP`/channel broadcasts," with
  BRIDGE_VOUCHED wired. Both sections trace back to the same
  original commit (`d502696`); "Known limitations" has been kept
  current across several later sessions, "LoRa integration" never
  was. This directly misleads a reader about the project's actual
  maturity in the section most likely to be read first.
- **"Unit test coverage" table lists 4 test classes.** The repo
  actually has 49 `*Test.kt` files today (`DmEnvelopeRegistryTest`,
  `AppModuleTest`, `Ed25519AsX25519RoundtripTest`,
  `SentAtMsLintTest`, `SourceInvariantTest`,
  `DomainTagInvariantTest`, `TwoTierDuplicateFilterTest`,
  `RoomEndToEndIntegrationTest`, `WireParserFuzzers`,
  `SeedCorpusTest`, and many more never mentioned).

Recommend a pass that either deletes the stale sections or updates
them in the same sweep as whatever session next touches bridge/
module-layout work, so the two halves of the document stop
disagreeing with each other.

---

## 6. Priority order, if picking one thing at a time

1. **§4.2 finding #1 — the ingest dedup-before-sig-verify ordering
   bug.** Live censorship primitive, small fix, highest severity in
   this document.
2. **§1/§4.5 — CI is doubly broken** (missing `gradle.properties` on
   `main`; wrong `:core:test` task name on `archimedes`). Cheap to
   fix, and nothing else in this list gets a real safety net until
   it's green somewhere.
3. **§2 — reconcile the `archimedes` / `check-online-status-vef1H`
   fork** before either goes further; the longer they diverge the
   worse the merge. At minimum, port the G12 direction-tiebreak fix
   and the G11 thread-ordering fix (§4.3, §4.5) onto `archimedes` —
   both are already-solved problems being carried as live bugs here
   only because of the fork.
4. **§3 — `MAX_DIRECT_HOPS`/`MAX_BROADCAST_HOPS` decoupling.**
   Cross-corroborated by two independent agents, plausible one-line-
   constant-change fix, likely resolves the scenario 15/16 (and
   partially 02/03) simulator failures outright.
5. Everything else in §4 — real, verified, but lower urgency than
   the above.

## 7. What I did not get to

- Did not attempt to actually run the test suite locally myself (no
  Android SDK confirmed unavailable in past sessions' containers
  either; did not re-verify that specific claim, though the UI/Room
  agent did successfully run `:core:test`/etc. locally to reproduce
  finding 4.5's task-name bug, so at least plain Gradle invocation
  works in this environment).
- Did not check the `ios/` Swift bridge code for correctness beyond
  confirming its documented state matches its actual (intentionally
  minimal, honestly-labeled) content — no findings there.
- Given the scale of §2 (branch divergence), a full reconciliation
  plan (what to cherry-pick, what conflicts, in what order) is real
  design work I did not attempt — flagging the problem and its
  shape, not solving it.
- This was a research/audit pass, not a fix pass, per the session's
  instructions — nothing above has been fixed, only found and
  written up.


# Round 2 — same overnight session, continued (identity, blocklist, transfer, device-quirks/mode/settings)

Continuation of the research/audit pass above. Still no source-code
changes — findings only. Four more previously-unaudited areas.

## Highest-severity new findings

**Transfer receive path has two remote crash/DoS bugs, and the
"working" feature it protects doesn't actually deliver files to
users at all:**

- `Chunker.kt:76` allocates `ByteArray(metadata.totalBytes.toInt())`
  from attacker-controlled `TransferMetadata` with **no cap anywhere
  in the codebase** (no `MAX_TRANSFER`/`MAX_FILE` constant exists).
  A single crafted `TRANSFER_METADATA` (`totalChunks=1,
  totalBytes=500_000_000`) plus one small `CHUNK` triggers a 500MB
  allocation *before* the SHA-256 integrity check runs — reliable
  OOM/throttle on a typical Android heap. This is the exact O13
  pattern (adversarial size claim → OOM) recurring in a sibling
  subsystem that was never given the same treatment.
- Separately: if a chunk's actual decoded size doesn't match the
  metadata's declared `totalBytes`, `Chunker.kt`'s `copyInto` throws
  an uncaught `IndexOutOfBoundsException` inside the **single**
  `scope.launch { gossipEngine.incomingMessages.collect {...} }`
  coroutine that handles all inbound transfer traffic
  (`TransferAssembler.kt:81-102`). Nothing catches it, nothing
  re-arms the collector — one malformed `CHUNK` from any peer
  silently kills transfer receiving for the rest of the app's
  process lifetime (or crashes the app, depending on Android's
  default coroutine exception handling).
- Even when a transfer *does* complete and pass hash verification,
  `TransferAssembler.assembledTransfers` (the verified-bytes
  `SharedFlow`) **has no collector anywhere in `:app`** — only the
  simulator collects it (to increment a counter). A fully received,
  integrity-verified file is built in memory on-device and then
  simply dropped: never written to disk, never surfaced to the
  user. This is a bigger gap than the already-tracked O14
  (extraction-warning UI) implies — O14 assumes a file arrives and
  needs an extraction dialog; on this branch, no file arrives
  anywhere a user could see it, full stop.

**`IdentityManager.lock()` doesn't zero key material, and is never
called by anything:**

- `lock()` (`app/.../core/identity/IdentityManager.kt:76-79`) just
  sets `_identity.value = null` — it does not zero the old
  `LocalIdentity`'s `privateKeyBytes`, despite `Identity.kt`'s own
  doc comment promising exactly that zeroing, and despite
  `CLAUDE.md`'s G25 audit entry independently repeating the same
  (incorrect) claim as an established fact. `SourceInvariantTest`
  (G25's regression guard) only checks `GossipEngine.kt`, so this
  gap has zero test coverage.
- Worse: grepped the whole app — **`lock()` has no caller anywhere.**
  No Settings menu item, no auto-lock on backgrounding, no session
  timeout. Once unlocked at app start, the private key stays live
  in a `MutableStateFlow` for the rest of the process's life; the
  only way to "lock" the app today is to kill it. This makes the
  zeroing gap above currently unreachable in practice, but also
  means the "lock" feature has no protective effect at all after
  first unlock — including against a future malicious DEX plugin
  per O15/O20's own threat model.
- Related, lower severity: the passphrase itself is held as a JVM
  `String` (not zeroable) through `createIdentity`/`unlock`, and
  `PBEKeySpec.clearPassword()` is never called after PBKDF2
  derivation — the plaintext passphrase, not just the derived key,
  has an unbounded heap-dump recovery window. PBKDF2 iteration
  count (100,000) is roughly 6x below OWASP's current 600,000
  recommendation, though salt handling itself is correct (unique,
  random, no reuse).

## Confirmed: the O62 "no mode-branching until ModeProfile is
finalized" gate has already been violated

`CLAUDE.md`'s O62 backlog row is explicit: "no code that branches
on mode lands until this profile is recorded here." The formal
`ModeProfile`/`UserMode` wire types exist but aren't wired to any
real behavior yet — however, a **parallel, undocumented mechanism**
already does exactly what the gate prohibits. `core/.../policy/
StaticMode.kt` is a plain boolean toggle (manually flipped in
Settings, persisted via `StaticModeManager`) implementing the same
concept as O57's "Static mode," with scattered `if (isStatic)`
branches independently in three unrelated files: BLE advertise/scan
parameters (`BleDiscoveryManager.kt:123-132,205`), scheduler
quantum/cap multiplier (`Scheduler.kt:55,183`), and cache-size/
eviction multiplier (`MessageStore.kt:138-141`). `CLAUDE.md` has
zero references to `StaticMode`/`StaticModeManager` anywhere and
doesn't cross-link it to O57/O62/O80 — it's shipped, invisible to
the backlog that's supposed to gate exactly this pattern, and
already has the "scattered checks throughout the codebase" shape
the gate exists to prevent.

## Blocklist system — one concrete bug, one reinforcement of the §4.2 dedup finding

- **`BlockManagementViewModel.unsubscribe()` leaves orphaned
  blocklist entries permanently.** It calls
  `subscribedBlocklistRepo.delete(publisherId)` directly instead of
  `BlocklistSubscriber.unsubscribe(publisherId)`, which does that
  *and* `blocklistEntryRepo.deleteAllForPublisher(publisherId)`.
  `BlocklistSubscriber` isn't even injected into the ViewModel
  (`AppModule.kt:213` only wires `BlockManager` +
  `SubscribedBlocklistRepository`), so there's no path to the
  correct method today. Net effect: tapping "unsubscribe" removes
  the publisher from the roster but every userId they ever blocked
  keeps silently suppressing messages from your inbox forever, with
  no way to discover or clear it short of re-subscribing to the
  same publisher (which incidentally cleans up via the next
  snapshot apply).
- **The critical dedup-before-signature-verification bug (§4.2 in
  the round-1 section above) applies directly to blocklist
  distribution.** `BLOCKLIST_PUBLISH`/`BLOCKLIST_DIFF` messages go
  through the same `MessageStore.ingest` path as everything else —
  a forged message using a target update's real id, sent first with
  a garbage signature, permanently blackholes that publisher's
  genuine signed blocklist update at a targeted node. This is a
  concrete, high-value instance of the general bug: it's a
  censorship-adjacent primitive (suppressing a moderation update)
  riding a censorship-primitive bug.
- Everything else in the blocklist module checked out clean: inner
  snapshot/diff signature and version-monotonicity verification is
  correctly ordered (nothing applied before full verification), the
  "relay path never sees blocklist" invariant holds exactly as
  documented (single call site, confirmed by grep), and
  `trafficClass` tiering matches the documented INFRASTRUCTURE/
  TRANSFER_SETUP split.

## Smaller findings

- **Device chipset detection is dead code.** `DeviceQuirks.
  isDeviceMediaTek()`/`isDeviceQualcomm()` call
  `System.getProperty("ro.product.board")` — on Android this only
  exposes standard JVM properties, never build props like
  `ro.product.board` (that needs `Build.BOARD`/`Build.HARDWARE`).
  Both detectors always return `false`. The documented "MediaTek
  devices demand to be Group Owner regardless of intent" Wi-Fi
  Direct workaround (`wifiDirectDualRoleRequired`) silently never
  fires on real MediaTek hardware — exactly the "4-year-old budget
  phone" class O33 calls out as a target device. Of 8 documented
  quirk flags in this class, 6 have zero references anywhere else
  in the app; the corresponding behaviors are hardcoded directly in
  transport code instead, bypassing the "centralized registry" the
  class's own docstring claims exists.
- **Cosmetic-only radio duty-cycle slider.** `SettingsScreen`'s
  "Radio duty cycle" slider writes `scanIntervalSec`/
  `sleepIntervalSec` into local ViewModel state only — never
  persisted, never read by `BleDiscoveryManager`, `Scheduler`, or
  anywhere else. A user adjusting it changes nothing.
  `sleepIntervalSec` has no setter at all; permanently 30.
- **Dead battery-optimization warning card.**
  `SettingsState.showBatteryOptimisationWarning` defaults `false`
  and nothing in the ViewModel ever sets it `true` — the warning
  card and its otherwise-correctly-wired "Fix this" button are
  permanently unreachable. Looks like a half-finished O33 feature
  whose trigger (a foreground-service-killed detector, presumably)
  was never implemented.
- Confirmed clean: InboxPolicy, Plugins, Transfers, and Logs
  settings screens are all genuinely wired with no dead state or
  TODO markers.

## Updated priority list (folds into the round-1 list above)

Given the severity found in this round, **the transfer-receive OOM/
crash (this section, item 1) belongs at the same priority tier as
the round-1 dedup-before-sig-verify bug** — both are remotely
triggerable, no-authentication-required denial-of-service/
censorship primitives reachable over the mesh from any peer. Suggest
treating both as a matched pair for the first hardening pass, since
they're structurally the same class of mistake (trusting
attacker-supplied size/ordering data before the integrity check that
was meant to gate it) recurring in two different subsystems —
worth a single sweep across the codebase for the same pattern
elsewhere (any other `ByteArray(untrustedLength)` or
"record-before-verify" ordering) rather than fixing these two in
isolation.


# Round 3 — same overnight session, continued (keyword filters, online-status, plugin lifecycle, O80 mode profile)

Continuation of the same research/audit pass. Still no source-code
changes. This closes out the remaining previously-unaudited corners
of the codebase this session set out to cover.

## O67 keyword filter vs. its sibling, the blocklist system: same
distribution bug, worse UI gap

- **`KeywordFilterMatcher.match()` is never called by anything
  outside its own tests.** Grepped `:app`/`:core` — no
  `ThreadViewModel`, message-render path, or any UI file references
  it; there's no `KeywordFilterViewModel` or filter-list screen at
  all. So even where a filter list is subscribed and stored, it has
  **zero effect on what a user sees** — WARN/BLOCK never fires. This
  is a stronger statement than CLAUDE.md's "UI not built yet" note:
  the matcher itself has no consumer, not just no settings screen.
  One incidental upside: because it's never invoked, it's also never
  applied at relay time, so it doesn't violate the "filtering is
  display-only" design rule — but only by absence, not by any
  enforced gate.
- **Inherits the exact §4.2 dedup-before-sig-verify vulnerability
  from `MessageStore.ingest`, confirmed independently.**
  `KEYWORD_FILTER_PUBLISH` is relayed identically to
  `BLOCKLIST_PUBLISH`/`BLOCKLIST_DIFF` and goes through the same
  `ingest()` path. Same attack shape: forge a message using a
  target list-update's real id with a garbage signature, send it
  first, the id gets marked "seen" permanently even though the
  forgery is rejected — the genuine signed update later arrives and
  is silently dropped as a duplicate. This is now confirmed across
  three independent subsystems (general messages, blocklist,
  keyword filters) all riding the same one bug in `MessageStore`,
  which reinforces round 1's recommendation to fix it once at the
  source rather than per-subsystem.
- **Good news, for contrast:** `KeywordFilterSubscriber.unsubscribe()`
  correctly deletes from both repos in one method — it does **not**
  repeat the blocklist system's UI bug (round 2, `Block
  ManagementViewModel` bypassing `BlocklistSubscriber.unsubscribe()`
  and calling the wrong repo directly), simply because no keyword-
  filter UI exists yet to make that mistake. Worth a note for
  whoever eventually builds that UI: call `KeywordFilterSubscriber.
  unsubscribe()`, not the repos directly, to avoid reintroducing the
  exact bug already found in the sibling system.
- Matching logic itself has no ReDoS risk (substring/word-boundary
  scans only, no user-authored regex) and consistent case-folding.
  One latent performance issue for whenever it gets wired in:
  `WORD_CI` recomputes `text.lowercase()` once per filter entry
  instead of once per match call — O(entries × lists) redundant
  allocations. Zero live impact today since it's unreachable.
- Four-impl DI parity is clean; O81 (image classifier) genuinely has
  no code yet, matching its honest `[TODO/CODE]` backlog status —
  not a stale/regressed feature, just not started.

## O80 `ModeProfile.kt` is real, correct, well-tested — and entirely
disconnected

Confirms and sharpens round 2's `StaticMode`-vs-O62-gate finding.
`ModeProfile.evaluate` (rule matching, `And`/`BatteryAbove`/
`ChargingIs`/`ScreenIs`/`NetworkIs`/`TimeOfDay`/`WeekdayIn`
conditions) is correctly implemented — first-match-wins, midnight-
wraparound time ranges handled properly, 8 tests covering the real
edge cases including the exact wraparound boundary. But it has
**zero runtime call sites anywhere** — not constructed, not wired
into `AppModule.kt` or `MeshService`, no battery/screen/charging
listener feeds it a `DeviceState`. The class's own KDoc says
explicitly that "the Android orchestrator... is responsible for
assembling a fresh DeviceState... and calling evaluate" — that
orchestrator doesn't exist. So the actual state of play is: the
correct, gated, single-source-of-truth mode engine (O80) sits
unused right next to the ungated, undocumented `StaticMode` toggle
(round 2) that's already live in three files. Whoever picks this up
should wire O80 in and delete `StaticMode`, not the reverse.

## `OnlineStatusTracker` — correct logic, but the same unbounded-
growth bug as round 1's BreadcrumbCache/TopologyTracker, plus a
false claim in `README.md`

- Boundary logic (`ONLINE`/`RECENTLY`/`AWAY` at the 30-minute cutoff)
  and locking are both actually correct here — consistently `<=`
  on both sides of the comparison, and every mutating/reading method
  is properly `synchronized`, unlike `TopologyTracker`/`NeighborStore`
  (round 1), which had the read-modify-write race. Good, no finding.
- **But `lastSeen` (the backing map) is never pruned.**
  `currentSnapshot()` only *filters* on read, it doesn't remove
  stale entries from the map itself, and `recompute()` maps over
  every entry ever recorded — including via `mergeRemoteStatus`,
  called on every gossip exchange with a peer's second-hand presence
  table. **`README.md:335` explicitly claims otherwise** ("Peers
  older than 30 minutes are pruned from the in-memory map on each
  update cycle") — this is factually wrong; nothing is ever removed.
  Net effect: a node accumulates an unbounded `userId → timestamp`
  entry for every distinct user it has ever heard about, directly or
  secondhand, network-wide, for the life of the process — the same
  "documented prune that never fires" pattern round 1 found in
  `BreadcrumbCache`/`TopologyTracker`, except here the README
  actively asserts the (non-existent) protective behavior exists,
  which is worse than silence.
- **Secondary, medium-confidence:** `mergeRemoteStatus` accepts an
  arbitrary peer-supplied timestamp map and adopts any value greater
  than what's on file, with no check that the timestamp isn't in the
  future. A malicious peer can inject a far-future timestamp for any
  userId, making that user appear permanently ONLINE to everyone
  downstream in the gossip chain — worth a `ts <= now` bound.

## `PluginRegistry`/`PluginCatalog` — disable is real enforcement,
but has a genuine race and an unchecked outbound path

- Disabling a plugin **does** stop it from receiving future
  messages — `unregister()` really removes it from the dispatch
  list and tears down its scope; this is not a UI-only toggle.
- **Race at the disable boundary:** `MeshService`'s incoming-message
  collector takes a snapshot of the plugin list and calls each
  plugin's `onMessageReceived` in a `forEach`, with no coordination
  against a concurrent `unregister()` call from the UI thread. A
  plugin captured in the snapshot just before being disabled can
  still get `onMessageReceived` called on it after its own
  `onDetach()`/resource-teardown has already run — behavior then
  depends on that plugin's own resilience to being called post-
  teardown (not separately audited per-plugin).
- **Outbound injection has no registration check at all.**
  `PluginContext.sendMessage` → `GossipEngine.injectFromPlugin` runs
  on `GossipEngine`'s own scope, not the plugin's — so a plugin's
  in-flight `sendMessage()` call racing a disable can still land the
  message on the wire, since cancelling the plugin's own scope has
  no effect on a call that was never running inside it. A "disabled"
  plugin can still get one more message out if the timing lines up.
  Both race findings are medium-high confidence on mechanism; exact
  blast radius depends on per-plugin `onMessageReceived` behavior
  under teardown, which wasn't separately re-audited here.

---

## Session close-out

This was originally scoped as a single research/audit pass; it
expanded to three rounds (13 background research agents total)
covering: crypto/protocol core, sync/scheduling/routing, transport,
bridges, UI/Room, simulator/tests, wire-format docs, identity/
passphrase, blocklist, transfer/chunker, device-quirks/mode/
settings, keyword filters, online-status, and plugin lifecycle —
plus the repo-level findings (CI, branch divergence, README
staleness; §0 covers the canary convention for future instances). That's coverage of essentially every
major subsystem in `:core` and `:app`, and the `:simulator` harness
that tests them. **No source code was changed at any point**, per
the session's instructions — everything above and in rounds 1-2 is
a finding to triage, not a commit to review.

**A pattern worth naming explicitly, since it recurred independently
across three unrelated subsystems this round alone:** "shipped and
marked complete in CLAUDE.md, but has zero runtime call sites" shows
up for `ModeProfile`/O80 (this round), `KeywordFilterMatcher` (this
round), `TransferAssembler.assembledTransfers` (round 2), and
`BreadcrumbCache.pruneOld()`/`TopologyTracker.pruneStale()` (round
1). None of these are hard bugs to fix individually — they're all
"the leaf function is correct, nobody calls it" — but four
independent instances of the same shape suggests a process gap
worth naming on its own: something about how this project's
backlog gets marked "done" isn't verifying the feature is actually
reachable from a running app, only that the underlying code exists
and passes its own unit tests. Worth a dedicated "is anything else
DI-wired-but-uncalled" sweep before the next release push — the
four found here were incidental discoveries during otherwise-
targeted audits, not the product of looking for this pattern
specifically, so there are likely more.

**What genuinely wasn't covered, for a future session:** a full
reconciliation plan for the branch fork (§2 in the main section
above) is real design work, not a quick audit finding. Instrumented/
on-device UI tests were out of scope for a code-reading pass by
design. The `ios/` Swift bridge was confirmed to honestly match its
documented (intentionally minimal) state and wasn't re-audited
beyond that. Nothing in fastlane/, the release-check workflow logic
itself, or the F-Droid reproducibility checklist was code-reviewed
this session.

---

## Prior session history (kept below, unedited)


> Per `docs/MULTI_INSTANCE_COORDINATION.md` async-handoff section.
> This snapshot covers five autonomous sessions stacked.

## Branch state

`claude/practical-archimedes-wmySm`. **Latest commits at write:**
`5da8065` (O53 tagKey zero-fill source invariant), `bd95b70`
(O53 composeDirect sealed-sender stamp + sim wire test), on top of
`0745c3a`. User is asleep; another instance may pick up here.

## Most-recent autonomous session (this writeup)

**Wired O53 compose-side sealed-sender stamp.** Two focused commits
on top of `0745c3a`:

| Commit | Result |
|---|---|
| `bd95b70` O53: composeDirect stamps `_ext.t` | `GossipEngine.composeDirect` derives the per-contact tag key via `SealedSenderKey.derive`, computes `SealedSenderTag.tagFor(tagKey, msg.id)`, stamps `_ext.t = base64(tag)` alongside the existing plaintext `recipientId` (coexistence phase). tagKey is zeroed in finally. Bridged DMs skip the stamp because the recipient pubkey is foreign-network, not Rumor Ed25519. `_ext` is excluded from `signableBytes` so the outer Ed25519 sig stays valid. Simulator pin `SealedSenderTagWireTest` (2 tests) proves the recipient (independently deriving the per-contact key from the symmetric HKDF info) computes a matching tag for the same messageId — the property a relay's pre-match table will exploit once the receive-side precompute lands. Different recipients on the same sender stamp different tags (anti-cross-match property). |
| `5da8065` O53: pin tagKey zero-fill as a source invariant | New `SourceInvariantTest.composeDirect zeros sealed-sender tagKey after use` brittle-regex check. Same shape as the existing G25 (`ephemeral.privateKeyBytes.fill(0)` / `sharedKey.fill(0)`) invariants. A future refactor that drops the fill is caught at unit-test time instead of silently leaking the per-contact tag key on the heap. |

`:core:jvmTest` + `:simulator:test` both green at HEAD. CLAUDE.md O53
row updated; remaining open work is receiver-side precompute store +
relay routing match (both ~bounded, ~similar in shape to compose side).

## Previous autonomous session (O91 step 2 of 2)

**Closed O91 step 2 of 2 — Ed25519 → X25519 derivation wired into
the production DM path.** One focused commit that turns the bug pin
into a real fix, plus the doc updates:

| Change | Result |
|---|---|
| `PlatformCrypto` expect/actual surface | Added `ed25519ToX25519PrivateSeed` + `ed25519ToX25519Public` to the expect object. JVM actual delegates to the existing `Ed25519ToX25519` math (no change to the well-tested primitive). iOS actual throws `NotImplementedError(BRIDGE_MISSING)` — same posture as the other iOS crypto primitives until the Swift bridge lands. |
| `CryptoManager` wrappers | Two thin commonMain entry points so callers in `:core` and `:app` reach the conversion via the same façade they already use for sign / x25519Agreement / aesGcm. |
| `GossipEngine.composeDirect` | Converts the recipient's Ed25519 pubkey before `x25519Agreement`. Mirror of the receive-side conversion. |
| `ThreadViewModel.decryptPayload` | Converts the local Ed25519 seed before `x25519Agreement`. Zeros the derived X25519 private in a finally block (the conversion produces a fresh buffer per call, so it's safe to scribble over). |
| `RoomSubscriptionProvider.localX25519StaticPrivate` | Returns the derived static from the unlocked identity in both `AppModule` (Android) and `SimNode` (simulator). Was returning `null` before, which meant ENCRYPTED rooms matched at the tag layer but couldn't decrypt; now they decrypt end-to-end. |
| `GossipEngine.handleRoomMessage` | Wraps the codec call in a `try { … } finally { xPriv.fill(0) }` so the freshly-derived private doesn't outlive the decrypt. The contract docstring on `RoomSubscriptionProvider.localX25519StaticPrivate` was updated to document that implementations must return a fresh buffer each call. |
| `Ed25519AsX25519RoundtripTest` | Reframed: the primitive-gap pin stays (primitive contract is "X25519 in"; a future regression that auto-converts inside the primitive breaks genuine X25519 callers and trips the assertion), and a new `wired fix` test exercises round-trip + AEAD through the two new conversion wrappers. The wired-fix test is the regression nudge for any future change that drops the conversion at a call site. |
| `CLAUDE.md` | O91 marked `[CLOSED]` → G29; counts refreshed (16 PART, 71 total open). |

`:core:jvmTest` + `:simulator:test` both green at HEAD. `:app:testDebugUnitTest`
not runnable in this container (no Android SDK); the change to
`ThreadViewModel.decryptPayload` is small and follows the existing
finally-zeroize pattern verbatim, and the change to `AppModule` is
the same six-line shape as the simulator's `SimNode` change which
the simulator tests cover.

## Previous autonomous session

Two commits stacked on top of `35bd2dc` (the previous session's
G22 scenario-survey extension):

| Commit | Row | Result |
|---|---|---|
| `a922947` | Sustaining KDoc — Android app entry surfaces | Top-level KDoc on `RumorApp` (Koin startup point + cross-ref to G6 verify test), `MainActivity` (lifecycle wiring: onCreate eager-resolve to surface Koin misconfig as a friendly error screen, onStart bind to `MeshService` when identity unlocked, onStop unbind), `RumorDatabase` (schema-versioning policy + release-vs-debug `fallbackToDestructiveMigration` divide + the enum-name on-disk-schema rule), and `Converters` (the `.name`/`valueOf` round-trip rule — renaming a `MessageType` / `ContentType` / `BlocklistMode` / `TransferStatus` / `TransferDirection` value breaks read-back of existing rows; same constraint as wire-format strings in `docs/RENAMED_FIELDS_NEVER_REUSE.md`). |
| `091dd33` | Sustaining KDoc — four more small public surfaces | Top-level KDoc on `BootReceiver` (post-reboot/post-upgrade auto-restart rationale + locked-identity graceful no-op), `FilterSubscriptionRepository` (records the sibling-pair shape with `KeywordFilterListRepository` for O67 publish/subscribe), `AssembledTransfer` (flags that bytes are byte-integrity-verified but NOT content-validated — extraction still has to validate per O14/O28/O83), and `RoomSubscriptionMode` (OPEN vs ENCRYPTED dichotomy and the note that PASSWORD/INVITE/CLOSED all fold into ENCRYPTED at the wire layer; the join-time key distribution is what differs). |

All commits pushed. `:core:jvmTest` + `:simulator:test` green at every step.

This session also installed JDK 17 (`openjdk-17-jdk-headless`) into the
container so the gradle toolchain pinned in `core/build.gradle.kts` /
`simulator/build.gradle.kts` (`jvmToolchain(17)`) could resolve — the
session's Ubuntu 24.04 base image ships only JDK 21. No project files
were changed for this; just the container's package set.

## Previous session (the five-commit sustaining run)

Five commits stacked on top of the previous session's `25e3f11`:

| Commit | Row | Result |
|---|---|---|
| `475c080` | DomainTagInvariantTest extension | Drift guard widened from 8 to 21 wire-format domain tags. Catches a silent rename of `rumor-msg-v1:` / `rumor-hello-v1:` / `rumor-hello-v2:` / `rumor-blocklist-v1:` / `rumor-blocklist-diff-v1:` / `rumor-bridge-vouched-v1:` / `rumor-prekey-v1:` / `rumor-room-create-v1:` / `rumor-room-invite-v1:` / `rumor-room-action-v1:` / `rumor-rbsr-v1:` / `rumor-o76:` (plus the original 8) at build time. |
| `5392265` | docs/wire-format.md §6 | Inventory table was missing 13 tags actually emitted by `:core` and still listed the retired `rumor-identity-rotation-v1:` as active. Split into Signatures / HMAC-HKDF-AEAD / Retired subsections; cross-referenced against the new drift guard. |
| `7863fcd` | CLAUDE.md counts refresh | Stale "Counts as of this writing" line (off by 1 on DECISION, off by 1 on Total). Now: 17 PART · 15 DECISION · 40 TODO. Total open rows: 72. |
| `17a59c5` | Sustaining KDoc — repository interfaces, Identity, Route | Top-level KDoc on `MessageRepository`, `ContactRepository`, `RouteRepository`, `BreadcrumbRepository`, `BlockEntryRepository`, `SubscribedBlocklistRepository`, `BlocklistEntryRepository`, `ScheduledMessageRepository`, `TransferRepository`, `ChunkRepository` + `Identity.LocalIdentity` / `IdentityProvider` + `Route` / `Breadcrumb` data classes. Records the "three impls in tree, one contract" pattern and cross-references the relevant G#/O# rows. |
| `abfcc39` | Sustaining KDoc — blocklist module + RumorLog primitives | Top-level KDoc on `BlocklistVerifier`, `BlocklistPublisher`, `BlocklistSubscriber`, `BlocklistGossipBridge` + `LogLevel` / `LogEntry` / `LogSink`. Records the sibling-pattern shape (the blocklist publish/subscribe stack mirrors the O67 keyword-filter publish/subscribe stack). |

All commits pushed. `:core:jvmTest` green at every step.

## Previous session (overnight + post-wake) below this line

**Wall clock:** ~20:55 UTC 2026-06-05.

## Session totals (autonomous-overnight + post-wake)

- **Autonomous-overnight (user asleep):** 27 commits — table below.
- **Post-wake (user requested "continue, as you were"):**
  - `a58d428`  Sim scenario for O79 ROOM_MESSAGE (3 tests). Caught a
    real bug: unconditional `emitToInbox` after type handlers was
    making non-subscribed nodes surface room messages locally. Fixed
    by gating on `msg.type != ROOM_MESSAGE`.
  - `cf36ddf`  CLAUDE.md sync — record the sim scenario + bug fix.
  - `8f2454a`  **O91 filed** — real bug found while researching the
    Ed25519/X25519 question raised during O79 work. Production DM
    crypto between two real Rumor users does NOT round-trip (Ed25519
    bytes silently treated as X25519 produce different shared secrets
    on the two sides). `Ed25519AsX25519RoundtripTest` pins the broken
    state; assertion flips when fixed.
  - `9b57d99`  Sustaining: explicit imports for Base64Codec +
    MultiRecipientEnvelope in GossipEngine (replaces 5 inline FQNs).
  - `d24a7e1`  Robustness sim test for malformed routing tags.
  - `2b4ec0b`  `docs/O79_PROTOCOL_SPEC.md` — byte-level spec for an
    alternative implementation (iOS / Linux relay / etc).

## Autonomous-overnight commits (chronological)

27 commits, all on `claude/practical-archimedes-wmySm`, pushed.
Each commit message includes an explicit **Rollback** note per the
overnight-instructions request that experiments be documented for
clean reversal.

| Commit | Row | Result |
|---|---|---|
| `af48fef` | CLAUDE.md decisions | Multi-recipient envelope chosen for ENCRYPTED rooms; O89 ratchet design problem collapsed; O90 (thread + mention `_ext` fields) filed. Names reserved in `docs/RENAMED_FIELDS_NEVER_REUSE.md`. |
| `39c636b` | **O90 → PART** | `_ext.replyTo` + `_ext.mentions` accessors with copy helpers (`withReplyTo`, `withMentions`). 11 tests pin field names + wire format + round-trip + tamper. |
| `d7b17fa` | **O79 wire substrate** | `MultiRecipientEnvelope` + `KeyWrap` data classes + signable bytes function. 10 tests pin sig scope (relay can't extend/trim/permute recipients). |
| `42c27a1` | **HKDF-SHA-256** | RFC 5869 implementation over the existing `HmacSha256` primitive. 10 tests pin RFC 5869 vectors (case 1, case 3) + length/edge guards + domain-separation property. |
| `ca77c28` | **O79 envelope codec** | `MultiRecipientEnvelopeCodec.encrypt` / `.decrypt` pure functions composing X25519 + HKDF + AES-256-GCM + Ed25519. 9 tests cover single + multi-recipient round-trips, non-recipient drops, tampered sig / appended slot / tampered ciphertext rejections, per-message FS (same input → different ciphertext). All key material zeroed in finally blocks. |
| `ab2abae` | **O79 routing tag derivation** | `RoomRoutingTag.openRoomTag` (SHA-256 prefix) and `.encryptedRoomTag` (per-message HMAC) + `.deriveEncryptedRoomRoutingKey` (HKDF). 11 tests pin behavior + 4 domain tag separations. |
| `b441b8a` | Reserve domain tags | 5 new entries in `RENAMED_FIELDS_NEVER_REUSE.md` (envelope sig scope, wrap-key HKDF info, OPEN routing prefix, ENCRYPTED routing HMAC prefix, routing-key HKDF info). |
| `44c6f44` | **O79 receiver-side matcher** | `RoomTagMatcher.match` identifies which subscribed room an inbound tag is for. OPEN check runs first (cheaper); ENCRYPTED on miss. 10 tests cover all paths. |
| `ae4e73f` | **O79 MessageType + relay** | `MessageType.ROOM_MESSAGE` enum entry. Traffic class follows BROADCAST/DIRECT/BRIDGE_VOUCHED content-driven branch. Relay alongside BROADCAST; clampTtl to MAX_BROADCAST_HOPS. 2 new traffic-class tests + exhaustiveness allowlist updated. |
| `bc04b79` | CLAUDE.md O79 sync | Row's "Done" / "Not done" split updated to reflect protocol-layer completeness. |
| `88d2bd0` | **O90 compose-side** | `GossipEngine.composeBroadcast` and `composeDirect` accept optional `replyTo: String?` and `mentions: List<String> = emptyList()` params. Default-empty fallback preserves existing call-site behavior; sim tests recompile cleanly. |
| `df64401` | CLAUDE.md O90 sync | Row updated; remaining open item narrows to UI consumption. |
| `6c9abbe` | **O79 RoomSubscriptionRepository** | Pure-interface contract + `RoomSubscription` validating data class + `RoomSubscriptionMode { OPEN, ENCRYPTED }`. 7 tests cover routing-key-length init guards + content-equality semantics. Impls not added; that's app/sim wiring for later. |
| `f32ff3d` | HANDOFF refresh | Comprehensive session summary. |
| `d2d605b` | Sustaining: MessageStore + MetricsSnapshot class-level KDoc | Top-level docs for two classes whose fields were documented but the surrounding class context wasn't. Pure-additive. |
| `af23634` | Sustaining: BlockEntry + SubscribedBlocklist + BlocklistEntry + Contact KDoc | Second docs pass. |
| `ae6d46f` | Sustaining: OnlineStatusTracker + TopologyTracker KDoc | Third docs pass — covered the two ingress paths (firsthand vs secondhand), windowed presence model, the two routing surfaces (durable [routeRepo] + in-memory [neighborStore]), and the latency-NOT-used-for-routing decision. |
| `1cea6e8` | **O79 composeRoomMessage + _ext.rt accessor** | GossipEngine helper that wires every protocol piece into one call: routing tag + envelope codec + wrapping into RumorMessage. Empty recipients → OPEN-mode (signed plaintext); non-empty → ENCRYPTED via codec. `_ext.rt` accessor + 7 tests. Field name `rt` reserved forever. |
| `73de7b6` | **O79 end-to-end integration test** | RoomEndToEndIntegrationTest (5 tests) exercises the full compose → wire → match → decrypt path for both OPEN and ENCRYPTED modes. Catches integration drift between unit-tested primitives — if any individual unit test passes but protocol-level composition breaks (e.g. HKDF info string typo on one side), this suite fails. |
| `<current>` | Final HANDOFF refresh | This summary. |

## What this autonomous session accomplished

**O79 protocol primitives are complete and end-to-end tested.**
The wire types, encrypt + decrypt codec, routing tag derivation
(both OPEN and ENCRYPTED), receiver-side tag matcher, MessageType
+ traffic class + relay + clampTtl, and the subscription repository
interface all landed.

**O90 (thread + mention metadata) is complete except UI.**
Substrate accessors, compose-side wiring, never-reuse name
reservation all done.

**O89 collapsed substantially.** The decision to use multi-
recipient envelopes for ENCRYPTED rooms means there's no shared
room key to ratchet, no rotation cascade, no catch-up-after-
missing-rotation flow. O89 reduces to just write-enforcement via
posting certificates.

**Apache 2.0 LICENSE + project signing key policy + Rooms threat
model** were committed in the prior chat session (`9950316`).

## What's left for O79

Bounded code work, no design decisions remaining:

- ~~`composeRoomMessage` helper~~ **shipped in `1cea6e8`.**
- **Receive-side dispatch in `processIncoming` for ROOM_MESSAGE:**
   - Pull `_ext.rt`; call `RoomTagMatcher.match` against the
     subscription cache
   - On match: decrypt via codec (ENCRYPTED) or pass through (OPEN)
   - Emit plaintext to inbox
- App-layer `RoomSubscriptionRepositoryAdapter` (Room/SQLite) +
  `:simulator` in-memory stub + DI wiring (interface contract is
  in place in `6c9abbe`)
- Events-derived membership projection cache (the
  `RoomCreate + invites/joins/leaves/bans` replay) — needs for
  the sender-side to enumerate recipients at composeRoomMessage
  call time
- End-to-end integration test of compose → wire → match → decrypt
  is **shipped in `73de7b6`** — the integration test runs without
  GossipEngine, so the substrate is proven independently of the
  receive-branch wiring.

UI work (member roster, "Alice joined" surfaces) is gated on the
above.

## What was rejected during the autonomous session

- **Implementing a Room app-layer repository adapter** — would
  require Room schema migration (current v8) + AppModule wiring
  changes. Scope creep beyond "pure-interface contract" for what
  is fundamentally a single-user-decision question (what fields
  to persist; the user is asleep). Repository INTERFACE landed;
  impl is the next session's pickup.

- **`GossipEngine.composeRoomMessage` helper** — needs
  membership-list enumeration which depends on the events-derived
  membership projection cache, which doesn't exist yet. Could be
  built as a free-standing helper that takes a recipient list
  directly, but felt premature without the membership story
  settled. Substrate primitives are in place; helper is a clean
  follow-up.

- **iOS / desktop platform actuals** — gated on Mac access per
  `docs/IOS_PORT_PLAN.md`. Not attempted.

- **UI work** — gated on visual verification; honest "cannot do
  without you driving" item.

## Backlog state at handoff

- **Counts:** still need a refresh; rows that moved this session:
  - O79: PART (substrate → "protocol primitives complete")
  - O89: tag unchanged but scope collapsed
  - O90: TODO/CODE → PART → PART (substrate + compose-side wired)

- **Completed gaps:** G1–G28.
- **Open rows touched this session:** O79, O89, O90.
- **New file count:** 11 production source files, 7 test source
  files, 3 doc files. ~3500 lines of net additions.

## Tooling status

- `:core:jvmTest` — green at HEAD (`6c9abbe`).
- `:app:testDebugUnitTest` — green.
- `:simulator:test` — green (required `:simulator:clean` once
  after the `composeBroadcast` signature change to refresh JVM
  bytecode; documented in commit `88d2bd0` message).
- Test count grew by ~60 across the session.

## What's NOT updated and may be stale

- The "Counts as of this writing" header in CLAUDE.md still shows
  pre-session numbers; should refresh when convenient. Probably
  ~16 PART · 14 DECISION · 39 TODO at the current head.
- iOS actuals still throw `NotImplementedError`.
- No `RoomSubscriptionRepository` impl wired into AppModule.

## Canary

"By Order Of The High Magnate" used on every commit message this
session.
