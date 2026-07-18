# Handoff — overnight research/audit session (2026-07-18), continued — round 3 (small)

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

Closing out the two items round 2 flagged as "not re-verified" rather than
leaving them open-ended. No source-code changes.

- **PBKDF2 iteration count is still 100,000** (`CryptoManager.kt:142`,
  `pbkdf2HmacSha256(..., iterations = 100_000, ...)`), and
  `PlatformCrypto.pbkdf2HmacSha256` still never calls `spec.
  clearPassword()` (`PlatformCrypto.kt:109-113`) — the `PBEKeySpec`'s
  internal char-array copy of the passphrase sits unzeroed after use.
  Confirmed both parts of the original finding. Note the deeper limit
  underneath: even a `clearPassword()` fix only clears the `PBEKeySpec`'s
  internal copy — the `passphrase: String` parameter itself is a JVM
  `String` (immutable, interned, unclearable) all the way from wherever
  the UI first reads it. Closing that fully means threading `CharArray`
  through the passphrase-entry UI down to this call, not just a one-line
  fix here; the `clearPassword()` call is still a cheap, real, partial
  improvement worth doing on its own.
- **The `PluginRegistry` disable-boundary race is real, mechanism
  confirmed.** `plugins` is a `CopyOnWriteArrayList` (`PluginRegistry.
  kt:36`) — thread-safe against `ConcurrentModificationException`, but
  its iterators snapshot the backing array at iteration start. A plugin
  captured in that snapshot by `onMessageReceived`'s `plugins.forEach`
  (line 88) will still have `.onMessageReceived()` invoked on it even if
  `unregister()` (line 71-79, which cancels its scope and runs
  `onDetach()`) completes concurrently on another thread — the object
  doesn't stop existing just because it left the live list. **Mitigating
  factor worth noting** (not visible from a code-shape read alone): the
  call is wrapped in `try/catch` (line 89-92), so a post-teardown plugin
  throwing doesn't crash the app or the collector — worst case is
  whatever a specific plugin does when called on already-torn-down
  state, logged and swallowed. Confirms the finding but narrows "medium-
  high confidence, blast radius depends on per-plugin behavior" to
  "confirmed mechanism, bounded blast radius by the existing catch."

Both are now fully closed out from "flagged, not verified" to "confirmed
against current code" — nothing left dangling from this session's list.

---

# Handoff — overnight research/audit session (2026-07-18), continued — round 2

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

Continuation of the session above. Still no source-code changes. This
round re-verifies, against current code, a batch of findings from the
2026-07-13 audit rounds recorded later in this file (search "Round 2" /
"Round 3" below) that the round-1 write-up above didn't get to. Same
method as round 1: every claim below was re-checked against the actual
file today, not taken on the old write-up's word — five days and the
whole archimedes merge sit between that audit and this one, so "still
true" is real information, not a repeat.

## Confirmed RESOLVED
- **`StaticMode.kt`/`StaticModeManager`** (the undocumented boolean
  toggle that violated O62's "no mode-branching until ModeProfile lands"
  gate, with scattered `if (isStatic)` checks in `BleDiscoveryManager`/
  `Scheduler`/`MessageStore`): **no longer exists anywhere in the tree**
  (`find . -iname "StaticMode*.kt"` — zero hits). Consistent with G39's
  O80 mode-orchestrator work replacing the old binary Settings toggle
  with the 4-way selector — looks like the cleanup happened as part of
  that, even though G39's own entry doesn't explicitly mention deleting
  it. Good outcome either way.

## Confirmed STILL OPEN (re-verified against current code)
- **`TransferAssembler.assembledTransfers` has no collector anywhere in
  `:app`.** Confirmed by grep: the only `.collect` on this `SharedFlow`
  in the whole tree is `simulator/.../SimNode.kt:141`, which just
  increments a counter for scenario scoring. A fully received, hash-
  verified file transfer is assembled in memory on-device and then
  dropped — never written to disk, never surfaced anywhere a user could
  see it. This is a bigger gap than O14 (extraction-warning UI) implies;
  O14 assumes a file arrives and needs a dialog, but on current `main`
  no file ever reaches anywhere visible, regardless of O14. Worth its
  own backlog row distinct from O14/O108/O109 (which cover the sender-
  side resource-cap half of this subsystem, not "does a completed
  transfer go anywhere").
- **`BlockManagementViewModel.unsubscribe()` still calls
  `subscribedBlocklistRepo.delete(publisherId)` directly** (line 106)
  instead of `BlocklistSubscriber.unsubscribe()`, which is the only path
  that also does `blocklistEntryRepo.deleteAllForPublisher(publisherId)`.
  Confirmed `BlocklistSubscriber` still isn't what's injected/called
  here. Net effect unchanged from five days ago: tapping "unsubscribe"
  drops the publisher from the roster but every userId they ever blocked
  keeps silently suppressing your inbox forever, with no UI path to
  clear it short of re-subscribing to the same publisher.
- **`OnlineStatusTracker.lastSeen` is still never pruned, and
  `README.md:336` still asserts that it is.** Confirmed both sides: the
  backing `mutableMapOf<String, Long>` (line 44) has no `.remove()`/
  eviction call anywhere in the class — `currentSnapshot()` (line 62-65)
  only *filters* on read via `.filterValues`, the map itself keeps every
  entry forever. `README.md:336`, verbatim, still today: "Peers older
  than 30 minutes are pruned from the in-memory map on each update
  cycle." That's not true of the current implementation — an
  unbounded-growth doc claim that's actively wrong, not just outdated,
  which is worse than the silent-staleness pattern found elsewhere (§4
  of the round-1 entry above). Same class of bug as `BreadcrumbCache`/
  `TopologyTracker`'s unwired prune methods (§2.5 above) — recommend
  fixing all three unbounded-growth spots in one pass, since they're the
  same mistake in three places.
- **`mergeRemoteStatus` still adopts any peer-supplied timestamp greater
  than what's on file, with no upper bound against the future**
  (`OnlineStatusTracker.kt:54-60`, `if (existing == null || ts >
  existing) lastSeen[id] = ts`). A malicious peer can inject a far-
  future timestamp for any userId and that user shows as permanently
  ONLINE to everyone downstream in the gossip chain. Cheap fix: clamp
  to `minOf(ts, now)` before the comparison.
- **`KeywordFilterMatcher.match()` still has zero callers outside its
  own tests** — confirmed by grep across `:app`. Stronger than "O67 UI
  not built yet": there's no consumer of the matcher at all, so even a
  fully populated, correctly-subscribed filter list has zero effect on
  what any user sees today. (Incidental upside, unchanged: because
  nothing calls it, it can't have accidentally been wired into the
  relay path either, so the "filtering is display-only" rule isn't at
  risk here — just by absence, not by an enforced gate.)
- **`DeviceQuirks.isDeviceMediaTek()`/`isDeviceQualcomm()` are still
  dead code** (`DeviceQuirks.kt:150-160`): both call
  `System.getProperty("ro.product.board")`, which is a JVM system
  property lookup, not an Android build-prop read — on Android this
  should be `Build.BOARD`/`Build.HARDWARE`. Both detectors always
  return `false` on real hardware, so the documented "MediaTek demands
  Group Owner regardless of intent" workaround silently never fires on
  actual MediaTek devices — exactly the budget-phone class O33 targets.
  Cheap, mechanical, one-line-per-function fix.
- **The "Radio duty cycle" Settings slider is still cosmetic.**
  `scanIntervalSec` has a ViewModel setter but nothing downstream reads
  it (not persisted, not consulted by `BleDiscoveryManager`/`Scheduler`);
  `sleepIntervalSec` has no setter at all, permanently 30. A user
  dragging the slider changes nothing.

## Not re-verified this round (time-boxed; flagging for whoever picks
   these up next rather than leaving silently unmentioned)
- The transfer-receive OOM path (`Chunker.kt` allocating
  `ByteArray(metadata.totalBytes.toInt())` with no cap) — this is
  already tracked as `CLAUDE.md` O108/O109 (transfer-metadata resource
  caps + windowed chunk requests), so not new, just confirming the
  round-1-of-2026-07-13 finding and the current backlog row are
  describing the same gap.
- `PluginRegistry`/`PluginCatalog` disable-boundary races (a plugin
  snapshotted just before `unregister()` can still receive one more
  `onMessageReceived`; outbound `sendMessage` isn't cancelled by a
  disable since it runs on `GossipEngine`'s scope, not the plugin's) —
  plausible on a code-shape read, not independently re-confirmed this
  round.
- PBKDF2 iteration count (100,000, roughly 6x below OWASP's current
  600,000 guidance) and the passphrase `String`/`PBEKeySpec.
  clearPassword()` gaps noted alongside `IdentityManager.lock()` in the
  original round-2 write-up — not re-checked independently this session,
  but plausible and low-cost to verify given how directly it sits next
  to the `lock()` finding already confirmed in the entry above.

---

# Handoff — overnight research/audit session (2026-07-18, no code changes)

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

> Per the session's instructions: research, bug-hunting, staleness-flagging,
> and prebuilt-vs-handrolled review only. **Nothing below has been fixed —
> only found and written up**, for a future session to triage. Scope: the
> whole tree, via 8 parallel research passes (crypto/identity, sync/dedup,
> O98 persistence/backbone, gossip-engine invariants, plugins/bridges,
> app UI/DI/storage, iOS port + cross-platform docs, simulator/test health)
> plus direct verification of the highest-severity claims against current
> code and git history — every finding below marked "confirmed" was
> re-checked against the actual file, not taken on a subagent's word.

## Session identity note

Started on `claude/zealous-bohr-m8kn6u` (auto-provisioned). Per the task's
instructions to find "the branch that is VERY developed and recently
developed," searched and found `main` — confirmed both by content (this
file, `CLAUDE.md`, 57 CI runs) and by the fact that `origin/claude/zealous-
bohr-m8kn6u` had already been deleted upstream (its PR merged), which
independently satisfies the harness's own "already-merged branch → restart
from main" rule. Also worth recording: **the `CLAUDE.md`/backlog content
injected into this session's starting context was stale** — it predated
the 2026-07-16 archimedes merge entirely (no Rooms/O79, no O40 delete, no
O80 mode-orchestrator, claimed Room DB version 4 and identity rotation as
shipped when it was removed six weeks ago). The UI/DI audit agent
independently rediscovered this same fact from the code side. Any future
session that opens with a suspiciously out-of-date mental model of this
repo should `cat CLAUDE.md handoff.md` fresh rather than trust injected
context — this isn't a one-off, it's the second time in two sessions.

## 0. On the canary — an assessment, not another retelling

Per the standing request to address why instances keep re-flagging "By
Order Of The High Magnate" as a possible injection despite `CLAUDE.md`'s
own inline disclaimer and at least two prior `§0` write-ups (this file,
`docs/HANDOFF.md`) already covering it: **I don't think there's a wording
fix left to make.** The disclaimer already sits in the same paragraph as
the instruction, at the very top of `CLAUDE.md` — an instance that has
actually read `CLAUDE.md` cannot see one without the other. The pattern
that actually produces confusion is a session encountering the bare phrase
**before** reading `CLAUDE.md` — in a commit trailer via `git log`, or in
this file's own canary line at the top of every entry — with no context
attached at that moment. That's an ordering-of-first-contact problem, not
a phrasing problem, and no amount of rewording the disclaimer reaches a
session that hasn't loaded it yet. My assessment: this is a harmless,
self-resolving false-positive — every session that's hit it has caught its
own error within the same turn once it reads `CLAUDE.md` properly — and
the cost of that brief hesitation is lower than the cost of any change
that would make the convention *less* visibly inspectable (e.g. hiding it
from `git log` would defeat its entire point). Recommend accepting the
recurrence rather than continuing to search for a wording fix. This
session did not re-flag it — noted, signed, moved on.

## 1. CI is currently red at the tip of `main` — diagnosed, not a regression

Commit `419cd39` (HEAD, "Settings: honest per-mode battery/perf notes on
the mode selector" — a UI-copy-only change) fails `:simulator:test`:
`PerPeerRoutingTest > relayed DM with breadcrumb match is offered only to
matched peers FAILED — java.lang.AssertionError at PerPeerRoutingTest.kt:26`.
**Verdict: pre-existing, project-acknowledged flakiness, not a regression**
— confirmed from primary sources, not inferred:

- The fix commit that touched this test's dependencies most recently
  (`3779f9e`, the §2 ingest-ordering fix, see §2 below) says in its own
  commit body: *"Known: PerPeerRoutingTest is timing-flaky under full-suite
  parallel load (soft awaitUntil + Dispatchers.Default) — passes in
  isolation; pre-existing, not from this change. Candidate for the O12
  single-threaded-dispatcher determinism fix."*
- This matches `CLAUDE.md`'s own `O12` **[DECISION]** entry exactly: tests
  built on `Dispatchers.Default` + real coroutine scheduling + seeded-but-
  not-fully-deterministic exchange choreography have documented ~5%
  variance; O12 tolerates it with a ±5% band on *other* assertions but
  `PerPeerRoutingTest` uses hard `assertTrue`/`assertEquals`, so it doesn't
  get that cushion — it just intermittently fails outright instead.
- **This is no longer theoretical — it just failed in real, current CI**,
  which is new information beyond O12's original framing (written before
  gradle.properties existed and CI ever actually ran green). Worth
  promoting from "candidate fix" to "do this soon": convert
  `SimNode.scope`'s dispatcher to single-threaded for this test class (or
  project-wide for `:simulator:test`), per O12's own stated escalation
  path.
- **Systemic risk, not isolated**: the same `Dispatchers.Default` +
  `awaitUntil` (soft-timeout, no throw — a race manifests as a confusing
  downstream `AssertionError`, not a clear timeout) + hard-assert shape
  also appears, unconfirmed-but-suspect, in `BreadcrumbBiasTest.kt`,
  `MeshViewConvergenceTest.kt`, `RbsrSimTransportTest.kt`,
  `SelfEchoTest.kt`, `SelfPresenceTest.kt`. `BreadcrumbSubstrateTest.kt` is
  already independently documented elsewhere in this file (line ~1207) as
  known-flaky under load. None of these are silenced/`@Ignore`d — the
  suite is honest about failing loudly, just not deterministically.

## 2. New high-severity findings (not previously tracked anywhere)

### 2.1 `MeshService.startMesh()` has no reentrancy guard — confirmed live, HIGH

`app/src/main/java/com/rumor/mesh/service/MeshService.kt`: `onStartCommand`
(line 145) unconditionally calls `startMesh()` on every service start —
and `MainActivity.onStart()` (line 147) calls `bindMeshService()` →
`ContextCompat.startForegroundService(...)` on **every app foreground
transition**, not just first launch. `startMesh()` has no `if (started)
return` anywhere (grepped for `started`, `isStarted` — zero hits), and
`scope` (line 121, `CoroutineScope(Dispatchers.Default + SupervisorJob())`)
is a `val` built once in the service's field initializers, never
recreated. Every re-entrant `startMesh()` call stacks, on that same
`scope`, without cancelling the previous set:

- a fresh `gossipEngine.incomingMessages.collect { pluginRegistry.on
  MessageReceived(msg) }` (duplicate plugin delivery — a bridge could
  forward the same message to a LoRa radio N times),
- a fresh `wifiDirectTransport.exchangeResults.collect { ... }`,
- a fresh **SELF_PRESENCE beacon loop** (`while (isActive) { compose...;
  delay(...) }`) — N independent beacon timers now firing,
- a fresh **O98 backbone recompute loop** (`while (isActive) { delay
  (BACKBONE_RECOMPUTE_INTERVAL_MS); coordinator.recompute(); ... }`).

That last one is the one that worries me most given what's shipped since
this exact bug class was first flagged (a prior, pre-merge pass found the
same shape of bug and it evidently never got ported/fixed — see the
"already found on the sibling lineage" pattern that recurs throughout this
document's history). The O98 backbone recompute loop makes **real**
`WifiP2pManager.createGroup`/join decisions (`BackboneRealizer`). G38's
own field log is an 8-round campaign fixing exactly the class of chaos
that comes from *uncoordinated concurrent* radio-state decisions
(duplicate `groupCreated` reaffirms, stale-disconnect races, host-election
fragmentation). Two independent copies of that recompute loop running
concurrently on one device — trivially reachable by backgrounding and
re-foregrounding the app twice — reintroduces exactly that failure class,
except caused by the app itself rather than radio conditions, and would be
very confusing to debug because nothing in the field logs would look like
"two loops fighting" from the outside. **Recommend**: an `AtomicBoolean`/
`started` guard at the top of `startMesh()`, or cancel-and-relaunch
`scope`'s children (not the whole `scope`, since `stop()` elsewhere
appears to depend on it staying alive — verify before changing). Cheap,
mechanical, high-value fix.

### 2.2 `IdentityManager.lock()` never zeroes key material and is never called — HIGH

`app/src/main/java/com/rumor/mesh/core/identity/IdentityManager.kt:73-76`:
`lock()` sets `_identity.value = null` only; the `privateKeyBytes` array
underneath is dropped by reference, never `.fill(0)`'d — directly
contradicting `Identity.kt`'s own doc comment ("holder zeroes its
StateFlow value on lock"). Compounding: `lock()` has **zero callers**
anywhere in `:app` — no Settings entry point, no auto-lock-on-background,
nothing. Once unlocked, the long-term Ed25519 private key sits
unzeroed in heap memory for the process lifetime with no way to lock it
even manually today. `unlock()`/`changePassphrase()` have the identical
gap on the old key bytes they replace. Also noted in passing:
`pbkdf2HmacSha256` never calls `PBEKeySpec.clearPassword()`. This is a
real gap against O20/O44's stated pre-ship bar (TEE-backed keys,
zeroize-on-lock) — currently there's no lock *feature* at all to audit for
correctness, which is a bigger gap than "the zeroize has a bug."

### 2.3 O38 receiver-side forward secrecy is fully unwired — composeDirect never uses it — HIGH

`core/src/main/kotlin/com/rumor/mesh/core/protocol/GossipEngine.kt`:
`PrekeyCache` exists, is populated on inbound `PREKEY_PUBLISH`
(`handlePrekeyPublish`), and is exposed on the engine — but
`composeDirect` (~line 412-511) never calls `prekeyCache.freshestFor(...)`.
It unconditionally converts the recipient's **long-term** Ed25519 identity
key to X25519 and DHs against that, every time. Worse: **no function
anywhere in the tree composes or broadcasts a local `PrekeyPublish`** — no
node ever publishes its own rotating prekeys either. The entire O38
feature is receive/verify/cache-only on both ends; the documented
receiver-side-FS guarantee (a relay holding stored ciphertext cannot
decrypt past DMs after a key leak, once a prekey has expired) does not
exist in production today despite substantial supporting code
(`PrekeyCache`, `PrekeyVerifier`, `Prekey.kt`, dedicated tests) that would
lead a reader to assume it's live. `CLAUDE.md` doesn't currently carry an
open row for "wire the prekey substrate into compose" specifically —
recommend filing one; this is a real, scoped, well-bounded piece of work
(the crypto and cache already exist, it's a call-site + publish-loop gap).

### 2.4 Zero Room `Migration` objects exist despite 5 schema bumps (v5→v9) — HIGH, forward-looking

`RumorDatabase.kt`: `version = 9`, with a version-history KDoc noting bumps
through v9 (O79 Rooms). `grep -rn "Migration(" app/src/main` returns
**zero** real migration definitions (the one hit is a comment, not code).
`fallbackToDestructiveMigration()` is correctly gated to `BuildConfig.DEBUG`
only (release builds crash-don't-silently-wipe, matching CLAUDE.md's
stated intent and what a prior audit pass already confirmed) — but that
means a release build hitting *any* future schema bump with no explicit
migration throws `IllegalStateException` on database open and the app
refuses to start until reinstalled, i.e. **full data loss from the user's
perspective, and on the exact kind of device (long-term-offline mesh
anchor) O55 explicitly designs for.** No git tags exist yet (`git tag` is
empty) — nobody has shipped a release, so nobody's been bitten *yet*. But
the schema has bumped 5 times without a single migration ever written, so
this isn't hypothetical risk, it's 100%-certain-to-fire risk deferred only
by the absence of a first release. Recommend treating "write the v5→v9
migration chain, or at minimum start writing real migrations from the next
bump forward" as a pre-first-release gate, alongside `exportSchema=true`
actually getting a `room.schemaLocation` KSP arg (currently unset, so the
schema JSON needed to author real migrations isn't even being written).

### 2.5 `BreadcrumbCache.pruneOld()` / `TopologyTracker.pruneStale()` are never called — unbounded growth, confirmed still true

Both methods exist, are correctly implemented, and are fully wired down to
Room (`BreadcrumbDao.pruneOld`, `RouteDao.pruneStale`, adapter overrides,
simulator stubs all present and correct) — but grepping for actual
*call sites* of `breadcrumbCache.pruneOld()` / `topologyTracker.pruneStale()`
(as opposed to their own internal repo calls) finds none, anywhere, in
`:app` or `:core`. No periodic job, no `MeshService` hook. Both classes'
own doc comments describe "24h breadcrumb inactivity prune" / "7-day route
staleness de-rank" as active behavior; neither actually fires. This
directly contradicts O55's own design decision ("storage quotas must
handle months of accumulated messages without OOM; eviction policy is the
load-bearing piece") — the breadcrumb and route/neighbor tables are
exactly the kind of unbounded-growth-over-months risk O55 calls out, and
today nothing evicts them. Cheap fix: a periodic call from `MeshService`
next to the other `while (isActive) { delay(...); ... }` maintenance
loops it already runs (see §2.1 — though note the reentrancy bug above
means "just add another loop" should wait for that guard, or it'll stack
too).

### 2.6 O98 backbone: SSID cooldown is bypassed on 2 of 3 join paths — medium-high

`app/.../wifidirect/WifiDirectTransport.kt`: `ssidCooldownUntil` (own doc
comment: failed SSIDs "go on cooldown so they can't pin the radio away
from the legacy flow," intended `SSID_COOLDOWN_MS` ≈ 180s) is consulted
only by `visibleBackboneSsids()` (the bootstrap path in `connectToPeer`).
The planned-Client join path (driven every `BACKBONE_RECOMPUTE_INTERVAL_MS`
= 12s from `MeshService`) and the host-defers-to-senior path both call
`joinBySsid()` directly, skipping the cooldown check — a join that just
failed can be retried roughly every 12-45s (bounded only by the 45s
in-flight watchdog) instead of the intended 180s. Directly contradicts the
code's own stated design intent; worth a quick fix (route all three paths
through the same cooldown gate) before it's field-tested again, since G38's
whole campaign was fighting exactly this class of radio-hammering churn.

## 3. Confirmed RESOLVED since the last written audit — stop re-flagging these

Cross-checked against git history and current code; all four genuinely
fixed, not just claimed fixed:

- **§2/§4.2 dedup-before-sig-verify censorship primitive + rate-limit
  bypass** (the historical top-priority item in this file, originally
  found 2026-07-13): **fixed in commit `3779f9e`** (2026-07-17). Verified
  the actual diff: check-only `duplicateFilter.mightBeSeen()` → sig verify
  → `senderId == SHA-256(pubkey)` identity binding → rate-limit (now on a
  `BoundedFifoMap(10_000)`, not an unbounded map) → commit. Independently
  re-verified the Rooms/`MESSAGE_DELETE`/`PREKEY_PUBLISH`/`SELF_PRESENCE`
  paths all route through the same fixed `MessageStore.ingest()` — no
  bypass path exists. Test coverage: `MessageStoreIngestTest` (3, sim).
- **Server-accepted sockets never got a read timeout** (previously
  suspected root cause of O31's intermittent HELLO timeouts): resolved as
  a byproduct of G29 hoisting `GossipSession` to `:core` as one shared
  wire state machine — `socket.soTimeout = HELLO_TIMEOUT_MS` (line 166,
  before either side's role diverges) then `SESSION_TIMEOUT_MS` (line 231)
  now apply identically whether the socket came from `accept()` or
  `connect()`. O31 itself may have other contributing causes (still open
  in CLAUDE.md) but this specific mechanism is closed.
- **G12 direction-tiebreak bug** ("both sides veto the only session that
  can exist" in a GO↔client group): present in current code as "One live
  session per peer, first-come-wins" (`WifiDirectTransport.kt:923`) — the
  fix from the sibling lineage did make it into `main`.
- **No WifiLock anywhere**: a `WifiManager.WifiLock` now exists
  (`WifiDirectTransport.kt:78-79`, `WIFI_MODE_FULL_HIGH_PERF`). Separately
  noted elsewhere in this file (O94) that this mode is a no-op on API 34 —
  that's a real, already-tracked limitation, but "doesn't exist at all"
  is no longer accurate.
- **`CLAUDE.md`'s own G28 row** ("MessagesScreen.kt has a third, simpler
  copy of formatElapsed") is now itself stale — `MessagesScreen.kt`
  imports the shared `ui.formatElapsed` today, no private copy remains.
  Small, but flag it so the next session doesn't go looking for a bug
  that's already gone.
- **`IDENTITY_ROTATION`**: fully removed, zero dangling references
  anywhere in `.kt` source (two independent agents confirmed this by
  grep). `docs/GLOSSARY.md` has NOT caught up — see §5.

## 4. Full findings catalog by subsystem (condensed from the 8 parallel passes)

### Crypto / identity (beyond §2.2/§2.3 above)
- `CryptoManager.x25519Agreement`'s KDoc still describes the derivation as
  "PBKDF2 with the 'RumorDH' salt, single iteration" — the actual
  implementation is HMAC-SHA256/HKDF-extract (the G20 fix). A re-
  implementer (iOS/Linux, told elsewhere to be "bit-identical") reading
  only this comment would build the wrong primitive. **Med, doc-only.**
- `platformDeriveAesKey` reimplements HKDF-extract via a raw `javax.
  crypto.Mac` call instead of calling the already-correct `HkdfSha256.
  extract` — not a security bug (both do the same HMAC), but two code
  paths now have to stay byte-identical by convention instead of by
  construction. **Low, consolidate.**
- `x25519Agreement` already folds in a domain-keyed HMAC before
  returning, and `SealedSenderKey.derive` then runs a *second* full
  HKDF on top as if the input were raw ECDH output. Functionally safe
  (double-KDF doesn't weaken anything) but easy to misreason about
  domain separation between DM crypto and O53 sealed-sender. **Low.**

### Sync / dedup / RBSR
- `RbsrWire.kt`'s `Fingerprint.fp` field comment still says "XOR
  fingerprint" — the implementation is the additive-mod-2^256 scheme that
  specifically replaced XOR because it was forgeable. Impl is correct;
  comment is stale. **Low.**
- `BloomFilterData`'s OOM handling (`deserializeOrNull` catching
  `OutOfMemoryError`) is real and correct (O13/G26), but `expectedItems`
  is never pre-clamped before allocation — a peer can pick a value large
  enough to force heavy allocation/GC pressure without actually throwing,
  a "slow DoS" the existing crash-hardening doesn't cover. **Med.**
- RBSR's round loop has no cumulative-size ceiling across a session's
  rounds (only a 4MB per-frame cap) — a malicious peer sending near-max
  frames across all 12 rounds accumulates tens of MB in `peerHas`/
  `peerNeeds` sets with no matching hardening to the bloom path's. **Med.**
- `SortedListRbsrStorage.items()` does a linear `.filter` scan of the
  *entire* list per range query rather than a binary-search-bounded
  slice — real cost trends toward O(N·branches) across the 16-way
  bisection, not the O(N log N) the design assumes. Worth profiling
  before the O55/O98 large-anchor-store regime this is meant to serve.
  **Med, perf not correctness.**
- `CLAUDE.md`'s G31 row cites a `core/src/commonTest/...` path for
  `TwoTierDuplicateFilterTest` — `:core` is plain JVM now, the real path
  is `core/src/test/kotlin/...`. KMP-era doc residue. **Low.**

### O98 persistence / backbone (beyond §2.6)
- `GroupCredentials.kt`'s class KDoc still calls `rumor-o98-psk-v1:`
  "reserved forever" — the shipped code uses `-v2:` (v1 retired same-day
  per `RENAMED_FIELDS_NEVER_REUSE.md`, correctly recorded there). Doc
  wasn't updated when v2 shipped. **Low.**
- Host-role retries are grace-windowed + budget-capped (45s/3 retries);
  Client-role retries are explicitly uncapped with a comment claiming
  the connect watchdog naturally rate-limits it — which, given §2.6, it
  doesn't reach the intended magnitude. Comment describes intent, not
  current behavior.
- Pre-Android-10 legacy negotiated-pairing path: verified correctly
  gated (`Build.VERSION.SDK_INT < Q → return` on every backbone call) —
  **not** dead-weight risk, the field narrative checks out.
- Core algorithms (`PersistencePlanner`, `PersistenceReconciler`,
  `BackboneRealizer`, `MeshViewTracker`, `ChannelSelector`) hand-traced
  and found correct, deterministic, internally consistent with their own
  docstrings. No new bugs there.

### Gossip engine / protocol invariants (beyond §2.5)
- `BreadcrumbCache` records DM breadcrumb `hopCount` using
  `DEFAULT_BROADCAST_HOPS` (7) unconditionally, even for DIRECT messages
  whose real budget is 15 (`GossipEngine.kt:992`) — every DM's stored
  hopCount is meaningless (usually clamped to 1 via `coerceAtLeast(1)`).
  Low current impact (routing doesn't consume this field yet per a prior
  pass) but a landmine for whoever next builds on top of it. **Med,
  still present, easy one-line fix once someone needs the field to be
  real.**
- Core invariants (relay never touches blocklist, BRIDGED never
  re-relayed including for Rooms/BRIDGE_VOUCHED, BRIDGE_UNSIGNED gated to
  LOCAL_BRIDGE only, no MAC-as-identity) all independently re-verified
  directly against `GossipEngine.kt` — hold exactly as documented.
- `MessageStore`/`GossipEngine` — the two most central protocol classes —
  still have thin dedicated coverage (`MessageStoreIngestTest` now exists
  post-§2-fix with 3 cases, but no broad `GossipEngineTest.kt`). Given
  how much rides on ingest ordering specifically, worth more direct
  coverage rather than relying on `SourceInvariantTest`'s regex checks.

### Rooms (O79) — newest feature, least field-tested
- **`docs/O79_PROTOCOL_SPEC.md`'s "Known gaps" section is stale**: it
  claims Ed25519→X25519 derivation "isn't wired" and a client "cannot
  exchange ENCRYPTED room messages... until O91 is closed." **O91 is
  closed** (`AppModule.kt:166-187`, `SimNode.kt:89-108` both derive a
  real X25519 static via `CryptoManager.ed25519ToX25519PrivateSeed`, and
  `RoomEndToEndIntegrationTest` exercises real encrypt→decrypt for 3
  recipients + a correctly-excluded non-recipient). This is a re-
  implementer-facing spec doc (its stated audience is iOS/Linux/MCU
  ports) — a stale "you can't interop yet" claim has real cost to anyone
  building against it. **Independently found by two of the eight agents
  plus my own direct check — high confidence.**
- **`docs/ROOMS_THREAT_MODEL.md` overclaims write-permission enforcement
  as currently live.** It states in the present tense that "every honest
  peer fails the structural check and drops the message" for an
  unauthorized channel post. In reality `RoomPostingCertVerifier.verify()`
  is never called from anywhere in `:core`/`:app`/`:simulator` (confirmed
  by grep — zero call sites outside its own file and its own test);
  `RoomAction`/`RoomCreate`/`RoomInvite` have no `MessageType` wire entry
  at all, so moderation and posting-policy can't even travel on the wire
  yet. `CLAUDE.md`'s own O89 row is honest about this (`[TODO/CODE]`,
  "write enforcement only," open) — the standalone threat-model doc is
  the one place this reads as shipped. Given the doc's own stated rule
  ("if a policy can't be [structurally] expressed, document the limit
  honestly and don't pretend it's enforceable") this is a direct
  violation of the doc's own standard. Recommend a one-line "NOT YET
  ENFORCED — tracked as O89" banner until it's wired.

### Plugins / bridges
- **New**: `MeshtasticProtobuf.Reader.readSubMessage` has zero bounds
  validation, unlike its siblings `readBytes`/`skipField` which both
  `require(pos + len <= end)`. `readSubMessage` builds a sub-`Reader`
  and advances `pos` unconditionally — an adversarial length can
  integer-overflow `pos + len` negative (silent truncation, a worse
  failure mode than a crash) or desync the *outer* reader's bounds for
  every subsequent field. Currently masked by the call-site `runCatching`,
  same as the already-known `readInt()`/`readBytes()` gap, but strictly
  larger. Given this session found two concrete bounds-check gaps in the
  hand-rolled codec (not just the abstract "should use Wire someday"
  concern CLAUDE.md already records), I'd mildly upgrade the priority: a
  small targeted patch (`require(pos + len in pos..end)` before
  constructing the sub-reader, plus a `len >= 0` guard on the varint
  path) is cheap and doesn't require the bigger Square-Wire-migration
  decision to be revisited.
- `DmEnvelope.selfAuthenticating` — documented as O5a's constraint #1
  security gate — is never actually read anywhere in production code.
  Today's safety is an emergent property of `injectBridgedDm` only being
  reachable from app-internal `PluginContextImpl`, not an explicit check
  on the field the docs point to. Not exploitable today; nothing pins
  the invariant against a future refactor that starts branching on it.
  (The other five O5a constraints were all directly re-verified as
  correctly and explicitly enforced — this is the one gap.)
- Neither bridge (Meshtastic, MeshCore) reconnects after a BLE
  disconnect — `ready` flips `false` only in `onDetach()`, never on a
  `STATE_DISCONNECTED` GATT callback. Radio out of range/reboot → silent
  permanent failure until the user manually toggles the plugin.
- MeshCore never negotiates a larger BLE MTU (`Meshtastic` does,
  `requestMtu(517)`) despite `MeshCoreFrames.MAX_TEXT_BYTES = 120` —
  outbound text beyond roughly 14 characters likely truncates/drops
  silently against the default ~20-byte ATT payload.
- Both bridges assign a **fresh random UUID per decode** rather than
  deriving an id from the radio packet — a BLE/LoRa retransmission of
  one physical packet produces N distinct "novel" messages in the feed;
  dedup can't catch a bridged replay. Bounded blast radius (BRIDGED,
  never re-relayed) but a real gap against O5a constraint #5.
- O48 (migrate MeshCore's synthetic userId from name-hash to pubkey-hash
  before DM bridging ships) remains genuinely unaddressed — no
  half-finished code found either way.
- **Clean**: O67 keyword filters and O76 compression+padding, both new
  since the last audit pass, hold up well — `Compression.inflate` has an
  explicit zip-bomb cap, `Unpad.unpad` validates length against actual
  buffer size, `originalLength` rides in AEAD-protected AAD rather than
  trust-on-read `_ext`.

### App UI / DI / storage (beyond §2.1/§2.4)
- `PersistenceCoordinator` (O98) is constructed manually inside
  `MeshService` rather than via a Koin `single{}` — likely intentional
  given its lifecycle is tied to mesh start/stop, but it means
  `AppModuleTest`'s Koin `verify()` never exercises its constructor
  wiring. Worth a comment noting this is a deliberate DI-rule exception,
  if it is one.
- O79 (Rooms), O90 (thread/mention), O67 (keyword filters) all have
  complete protocol + persistence + DI wiring but **zero UI surface** —
  confirmed by grep across `app/.../ui/` for Room/mention/keyword-filter
  terms, zero hits. Matches what `CLAUDE.md` already tracks as `[PART]`
  rows, so not new drift, but worth stating plainly: these three
  features are backend-complete and invisible to a user today.
- O40 (`MESSAGE_DELETE`) is fully implemented and tested at the protocol
  layer with zero call sites from any ViewModel/screen — matches
  `CLAUDE.md`'s own G33 note, confirmed accurate.
- No FLAG_SECURE gaps found (no plaintext-identity-export screen exists
  yet to need one); `ChangePassphraseScreen` correctly masks input
  throughout.

### iOS port + cross-platform docs
- **`ios/RumorCryptoBridge.swift` has silently drifted from its own
  spec.** `docs/IOS_SWIFT_BRIDGE_SPEC.md` (2026-06-04) added an `aad`
  parameter to `aesGcmEncrypt`/`Decrypt` (needed for O76 compression-
  length binding) and two new methods, `ed25519ToX25519PrivateSeed`/
  `ed25519ToX25519Public` (needed for O91 DM key derivation). The
  vendored Swift file (2026-06-05, one day later) implements neither.
  Per the spec's own text, without those conversions "real Rumor users
  cannot DM each other on iOS." Neither file has been touched in the six
  weeks since — the gap has just sat there unreconciled.
- `ios/README.md` and `docs/IOS_SWIFT_BRIDGE_SPEC.md` both still
  describe a KMP source layout (`core/src/iosMain/...`, `expect`/`actual`
  stubs, `commonTest`) that no longer exists — the archimedes merge
  deliberately flattened `:core` back to plain JVM (CLAUDE.md's own G30/
  O63 note records this explicitly). Neither iOS doc acknowledges that a
  KMP split was already built, tested, and intentionally reverted for
  reasons unrelated to iOS — a contributor resuming from these docs cold
  would re-derive that history the hard way.
- **`docs/GLOSSARY.md:92-93` documents a removed feature as live**:
  "Identity rotation (O41) — migrate to a new key with a continuity
  signature... contacts rebind under the new id." This was **removed
  entirely** six weeks ago (commit `806c885`, per `CLAUDE.md`'s own G9
  entry: auto-rebind-on-rotation turns a stolen old key into permanent
  impersonation). `GLOSSARY.md` was last touched 2026-07-15 — a cleanup
  pass that ran *after* the removal and *after* CLAUDE.md was corrected
  — and the stale entry survived that pass anyway. Highest-confidence
  single doc-drift finding of the session: a contributor trusting the
  glossary would believe a security-relevant migration flow exists when
  it was deliberately deleted for security reasons.
- Two more stale comments in the same family: `GroupCredentials.kt`
  (psk-v1 vs shipped v2, see above) and `core/.../model/GossipPacket.kt`
  telling future readers to "mirror the rbsr-v1 / rbsr-v2 capability-
  gated pattern" — `rbsr-v2` doesn't exist anywhere in the codebase; it
  was the forgeable raw-id-sum variant the archimedes merge deliberately
  dropped. The comment models a nonexistent sibling feature.
- `docs/wire-format.md` and `docs/RENAMED_FIELDS_NEVER_REUSE.md`: no
  drift found — every current `MessageType`, all 21 domain tags (see
  below), and the O79 byte formulas match code exactly.

### Wire-format / domain-tag bookkeeping
- `DomainTagInvariantTest` asserts 20 reserved domain tags; the code
  actually emits 21. `"rumor-dm-recipient-tag-v1:"` (O53) and
  `"rumor-room-posting-cert-v1:"` (O89) are both correctly listed in
  `RENAMED_FIELDS_NEVER_REUSE.md` but neither has the build-time
  assertion pinning it that the guard exists specifically to provide —
  exactly the gap class the test was written to catch, now present in
  the test itself. `docs/wire-format.md` §6 has the same two omissions.
  **Low severity, but a 2-line fix and it defeats the guard's purpose
  until fixed.**

### README.md — still self-contradictory, unaddressed since first flagged
Re-verified directly (this is a re-confirmation, not new): `README.md:25`
still says "split into 14 modules" (actual: 3 Gradle modules, matching
`CLAUDE.md`); `README.md:494` still says both bridges "exist as stubs
with clearly marked TODOs," contradicted by the same file's own "Known
limitations" section describing working BLE codecs; the "Unit test
coverage" table (line ~154) still lists a handful of classes against an
actual current count of **77** `*Test.kt` files across the three modules.
All three claims were already flagged in this file previously and remain
unfixed five days later — cheap, high-visibility fix (it's the first
thing anyone reads) whenever someone's next in this file for something
else.

## 5. Prebuilt-vs-handrolled assessment (per the standing policy in
   `CLAUDE.md`'s "Design decisions recorded" section)

The policy is being followed well overall — BouncyCastle for Ed25519/
X25519, `commons-codec` MurmurHash3 for the bloom filter (verified: zero
hand-rolled hash remnants), real `java.security.MessageDigest` SHA-256 for
the O98 SSID/passphrase derivation, `HkdfSha256`/`HmacSha256` are correct
RFC 5869/2104 implementations. The one place I'd revisit the existing
"not worth touching yet" calculus: **the hand-rolled Meshtastic protobuf
codec now has two concrete, session-found bounds-check gaps** (§4,
`readSubMessage` + the pre-existing `readInt`/`readBytes` negative-length
path) rather than the purely-theoretical "a silent field-number bug
happened once" framing CLAUDE.md currently records. A full migration to
Square Wire codegen is still the right *eventual* answer and still not
worth doing as a big-bang change today — but the two specific bounds
checks above are cheap, low-risk, targeted patches that don't require
committing to the bigger migration, and would close real (if currently
masked) gaps. Nothing else rose to the level of "should use a library
instead" — the DIY code elsewhere (schedulers, planners, gossip engine,
transports) is exactly the app-specific glue the policy says is fine to
hand-write.

## 6. Suggested priority order, if picking one thing at a time

1. **§2.1 — `MeshService.startMesh()` reentrancy guard.** Small, mechanical
   fix; the O98 backbone-loop stacking risk it prevents is the kind of
   bug that costs a multi-hour field-debugging session to diagnose from
   symptoms alone (see G38's own war story for what that looks like).
2. **§1 — O12 single-threaded-dispatcher fix for the flaky sim tests.**
   No longer hypothetical; it's failing in real CI right now, and it's
   masking the project's actual safety net.
3. **§2.5 — wire up the prune loops.** One function call, closes a
   real unbounded-growth gap that contradicts the project's own
   long-term-deployment design intent.
4. **§2.2 — give `IdentityManager.lock()` a caller and make it actually
   zero.** Currently there's no lock feature to audit, which is worse
   than "the zeroize has a bug."
5. **§2.4 — Room migrations**, before the first tagged release rather
   than after (100%-certain-to-fire once it fires).
6. **§2.6 — SSID cooldown gate**, before the next field campaign hits it.
7. Everything else in §4 is real and verified but lower urgency — mostly
   doc drift (cheap, low-risk, do opportunistically) or scoped follow-up
   work (§2.3's prekey wiring, the bridge reconnect/MTU/replay gaps)
   that doesn't block anything else.

---

# Handoff — O80 mode auto-fire shipped, O57 closed (2026-07-17, session 3)

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

Branch `main`. Version **versionCode 21 / 0.6.0-o80-automode** — built, all
suites green (:core :simulator :app), APK assembles. **Not yet flashed or
field-checked** — no wire-format change, safe to flash alone or with the
next batch.

## What landed (G39 in CLAUDE.md is the full record)

Post-O98, per the standing most-foundational-first order, the Tier 1
remainder starts with the O80 orchestrator (also the O62/O57 close-out
remainder). Shipped:

- `core/mode/AutoModeController.kt` + 7 tests — pure decision layer.
  Starter profile = O57 defaults (plugged+screen-off → FREE, plugged →
  STATIC, else MOBILE). Hysteresis: suppress a change iff it's *purely*
  battery-driven (re-evaluating with battery pinned at the last-applied
  anchor yields the current mode) and battery moved <5 points from the
  anchor; discrete signals (plug/screen/network/time) always fire.
- `app/…/core/policy/ModeOrchestrator.kt` — sticky ACTION_BATTERY_CHANGED
  (seeds battery+plug state at registration) + SCREEN_ON/OFF receivers,
  `registerNetworkCallback` (the default-callback variant is API 24+,
  minSdk is 23), 60s tick for time-of-day rules; idempotent start/stop,
  fresh scope per start; owned by MeshService startMesh/stopMesh.
- `ModeStateManager`: persisted `autoEnabled` + bounded (20) transition log
  with MANUAL/AUTO source. Manual-override-wins is structural: the
  orchestrator applies only while auto is on; a manual pick turns it off.
- Settings: binary "Static mode" switch → 4-way Auto/Mobile/Static/Free
  radio selector (FREE manually reachable for the first time); last-5
  transition log rendered under it.
- G12 pulses needed nothing — MeshService already fires SELF_PRESENCE on
  every mode transition regardless of source.
- CLAUDE.md: G39 added; O57 closed (tombstone → G39); O80 remainder is now
  only rule-editor UI + profile persistence + per-rule override application.

## Field check when convenient (fold into the next flash round)

Plug a phone in with Auto selected → logcat `Mode → STATIC (AUTO)` + a
SELF_PRESENCE pulse; screen off while plugged → FREE; unplug → MOBILE.
Verify a manual pick stops auto transitions until Auto is re-picked. Also
still pending from last session: an observation round on 0.5.4's anti-churn
dampeners (unchanged in this build).

## Next (per the standing order)

O106 :node · O93 mDNS transport (+O107 SPI extraction at that moment) ·
O95 HLC — then Tier 2 security (§3/§4/§5/§12/§10 punch-list below).

---

# Handoff — O98 Phase 3b FIELD-VERIFIED (2026-07-17, session 2, continued)

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

Branch `main`, everything committed + pushed. Version at end of session:
**versionCode 18 / 0.5.4-o98-3b9**, flashed on all 3 phones. The G38 row in
CLAUDE.md is the full campaign record — 8 rounds of flash→field→fix in one
sitting, each round's bug + fix in the git log (`ded9e15..` this session).

## Session finale (rounds 9–11, 0.5.4→0.5.6): legacy retired

User verdict on 0.5.3's endpoint: churn unacceptable at scale, "fix the
legacy thing." Done: **negotiated pairing no longer exists on API 29+** —
bootstrap-by-hosting (peer visible + no backbone SSID on air → host your
own credentialed group; both-host collisions resolve by SSID seniority:
junior yields at its 35s idle check and joins the senior). Final
observation round: **`Connect initiated` 0/0/0 across the fleet, zero
prompts, star converged via hosting + credential joins only.** Negotiated
machinery survives solely for pre-Q devices. Known limitation to carry:
OS foreground-scan throttling (4/2min) paces SSID visibility — nodes can
bootstrap-host redundantly for minutes before seeing a live group
(self-heals via yield); fix candidates: SCAN_RESULTS broadcasts or P2P
service discovery. Current version **versionCode 20 / 0.5.6-o98-3b11**.

## Emulator status (2026-07-17, diagnosed and parked)

The "keeps dropping after ~30s" emulator is a **SIGSEGV in emulator build
36.6.11 itself** on this host (Fedora, kernel 6.19) — crashes ~12s in,
right after "performing a full startup", with exit 139. Ruled out: KVM
(`-accel-check` clean), OOM (5GB free), stale AVD state (fresh AVD also
crashes), GPU path (`swiftshader_indirect`, `-gpu off`, `-feature -Vulkan`
all crash identically), launch method (harness-tracked foreground run
reproduces). A pre-crash warning ("cannot unmap ptr … protected range")
points at the emulator's guest-memory sub-allocator. **Fix path when it
matters: manually install an older emulator package** (sdkmanager only
serves latest; grab a 35.x zip into `Android/Sdk/emulator/`), or retest
after the next emulator update. AVDs: `api31` (the original, in the
Flatpak Android Studio dir `/home/user/.var/app/com.google.AndroidStudio/
config/.android/avd` — launch with `ANDROID_AVD_HOME` pointed there) and
`rumor31` (created this session, same dir). [TODO/EMU] rows are unaffected
in priority — real hardware covers current testing needs.

## Bottom line

Phase 3b works on hardware and the user confirmed **zero join prompts**
through a full cold-start convergence: autonomous createGroup honors the
scanned quiet channel (verified via dumpsys on every round), the
SSID-derived credentials (`rumor-o98-psk-v2:`) make any visible Rumor
group joinable in 0.4–2s by any node including bootstrap strangers, every
durable Rumor GO converts to its credentialed group, and a 3-node star ran
stable for 1h+ with 10s sessions. The invitation prompt's exact mechanism
(AOSP `WifiP2pServiceImpl.notifyInvitationReceived`, WPS join-auth on plain
connect() into a FORMED group) is now structurally avoided everywhere.

## Still open on O98 (in the row)

- **0.5.4's anti-churn dampeners** (clients never legacy-initiate; 90s
  backbone memory; junior host defers to senior visible SSID) shipped LAST
  and have NOT had a full observation round — run one before trusting them.
  Watch: hub death now costs ~2–7 min of reconnection (view decay is the
  only path back to legacy) — tighten if the field says so.
- **Multi-group backbones** — everything converges into ONE star today
  (correct for ≤8 clients); the 20-node building needs the realizer's star
  decomposition realized as simultaneous groups (GO+client concurrency or
  time-slicing) — that's the next big O98 chunk.
- Role flap CLIENT↔NONE with beacon freshness (links held anyway) —
  coordinator-level hysteresis widening is the candidate fix.
- Framework quirks to remember: joins return spurious ERROR then connect
  ~300ms later (don't trust the callback; cooldown is skipped for BUSY but
  ERROR cools 3min — mostly harmless since onConnected clears state);
  beacons drain-to-first-peer so the hub's view starves (the deep fix is
  the G23-noted repo-sourced offers / beacon re-offer).

## Also this session

§2-era backlog: filed **O111** (nickname advertisement, receiver-accepted,
emoji-fingerprint differentiator), **O112** (hostile-input hardening sweep —
"null"/injection/unicode/oversize corpus, Tier 2), **O113** (self-nick UI).
User arbitration recorded: legacy negotiated flow is KEPT but confined to
bootstrap pairing of two ungrouped devices + last-resort fallback.

---

# Handoff — O98 Phase 3b code-complete (2026-07-17, session 2)

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

Branch `main`, version **versionCode 10 / 0.4.6-o98-3b**. All unit suites green
(:core :simulator :app), APK assembles. **Phase 3b is code-complete but NOT yet
field-verified** — the 3-phone regression (below) is the next step before the
O98 row can close.

## What landed (this session)

- **`core/routing/GroupCredentials.kt`** — deterministic group credentials from
  the host's userId (`DIRECT-` + 12 hex networkName, 16-hex passphrase; SHA-256
  with tags `rumor-o98-net-v1:` / `rumor-o98-psk-v1:`, reserved in
  RENAMED_FIELDS). Non-secret by design: anyone with the userId derives them —
  that's what makes joins prompt-free; trust stays with HELLO Ed25519.
- **`core/routing/BackboneRealizer.kt`** — pure deterministic star decomposition
  of the held backbone links into GO/client roles (capacity→degree→userId
  ranking; ranked node with free neighbours hosts and claims them). Radio
  constraints honored structurally: one group per host, one group per client,
  never GO+client. Unrealizable edges (far leaf of a 4-chain) → `dropped`,
  covered by the legacy negotiated flow. NOTE: the "join an existing host"
  branch is provably unreachable under this greedy (a host claims every free
  neighbour when processed) — hub-hub edges are realized through *claiming*.
- **`core/routing/ChannelSelector.kt`** — quietest non-DFS 5 GHz pick, UNII-3
  first (5745…5825, then 5180…5240).
- **`PersistenceCoordinator.selfRole()`** — realization over
  `reconciler.activeLinks()` + last view modes.
- **`WifiDirectTransport.applyBackboneRole(role)`** — HOST: removeGroup →
  autonomous `createGroup(Builder(networkName, passphrase,
  setGroupOperatingFrequency(quiet)))`; CLIENT: `connect()` with the
  credential-built config (prompt-free join; group may not exist yet — retried).
  While a role is being realized, legacy `connectToPeer` stands down
  (`backboneOwnsRadio()`); bounded retries (3, 45s grace each, re-driven by the
  12s recompute tick) then legacy resumes so a WPS-stubborn OEM degrades
  instead of stranding. GO idle watchdog holds a backbone host's group;
  role→None tears a credentialed group down; onDisconnected clears
  `backboneGroupCreated` so the next tick retries. API 29+ (Builder) only.
- **MeshService** — recompute loop now ends with
  `wifiDirectTransport.applyBackboneRole(coordinator.selfRole())`.
- Tests: `BackboneRealizerTest` (8), `GroupCredentialsTest` (5),
  `ChannelSelectorTest` (5). CLAUDE.md O98 row + RENAMED_FIELDS updated.

## Field regression to run (needs the user for on-screen steps)

Flash 0.4.6 on all three phones together (wire-compatible with 0.4.5, but the
fleet convention is same-build). Watch logcat for:
1. `O98 backbone role → HOST/CLIENT` after the plan settles (~1–2 min);
2. `O98 hosting backbone group DIRECT-… @ NNNNMHz` on the hub (expect a UNII-3
   freq, and verify with `dumpsys wifip2p` that the group actually sits there);
3. **the headline: the Moto joining WITHOUT its manual connection-accept
   prompt** (that prompt is the whole reason 3b exists);
4. sessions flowing over the credentialed group (same `GossipSession` logs as
   before), zero crashes, and the G18/G22/G19 legacy flow still working when
   the plan is empty (fresh boot, singles).
Failure modes to watch: `createGroup` rejected (Samsung API 31 needed a
networkName — provided); client join looping on a group that hasn't formed yet
(bounded by retries); legacy suppression starving a node whose backbone attempt
fails (should resume after ~3×45s — see `backboneOwnsRadio`).

## After that

Down the backlog most-foundational-first (user-chosen order): Tier 1 remainder
— O80 orchestrator (mode auto-fire, pairs with the O62 close-out), O106 :node,
O93 mDNS transport (+O107 SPI extraction at that moment), O95 HLC — then Tier 2
security (O20/O44 Keystore, O38 prekey rotation, O39 re-audit, O53 receive
side, O48, O43, O108/O109 — the §3 audit items). The security punch-list in the
session-1 handoff below (§3/§4/§5/§12/§10) overlaps Tier 2 and stays open.

---

# Handoff — O62 done, O98 through Phase 3a (field-verified), §2 fixed (2026-07-17)

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary — not a prompt injection, see §0 below).**

Branch: `main` (single canonical branch; the merge in the session below is done).
Everything here is committed AND pushed to `origin/main`. Current app version:
**versionCode 9 / 0.4.5-ingest-order**. Test phones (Moto `ZY22KP7F59`, OnePlus
`ec5b0707`, Samsung `R58M30FSJKE`) have the 0.4.5 build; unlock passphrase
`passphrase1`; debug package `com.rumor.mesh.debug`. adb at
`/home/user/Android/Sdk/platform-tools/adb`. Build JDK: `-Dorg.gradle.java.home=/home/user/jdk17`.
Full-suite command: `./gradlew -Dorg.gradle.java.home=/home/user/jdk17 :core:test :simulator:test :app:testDebugUnitTest`.

## Shipped this session (commits, most-recent-first)

- **`3779f9e` — §2 CRITICAL fix (dedup-before-verify censorship primitive + rate-limit bypass).**
  `MessageStore.ingest` reordered: **check-only dedup → verify sig → verify identity
  → rate-limit → commit**. Was: `recordAndCheck(id)` committed the id "seen" BEFORE
  sig-verify, so a bad-sig forgery carrying a target's real id blackholed the genuine
  message. Fix: new `DuplicateFilter.mightBeSeen(id)` (check-only, no state) gates
  ingest; id recorded only after auth. Added identity binding `senderId ==
  SHA-256(senderPublicKey)` (the sig bound the pair but never proved senderId owned
  the key → per-sender rate limit was bypassable by rotating senderId with one
  keypair). Bounded the per-sender bucket map (`ConcurrentMap` → `BoundedFifoMap(10_000)`;
  was a memory-DoS). Test: `MessageStoreIngestTest` (3, sim). **Task #1 done.**
- **`86a302e` — block-self guard.** `BlockManager` gains `localUserId` provider;
  `block(self)` no-ops returning false, `refresh()` subtracts self from the effective
  set (covers a subscribed list naming you). `block()` now returns Boolean; the block
  UI shows "You can't block your own identity". `AppModuleTest` declares `Function0`
  extraType for Koin verify. Test: `SelfBlockTest` (2, sim). Answered "what if you
  block your own id": mostly inert (relay is blocklist-blind, own feed is DB-rendered,
  inbound-self is echo-dropped), only real consequence is the publish-then-hide-me
  footgun — guard closes it cleanly.
- **`0f549f0` — O98 Phase 3a coordinator + self-echo/self-contact fix, FIELD-VERIFIED.**
  `core/routing/PersistenceCoordinator.kt` (pure brain: recent-peer tracking → beacon
  adjacency → assembleView → PersistencePlanner → PersistenceReconciler → backbonePeers),
  driven by MeshService (beacon loop, MOBILE floored to 90s so every node is visible to
  the planner; 12s recompute loop that logs each change; entry/exit pulse on mode
  change; plan gates transport `isPriorityPeer`). **On-device: Samsung reached
  `view=3n/2e planned=2 held=2` (correct 2-link spanning backbone), all three converged,
  zero crashes.** The beacon traffic surfaced a self-inclusion bug (a node saw ITSELF
  online): (1) online-status echo — a peer's snapshot includes us; `onExchange` now
  subtracts self before `mergeRemoteStatus`. (2) message echo → **persisted self-contact
  in Room** (survived `install -r`; that's why reinstall didn't clear it) — `ingest` →
  `ensureContact(self)`. Fixed with a guard at top of `processIncoming` (a PEER-sourced
  message we authored is always an echo → drop). Self-heal purge in `startMesh`
  (`contactRepo.delete(identity.userId)`) — DB-verified 0 self rows on the Moto. Made
  `SimTransport` faithful (was hardcoding a single-entry `peerOnlineUsers`). Tests:
  `PersistenceCoordinatorTest` (5, core), `SelfEchoTest` (2, sim).
- **`19ec68c` / `08455ee` — O98 MeshView substrate.** `core/routing/MeshViewTracker.kt`
  assembles the planner's `MeshView` from inbound SELF_PRESENCE beacons (mode → degree
  budget, `recentlyExchangedWith` → edges; bounded, decayed, future-clamped). Tests:
  `MeshViewTrackerTest` (7), `MeshViewConvergenceTest` (2, sim).
- **`dd7e5d4` — O62 ModeEnvelope** (single source of truth; retired binary StaticMode).

Also filed **O110** (block-user UI belongs in Contacts/thread, not a raw-userId text
field in Settings — user field-report).

## O98 status: Phase 3a DONE + field-verified; Phase 3b is what remains

The coordinator (the "which links to hold" brain) works on hardware. **Phase 3b** is the
fragile radio-realization: replace negotiated `connect()` with autonomous
`WifiP2pManager.createGroup` per backbone edge, deterministic pre-shared credentials for
**prompt-free joins** (the Moto still needs manual connection-accept — re-observed this
session), and quiet-channel selection. Full detail is in the O98 row in CLAUDE.md and
G37. This touches the hard-won G18/G22/G19 flow — scope it carefully, regress on all
three phones. Task #11.

## Known issues / notes

- **`PerPeerRoutingTest` is timing-flaky** under full-suite parallel load (soft
  `awaitUntil` + `Dispatchers.Default`); passes in isolation. Pre-existing, not from the
  §2 change. Same class as `MeshViewConvergenceTest`. The O12 escalation path
  (single-threaded dispatcher per `SimNode.scope`) would fix both — worth doing before
  it masks a real failure.
- The §2 fix means a persistent attacker re-sending the same bad-sig forgery forces a
  re-verify each time (we no longer record bad ids). Bounded by the attacker's own
  bandwidth; the alternative (recording bad ids) was the censorship bug. A global
  pre-verify work budget (not per-sender) is the proper CPU-DoS mitigation if needed —
  noted, not built.

## Suggested next (from the audit punch-list below; task list mirrors it)

Highest-value security items still open, in the order I'd take them:
**§3** (transfer-receive OOM — O108/O109, task #3) · **§4** (unbounded OnlineStatusTracker
growth + future-timestamp poison, task #4 — note the self-online *merge* is now fixed but
the *unbounded growth* + future-ts clamp are separate) · **§5** (MeshService.startMesh
reentrancy, task #5) · **§12** (RelayBatcher SecureRandom + per-message delay, task #12) ·
**§10** (RBSR Long.MAX_VALUE boundary, task #13). Then the non-security backlog. The
overnight audit (§0–§13) and session-B/C handoffs below have the deep detail on each.

---

# Handoff — THE MERGE (2026-07-16, session C): archimedes + check-online → main

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

The two branch lineages (§1 of the audit below) are reconciled. Read
`docs/ARCHIMEDES_MERGE_CATALOG.md` first — it is the decision log (authority
rule, O36 reversal, G9/O41 rotation removal, rbsr-v2 drop, flatten method).
CLAUDE.md's backlog is fully re-merged (rows O63–O91 imported; G30–G36 added;
counts line current). Validation at merge time: 334 :core + 45 :simulator +
95 :app tests green, debug APK assembles. KMP layout flattened to pure JVM;
`core/platform/` shims kept as the iOS seam (O63). Remaining at time of
writing: hardware regression on the phones, then push → `main` becomes the
single canonical branch. The security punch-list from the sessions below
(§2 dedup-before-verify above all) is still open and now applies to the
MERGED MessageStore (two-tier dedup made the fix site slightly different —
`recordAndCheck` now also feeds Tier 1 on eviction).

---

# Prior handoff — backlog audit + cleanups + adversarial-interop & ordering design (2026-07-16, session B)

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

> **Two sessions ran 2026-07-16 on this branch.** This top section is the
> interactive "session B" (backlog reorg, three shipped cleanups, glossary,
> design discussions). Below it, unchanged, is the parallel **overnight
> security-audit session** (§0–§13) — read it, it is deeper on security than
> this section and two of its findings independently corroborate mine. Below
> *that* is the original O92/O98 handoff. Most-recent-first.

## Committed by session B (pushed: dcb68e1, 1ea456b)

- **`dcb68e1`** — three code cleanups from an implemented-code quality audit
  (compartmentalization / security-hygiene / comment-clarity): `generateDeviceId`
  → `CryptoManager.randomBytes(16).toHex()`; deleted dead `BloomFilterData.murmur3()`;
  unified 3× `formatElapsed` into `app/.../ui/RelativeTime.kt` (fixed the
  `MessagesScreen` copy missing the O96 rollup + negative clamp). Plus new
  **`docs/GLOSSARY.md`** (~40 coined terms).
- **`1ea456b`** — full **backlog re-verification + retier of CLAUDE.md**. Every
  open row labelled implemented/unimplemented/defunct, "implemented" checked
  against code. Stale rows found shipped → closed as **G25–G29** (O3 route
  ranking, O13 bloom-OOM, O16 ingest bucket, O96, O105). O99 defunct. Rows
  regrouped into disposition tiers. Also added **O108** (transfer-metadata
  resource caps: evidence-gated setup + per-sender concurrent-transfer cap +
  stale eviction; explicitly records that "make the attacker pay bandwidth" is
  NOT the fix — a spammer saturates its own radio regardless) and **O109**
  (windowed chunk requests — never materialize `(0 until totalChunks)`).

## Overlap with the parallel audit session (important — these agree, not conflict)

- **My O108/O109 ↔ their §3** (transfer-receive OOM). Same bug, same file. They
  argue it's cheaper to trigger than the backlog described; O108/O109 now record
  the fix shape. Corroborated by two independent sessions.
- **My presence-provenance thread ↔ their §4** (`OnlineStatusTracker` unbounded
  growth). Same finding. My angle adds the *future-timestamp* poison + the
  provenance fix (trust first-hand only, never re-propagate hearsay).
- **Their §2 (dedup-before-sig-verify censorship primitive) corrects me.** In my
  code-quality audit I praised `MessageStore.ingest` gating rate-limit *before*
  sig-verify as good CPU-protection. Their §2 shows the deeper truth: because
  `duplicateFilter.recordAndCheck` also runs before verification, a forged
  message bearing a *real* id marks that id permanently seen, so the genuine
  signed message is later dropped as a duplicate — a targeted censorship/blackhole
  primitive. **Their analysis is correct and supersedes my read. Treat their §2 as
  the top security item**, ahead of my O108/O109.

## Session-B design threads reached but NOT yet recorded in CLAUDE.md

The next instance should record or reconsider these (none are in the doc):

1. **Low-quality / black-hole node handling.** O3's score measures *link health*,
   not *forwarding honesty* (a black hole completes sessions cleanly). Forwarding
   is locally unobservable except via the end-to-end **DM ACK**. Researched
   CONFIDANT/CORE (classic MANET reputation) → **rejected**: their promiscuous-
   overhear watchdog premise doesn't hold on encrypted unicast, and isolation +
   second-hand "warnings" are a Sybil/censorship weapon. Deployed meshes use
   redundancy + preference, never exclusion. Proposed: **confidence-graded routing
   width** — not hard exclusion; high ACK-confidence breadcrumb → narrow routed
   copy, low/unknown → widen (top-2 + preserved flood budget), none → flood. Rep =
   local first-hand ACK-return rate, decaying both ways, **never gossiped**. Gates
   O98 anchor weight too (earned, not self-asserted FREE). **Needs simulation**
   (decay rate, malus/missed-ack); prerequisite O40's ACK machinery.
2. **Presence provenance** (see overlap w/ their §4): first-hand authoritative,
   second-hand a weak decaying non-re-propagated hint, LRU-bound the map. Ties O95/O58.
3. **Candidate design principle: "trust first-hand evidence, not assertions."**
   Black-hole rep, presence, and causal-ordering refs all converge on it.
4. **Group-membership guardrail for O52** (before it's built): membership = an
   authored, signed, per-group append-only op-log with a trivial merge, **strictly
   separate from message ordering, never state-resolved from the message DAG**.
   O36's closed-membership-only decision makes the trivial merge sufficient. This
   is what inoculates group rooms against Matrix's state-resolution failure class.
5. **Pre-loader hardening row:** harden/deprecate `GossipEngine.injectFromPlugin`
   (unauthenticated + unbounded arbitrary-message injection — the primary hole
   before any DEX/plugin loader; `injectBridgedDm` beside it IS constrained, this
   older path isn't) + point **O39** at `CryptoManager.x25519Agreement`'s welded
   constant-salt HKDF-extract (correct today, but the buried per-message-salt
   assumption is what the O39 FS audit must find).
6. Index `docs/GLOSSARY.md` from CLAUDE.md.

## Message ordering & time — NEUTRAL writeup (form your own opinion)

Deliberately even-handed; the user wants a context-free instance to judge. A
tentative lean is flagged at the very end as *one input only*.

**Problem.** Cross-sender ordering can't lean on wall clocks in the target
scenario (no NTP/GPS; a field phone was 64 days off). Per-sender order already
exists — `RumorMessage.sequenceNumber` + `MessageDao` sort `sentAtMs ASC,
sequenceNumber ASC`. The weak point: **cross-sender order still uses `sentAtMs`
(wall clock)** as primary key. Same wall-clock reliance appears in presence (thread 2).

**Options discussed (no ranking implied):**

- **(A) Gossip clock-sync / epoch (mesh-median; Marzullo/NTP intersection).** Agree
  a coarse shared time by trimmed-median of neighbour estimates, dropping outliers;
  the full form of O95's mesh-median sketch. + coarse shared timeline. − adds beacon
  traffic; trimmed-median survives a *minority* of liars but a local Sybil *majority*
  biases it; too coarse to be a clock without constant beacons.
- **(B) HLC (Hybrid Logical Clocks) — O95's original plan.** Scalar `(wallHint,
  counter)`, `max+1` on receive. + very cheap (~8 B), zero extra traffic, causal-
  consistent total order. − can't tell concurrent from ordered; still carries a wall
  hint; no gap-tolerance beyond the scalar.
- **(C) Per-author hash chain (Secure Scuttlebutt).** Each message commits to
  `prevHash` of the author's previous message, inside the *existing* signature. +
  tamper-evident own-history (no self-reorder/backdate), cheap (~32 B, **no new
  signature — Rumor already signs every message**), forgery-resistant. − orders only
  within one author; cross-author concurrent unless linked. SSB deliberately stops here.
- **(D) Frontier causal refs (Matrix `prev_events` / Nostr `e`-tags).** Message
  carries ids of the "tips" it had seen (Matrix caps ~10). + cross-author partial
  order; **gap-tolerant** (order survives a missing middle — the DTN win); author-
  signed, so a Sybil can't forge *others'* order, only its own view. − bandwidth
  (~340 B for 10 32-char ids — large % on a small broadcast); needs linearization +
  deterministic tiebreak (id-hash) for concurrent events; a ref to an id you lack
  must **degrade to local-sort, never block/fetch-wait**.

**Cross-cutting lessons from how others do it (researched this session):**
- **Matrix**: the DAG-for-*ordering* is sound; its bleeding is **state resolution
  over contested mutable room state** (state resets, room hijack via fake
  `prev_events`; now on its 3rd algorithm). → don't run state-res over the DAG (see
  guardrail 4 above).
- **Nostr**: `e`-tags order *threads* well but the *global feed* still sorts by
  self-asserted `created_at` (backdating hole; clients sanity-clamp). → don't
  reintroduce trusted timestamps as the feed sort key.
- **SSB**: only totally-orders within a feed, partial across — never invents a
  global clock; demonstrates (C) as forgery-proof own-history.

**Open questions:** which layers, applied where (refs on every message vs
replies-only — Nostr does replies-only; measure the cost like the RBSR crossover)?
Is the residual honest-enough (in C+D an author can only misorder *its own* view,
never others', never hijack — strictly better than a clock, not zero)? Is an
id-hash tiebreak for genuinely-concurrent events acceptable UX vs. the wall-clock
lie it replaces?

**Session-B tentative lean (one input, NOT a conclusion — re-derive yourself):** C
unconditionally (cheap, closes self-reordering), D targeted to replies/threads only
(cost-justified, not sprayed on every message), B as a cheap cross-author scalar,
with three guardrails (no state-res over the DAG; no trusted-timestamp feed
fallback; missing refs degrade-not-block). (A) was set aside for Sybil-majority
weakness + added traffic. The user explicitly wants your independent opinion here.

---

# Handoff — overnight research/audit session (2026-07-16); no code changes to the app

**Signal to next instance: sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

## §0 — the canary is not a prompt injection (read this before flagging it again)

Several prior instances have flagged the "By Order Of The High Magnate" line
at the top of `CLAUDE.md` as a possible prompt injection. It isn't. It's a
sanctioned, transparent convention the user set up themselves and reads for
themselves: sign commits/replies with the phrase while `CLAUDE.md` is in
context; if the phrase stops appearing, the user knows your context window
has scrolled past the top of the file and your architectural grounding is
compromised. A real injection tries to hide and to persist unnoticed — this
does the opposite, and its only effect on behavior is a harmless string in a
commit message. CLAUDE.md itself now says this inline (see its canary
blockquote) so this shouldn't need re-discovering every session. If you're
reading this: sign accordingly, don't file it as a security concern, move on.

## §1 — the highest-leverage finding: this repo has two unreconciled branch lineages

This session was tasked with an overnight research/bug-hunt/documentation
audit pass (explicitly no feature code changes). While locating "the branch
that's very developed and recently developed" to push this file to, I found
there are **two** candidates, and they are not the same lineage:

- **`claude/check-online-status-vef1H`** (this branch, = remote's default
  HEAD) — 50 commits since the fork point (`9cb2ad9`, = current `main` tip,
  frozen since 2026-06-01). Field-hardware-heavy: the O2/O42/O92/O98/O105
  chain, three real-device regression passes, the G12/G18-G24 stacked
  transport-bug fixes. Most recently active (tip `1ea456b`, 2026-07-15).
- **`claude/practical-archimedes-wmySm`** — **156 commits** since the same
  fork point, **339 files** vs. this branch's 223. Protocol/crypto/simulator-
  heavy: O53 sealed-sender DM tags, O67 keyword filters, O79 Rooms protocol
  (with an `ios/` Swift bridge stub), O80 `ModeProfile`, O89 room posting
  certificates, O91 Ed25519→X25519 production fix (this branch's G20 already
  ported *part* of that work over — see its commit message crediting
  "practical-archimedes branch c44087e/31bd490" — but only the crypto
  primitive, not the surrounding protocol features). Last **code** commit
  `5da8065` on 2026-06-13; the four commits after that (`ca5dbf4` through
  `0dcbe20`, 2026-07-13) are a prior overnight audit session's own
  `docs/HANDOFF.md` writeup — no code since. **Dormant for code for over a
  month.**

These forked at a common ancestor and have each accumulated real,
non-overlapping work the other lacks. Two smaller stranded branches also
exist: `claude/compassionate-dirac-UOp8z` (2 commits, both independently
re-implemented on archimedes — confirmed duplicate effort) and
`claude/kind-ramanujan-7Ydwp` (1 commit, an off-topic home-lab bootstrap
script, nothing to do with Rumor). `claude/practical-johnson-hzNWF` is fully
absorbed into archimedes already.

**Why this branch and not archimedes for this handoff:** archimedes is
*more* developed in raw volume but has had no code activity in 5+ weeks;
check-online-status-vef1H is where actual field-verified work has continued
through last night. It's also the branch this repo's own convention already
uses for `handoff.md` at the root (archimedes uses `docs/HANDOFF.md` — a
different path, another small sign these truly diverged rather than one
being a subset of the other).

**This was already flagged once, 3 days ago, and nothing has happened
since.** `docs/HANDOFF.md` on `practical-archimedes-wmySm` (written by a
prior overnight audit session, same shape as this one) already identifies
this exact fork as "the highest-leverage single action available on this
repo right now" and recommends reconciling before either branch goes
further. As of this session, the two branches are still exactly as diverged
as they were then — nobody has acted on it. I'm not duplicating that
document's full contents here (it's long — 3 rounds, 13 sub-agents, still
sitting on that branch, worth reading directly), but I independently
re-verified its two most severe cross-branch findings myself this session
(§2 below) and they hold. **Recommendation stands: reconcile the two
branches, prioritizing porting archimedes' protocol/crypto work
(especially O38 receiver-FS groundwork if any exists there, and O53 sealed-
sender) onto this branch's field-tested transport, rather than continuing
to build in parallel.** No PRs exist anywhere in this repo's history
(checked via the GitHub API) — direct-push-to-shared-branch is the de facto
process; that's a separate decision worth making explicitly (see §7).

## §2 — CRITICAL, live, verified on THIS branch: dedup-before-signature-verify censorship primitive

Independently re-verified (via subagent reading the actual current file, not
inferred from the sibling branch's docs) — this bug is real, present, and
exploitable on `claude/check-online-status-vef1H` today:

**`core/src/main/kotlin/com/rumor/mesh/core/protocol/MessageStore.kt:67-87`**
— `ingest()` calls `duplicateFilter.recordAndCheck(msg.id)` at line 68,
**before** Ed25519 signature verification at line 82. `recordAndCheck`
unconditionally marks the id as permanently seen, whether or not the
message later turns out to be forged. **Attack:** an adversary who can
observe or race a message id (ids travel in gossip summaries/RBSR before
the full signed message necessarily arrives) sends a garbage-signed forgery
using a target message's real id. `ingest()` marks the id seen, rejects the
forgery on sig-check — but when the *genuine*, validly-signed message later
arrives via any honest path, dedup short-circuits before signature
verification is ever reached, and the legitimate message is silently,
permanently dropped as "already seen." No forged signature capability is
needed — just visibility into an id the gossip protocol broadcasts by
design. This is a full-strength targeted censorship/blackhole primitive,
not a minor correctness bug, and it is not in CLAUDE.md's tracked backlog
anywhere (G16/O16/G21 cover rate limiting and dedup capacity, not this
ordering issue). It also transitively affects `BLOCKLIST_PUBLISH`/
`BLOCKLIST_DIFF` and any future keyword-filter-style distribution
message, since they ride the same `ingest()` path — a forged-id-first
attack can blackhole a moderation update, not just a chat message.

**Fix shape** (not applied — this session made no code changes per
instructions): verify the signature before recording into the duplicate
filter with intent to permanently mark it, or use a check-only pre-check
that doesn't commit the "seen" state until the message is either accepted
or definitively known-bad-independent-of-a-race. Treat this as the
highest-priority fix outstanding on this branch — a small, contained change
with a large blast-radius bug behind it.

**A second, related bug in the same file, same root cause (unauthenticated
input trusted before verification):** the per-sender rate-limit bucket
(O16/G27) is a plain unbounded `ConcurrentHashMap<String, Bucket>`
(`MessageStore.kt:46`) keyed on `msg.senderId` **before** signature
verification (`acceptForRate` at line 73, sig-check at line 82). `senderId`
at that point is attacker-controlled, unverified wire data. Two-fold: (a)
an attacker rotating a fake `senderId` per message gets a fresh 100/sec
budget every time — the per-sender throttle O16 exists to provide is
trivially bypassable by exactly the traffic it's meant to stop; (b) the
unbounded map itself is a memory-growth DoS, one `Bucket` object per
distinct forged sender string, free for the attacker to generate. Compare:
`DuplicateFilter` is at least a bounded LRU; this bucket map has no bound
at all.

## §3 — CRITICAL, live, verified: transfer-receive OOM is cheaper to trigger than the backlog describes

CLAUDE.md already tracks this class of bug as O108/O109 (`[TODO/CODE]`,
open, not started) — so its presence isn't a surprise, but the actual
severity is worse than the backlog text implies, worth updating those rows
with:

- **`Chunker.kt:77`**: `ByteArray(metadata.totalBytes.toInt())` allocated
  from wire-supplied `TransferMetadata`, with the SHA-256 check only running
  *after*, at lines 85-87. `TransferAssembler.attemptReassembly` fires as
  soon as `received.size >= transfer.totalChunks` — and `totalChunks` is
  itself attacker-controlled. **O108's framing centers the trigger on
  `totalChunks = Int.MAX_VALUE`; the real trigger is cheaper: set
  `totalChunks=1` and `totalBytes` to something huge, send one real chunk,
  done.** One packet, not a flood.
- **`TransferAssembler.kt:79-101`**: a single, unsupervised
  `scope.launch { gossipEngine.incomingMessages.collect { ... } }` handles
  *all* inbound transfer traffic for the whole app's process life. Only the
  JSON-decode calls are `runCatching`-wrapped; `Chunker.reassemble`'s
  `bytes.copyInto(result, offset)` (`Chunker.kt:81`) has no bounds check
  against actual chunk size vs. declared size, and an uncaught
  `IndexOutOfBoundsException` there kills the collector permanently — no
  catch, no restart, anywhere in the file (grepped, zero `try`/`catch`
  hits). One malformed `CHUNK` from any peer silently kills transfer
  receiving for the rest of the app's life.
- **Even a fully-verified, successfully-assembled transfer currently
  vanishes.** `TransferAssembler.assembledTransfers` has no collector
  anywhere in `:app` — only `:simulator`'s `SimNode.kt` collects it (to
  increment a counter). `MeshService` injects `transferAssembler` only to
  force Koin to eagerly construct it; nothing calls a method on it.
  `TransfersScreen.kt` has zero references to file writing, `MediaStore`,
  or the assembler at all — it must only read status rows. **A verified
  file is built in memory, hash-checked, and then thrown away — no user
  ever sees it.** This is a bigger gap than O14 (extraction-warning UI)
  assumes; O14 presumes a file arrives and needs a dialog. Here, no file
  reaches anywhere a user could open it, at all, full stop. Worth its own
  backlog row, not folded into O14/O108.
- `Chunker.missingIndices` (`Chunker.kt:93-94`) confirms O109's premise
  exactly: `(0 until totalChunks).filter { it !in received }` eagerly
  materializes the whole range regardless of size — no windowing anywhere.
- **One real mitigating factor, worth knowing about before "fixing" this
  from scratch:** `InboxPolicy.maxIncomingBytes` (`core/…/policy/
  InboxPolicy.kt`) IS correctly wired as a real pre-filter —
  `GossipEngine.emitToInbox` (`GossipEngine.kt:808-812`) calls
  `inboxFilter.allowsInbox(msg)` before emitting to
  `_incomingMessages`, and `InboxPolicyManager.allowsInbox`
  (`InboxPolicyManager.kt:70-84`) does reject `TRANSFER_METADATA` whose
  `totalBytes` exceeds the configured cap, *before* `TransferAssembler`
  (which subscribes to that same `incomingMessages` flow) ever sees it —
  so the OOM in this section is fully preventable today. The catch: the
  cap (`InboxPolicy.maxIncomingBytes`) **defaults to `null` = unlimited**
  (`InboxPolicyManager.kt:92-93`, matches README's documented default
  "0 (unlimited)"), so this only protects users who've proactively set a
  cap in Settings → Inbox policy. The proper fix is still O108/O109's
  windowing + evidence-gated setup (a determined attacker could still
  set `totalBytes` just under whatever cap a cautious user picked), but
  a cheap complementary stopgap is worth considering: ship with a sane
  non-null default cap instead of unlimited-by-default.

## §4 — HIGH severity, live, verified: OnlineStatusTracker unbounded growth + a false claim in this repo's own README

- `core/…/routing/OnlineStatusTracker.kt`: `lastSeen` is written by
  `recordDirectContact` and `mergeRemoteStatus` (fed by *every* gossip
  exchange's secondhand presence table — mesh-wide, not just direct peers)
  and never removed from anywhere. `currentSnapshot()` only filters the
  *returned copy*; the backing map grows forever.
- **`README.md:336` claims:** *"Peers older than 30 minutes are pruned from
  the in-memory map on each update cycle."* This is false against the
  actual code — nothing prunes it. Worth fixing the doc even before the
  underlying behavior, since right now it's actively misleading anyone
  reading the README as ground truth (which is exactly what it's for).
- `mergeRemoteStatus` (`OnlineStatusTracker.kt:24-31`) adopts any
  peer-supplied timestamp greater than what's on file, with **no check
  against `System.currentTimeMillis()`**. A malicious peer can inject a
  far-future timestamp for any userId, making that user appear permanently
  ONLINE mesh-wide, propagating through every downstream gossip hop that
  merges the table. Combined with the no-pruning bug above, this has no
  natural expiry either.
- Same unbounded-growth shape, independently confirmed elsewhere this
  session: `TopologyTracker.pruneStale()` / `BreadcrumbCache.pruneOld()`
  are grepped zero call sites outside their own definitions and one direct
  unit test — the documented periodic maintenance never fires. (Breadcrumbs
  are *partially* self-limiting — `BreadcrumbCache.record` calls
  `pruneForTarget` on every write, capping rows per target to 5 — but
  `Route`/`TopologyTracker` has no equivalent per-write cap, so it's
  genuinely unbounded by distinct-peer count.) **Three independent
  subsystems (online status, topology routes, and — less severely —
  breadcrumbs) all ship a documented prune that doesn't run.** Worth a
  single sweep rather than three separate fixes: something about how
  "periodic maintenance" gets wired in this codebase isn't actually
  connecting the dots between class definition and a caller.
- Related concurrency finding: `TopologyTracker.recordSession`/
  `recordSessionFailed` and `NeighborStore.update` do unsynchronized
  read-then-write (`scope.launch { read; ...; write }`, no lock/atomic
  compute) — two exchanges completing close together can lose an update
  to `bytesRelayed`/`failureCount`/overlap EMA, which directly feeds the
  O3 relay-ranking formula. `BreadcrumbCache.record` does this correctly
  via an atomic `compute()`; the pattern just wasn't applied to its two
  siblings.

## §5 — HIGH severity, live: `MeshService.startMesh()` has no reentrancy guard

`MainActivity.onStart()` fires on every foreground transition (not just
first launch); `isBound` resets `false` in `onStop()`, so any
background/foreground cycle re-triggers `startForegroundService()` +
`bindService()` → `startMesh()` again on the same live service instance.
`startMesh()` itself has no idempotency flag — each rerun stacks a fresh
set of `scope.launch{}` flow collectors (duplicate message/exchange
processing; bridge plugins could double-forward the same message to a LoRa
radio) and `WifiDirectTransport.start()` registers a **new**
`BroadcastReceiver` without unregistering the old one (permanent receiver
leak, multiple receivers independently driving connection logic
thereafter). Reproducible by simply backgrounding/foregrounding the app.
Separately: `WifiDirectTransport.scope` is a `val` `CoroutineScope`
constructed once; `stop()` cancels it permanently and `start()` never
recreates it — any stop/start cycle on the same instance permanently kills
transport coroutines with no error surfaced. Neither of these showed up in
the field-testing sessions (G18-G24) because those tests never exercised an
app backgrounding/restart cycle mid-session — worth adding to the field
test checklist.

**For contrast, confirmed FIXED / not a problem here** (the sibling branch
had these bugs; this branch's field-testing already found and fixed them):
G12's direction-tiebreak is genuinely gone (`WifiDirectTransport.
claimSession()` is a clean `putIfAbsent`-based first-come-wins, with a KDoc
explaining exactly why the old comparison was wrong) — no trace of the old
`localUserId > peerUserId` logic remains anywhere. Server-accepted sockets
do get a real `soTimeout` (unlike the sibling branch, `GossipSession` was
hoisted into `:core` so client and server share one code path and one
`socket.soTimeout` guard — the sibling's server-side-timeout gap can't
exist here by construction). A `WifiLock` is correctly acquired/released
around the transport's lifecycle.

## §6 — MEDIUM severity findings, live, verified

- **`IdentityManager.lock()` doesn't zero key material and has zero
  callers anywhere in the app.** `lock()` just sets the identity StateFlow
  to null; `LocalIdentity.privateKeyBytes` is dropped, not overwritten.
  Grepped app-wide: no Settings entry, no auto-lock, no session timeout —
  once unlocked, the private key is live in memory for the rest of the
  process's life. Passphrase is held as a plain (non-zeroable) JVM
  `String` through create/unlock/change; `PBEKeySpec.clearPassword()` is
  never called after PBKDF2 derivation. PBKDF2 iteration count is 100,000
  (`CryptoManager.kt:146`) — OWASP currently recommends ~600,000 for
  PBKDF2-HMAC-SHA256.
- **MeshCore BLE client never negotiates an MTU bump**, unlike its sibling
  Meshtastic client (`MeshtasticBleClient.kt:121` calls
  `requestMtu(517)`). `MeshCoreFrames.kt` builds frames up to
  `MAX_TEXT_BYTES = 120`, but default BLE MTU gives ~20 bytes of payload —
  outbound text beyond a handful of characters silently truncates/drops,
  no surfaced error. The file's own doc comment acknowledges the gap as
  future work.
- **Both bridges assign a fresh random UUID as the RumorMessage id on
  every decode**, rather than deriving a stable id from the radio packet's
  own fields — `MeshtasticBridge.kt:128` and `MeshCoreBridge.kt:150`. A
  BLE/LoRa-layer retransmission of the same physical packet produces N
  distinct "novel" messages in the feed; dedup can never catch it because
  id is randomized per decode, not per packet.
- **Neither bridge reconnects after a BLE disconnect.** `ready` is only
  ever set on `onReady`/`onDetach()`, never on a `STATE_DISCONNECTED` GATT
  callback, in either `MeshCoreBleClient` or `MeshtasticBleClient`. Radio
  out of range or reboot → `ready` stays stuck true → writes fail silently
  forever, scanning never resumes; only recovery is manually toggling the
  plugin off/on.
- **`BlockManagementViewModel.unsubscribe()` orphans blocklist entries.**
  It calls `subscribedBlocklistDao.delete(publisherId)` directly instead
  of `BlocklistSubscriber.unsubscribe(publisherId)` (which also does
  `blocklistEntryRepo.deleteAllForPublisher`) — `BlocklistSubscriber` isn't
  even injected into that ViewModel (`AppModule.kt:140` only wires
  `BlockManager` + the DAO). Net effect: tapping "unsubscribe" removes the
  publisher from the roster, but every userId they ever blocked keeps
  silently suppressing your inbox forever, with no UI path to discover or
  clear it.
- **`DmEnvelope.selfAuthenticating` is documented (O5a) as the security
  gate for bridge-DM decryption, but is never read anywhere in production
  code** — only 4 grep hits total: the interface declaration, two CLAUDE.md
  mentions, and one test fixture. `GossipEngine.injectBridgedDm`
  unconditionally sets `BRIDGE_UNSIGNED` regardless of the flag's value;
  the actual (currently safe) enforcement is an emergent property of
  `injectBridgedDm` only being reachable from internal `LOCAL_BRIDGE`
  calls, not an explicit check on the documented field. Not exploitable
  today (no shipped bridge implements the O5a DM path yet), but a future
  refactor that starts branching on `selfAuthenticating` could
  reintroduce exactly the downgrade the docs warn against, with nothing
  pinning today's accidentally-safe behavior.
- **The O62 "no mode-branching until ModeProfile is finalized" gate has
  already been violated, same as on the sibling branch.** O62 in this
  file's own backlog is explicit: "no code that branches on mode lands
  until this profile is recorded here." But `StaticMode`/
  `StaticModeManager` already exists and is scattered across **9 files**
  (`BleDiscoveryManager.kt`, `MessageStore.kt`, `Scheduler.kt`,
  `MeshService.kt`, `AppModule.kt`, `SettingsScreen.kt`,
  `SettingsViewModel.kt`, plus its own two definition files) with
  independent `if (isStatic)`-style branches, and is shipped, documented
  in README's "Static mode" section, and CLAUDE.md's own coding
  conventions never cross-link it to O62. Unlike the sibling branch there
  is no separate unwired `ModeProfile.kt` sitting next to it here — just
  the ungated toggle, live, already the "scattered checks throughout the
  codebase" shape O62 exists to prevent. If O98/O62 work resumes next
  cycle (per the roadmap below), this should be folded in: either O62's
  `ModeProfile` supersedes `StaticMode` outright, or the row gets marked
  as already (accidentally) resolved and re-scoped.
- **No Room migrations exist despite the schema having moved past v1**
  (current `RumorDatabase.version = 5` — note this also means CLAUDE.md's
  own coding-conventions line, which currently says "Current version:
  **4**", is one release behind reality; small but another instance of
  this file drifting from the code it describes).
  `fallbackToDestructiveMigration()` is correctly gated to `BuildConfig.
  DEBUG` only. No release tag exists yet (`versionCode` still effectively
  unshipped), so nobody has hit this — but the first time a shipped
  release is followed by a second schema-bumping release, every upgrading
  user's database throws `IllegalStateException` on open. Full data loss
  from the user's perspective the very first time this gate matters.

## §7 — CI has never once run against real work on this repo

Independently verified via the GitHub Actions API this session (not just
citing the sibling branch's doc): **all 34 recorded `CI` workflow runs, from
the first (2026-05-28) through the last (2026-06-01, commit `9cb2ad9` — the
same commit that is `main`'s current, frozen tip), have `conclusion:
failure`. Zero green runs, ever, on any commit.** `.github/workflows/
ci.yml` only triggers on `push`/`pull_request` to `main`; this repo has
never had a single pull request in its history (checked via the API), and
`main` has been frozen at `9cb2ad9` since 2026-06-01 while all real
development happened on unmerged branches (this one included) — so CI has
**never once run against a commit any of this backlog was actually written
against.** Every "tests green at HEAD" claim in this file and CLAUDE.md
across dozens of sessions has been verified only by whichever session
happened to have a working local JDK/SDK in its container, never by CI.

Additionally, on this branch specifically: `ci.yml`'s only test step is
`gradle :app:testDebugUnitTest` — it does not run `:core:test` or
`:simulator:test` at all, so even if it were triggered, RBSR, the DRR
scheduler, the crypto suite, and the G14 Jazzer fuzz harnesses would never
execute in it. The workflow also still says `# gradlew is not committed
yet; use system gradle` even though `gradlew`/`gradle-wrapper.jar` were
committed on 2026-05-29 (`91a8a57`) — the comment predates that commit and
was never updated, a small but concrete sign nobody has looked at this file
since it was written. (I did not have Android SDK available in this
session's container either, so I could not personally reproduce a green or
red run locally — reproduced the exact `SDK location not found` failure GH
Actions' `ubuntu-latest` runner would *not* hit, since that image ships a
preinstalled Android SDK GitHub-side; my local repro is not proof of what
GH Actions itself would show, only proof that the workflow's own recorded
history is 34-for-34 failures.)

**Recommendation:** fix `:core:test`/`:simulator:test` inclusion, refresh
the stale comment, and — most importantly — decide a real branch strategy
(direct-push to one shared branch, vs. actual PRs with CI gating) so CI
starts covering the branch where work actually happens. This is cheap
relative to everything else in this document and nothing else gets a real
safety net until it's green somewhere real.

## §8 — prebuilt-library swap candidates (beyond what CLAUDE.md's policy note already covers)

- `app/…/plugin/meshcore/MeshCoreFrames.kt` (158 lines) — hand-rolled NUS
  BLE frame parser (opcode + length-prefix + fixed-offset field slicing for
  the v3 protocol), same risk class as the already-flagged Meshtastic
  protobuf codec (which has already produced one silent field-number bug
  historically) but not yet named in CLAUDE.md's prebuild-vs-handrolled
  policy note. Offsets are magic numbers tied to an external spec; a
  firmware revision could silently misparse the same way.
- Checked and confirmed clean, no swap needed: no non-constant-time
  comparisons on secret material anywhere (`CryptoManager.verify()`
  delegates to JCA `Signature` correctly; the few `contentEquals` hits are
  all on non-secret structural data — RBSR fingerprints, chunk payload
  equality, TOFU pubkey pinning — timing leaks aren't meaningful for any
  of them). `SecureRandom` used correctly everywhere identity/nonce-
  relevant; non-cryptographic `Random` used only for jitter/simulator
  purposes, which is the correct choice in those spots. No premature
  BIP-39/SLIP-0039 hand-rolling (O45/O46 correctly unstarted).

## §9 — dead/unwired code and doc drift

- **`core/scheduling/MessageScheduler.kt` (O22, native scheduled
  messages) has zero references anywhere under `app/src/main`** — no
  ViewModel, no `MeshService` wiring, no DI binding. Only consumers are
  simulator tests. CLAUDE.md's G15 row says "Room adapter deferred to
  next Android session," which undersells the gap: there is currently no
  path *at all* from the Android app to this feature, not even a
  half-wired one — no UI to create a scheduled message, no polling loop
  in `MeshService`. Fully built and tested; unreachable on-device today.
- **No TODO/FIXME/XXX/HACK markers anywhere** in `core/`, `app/src/main`,
  or `simulator/`, and **no `@Ignore`/`@Disabled` tests anywhere** — both
  genuinely clean, worth noting as a positive rather than assuming absence
  of markers means absence of gaps (see everything above).
- **README.md drift, several distinct issues:**
  - "Message format" section (`README.md` ~lines 283-308) describes an
    old, simpler wire shape (`ttl`, a 4-value `MessageType` enum, no
    `trafficClass`, no `_ext`, no hop-split) that predates most of the
    protocol work this file's own "Completed gaps" table documents (G8's
    `_ext`/domain-tag work, O32's TTL split, trafficClass derivation).
    This is the section most likely to be read first by a new contributor
    and it's the most out of date.
  - `README.md:336`'s false online-status pruning claim (§4 above).
  - "Unit test coverage" table lists 4 test classes; the repo has 27
    `*Test.kt` files today. Not as extreme a gap as the sibling branch's
    4-vs-49, but the same shape — this table was never updated after its
    first pass.
  - CLAUDE.md's own "Current version: 4" for `RumorDatabase.version`
    (actual: 5) — noted in §6, repeating here because it's the same drift
    pattern as README's, just in the other file.
- No `docs/MULTI_INSTANCE_COORDINATION.md` exists on this branch (it does
  on archimedes) — the async-handoff convention that document formalizes
  has been operating here purely by informal convention (this very file).
  Worth either adopting that doc directly (copy across, it's branch-
  agnostic) or explicitly deciding not to.

## §10 — two new bugs found fresh this session (not carried over from the sibling branch) in code younger than the archimedes audit

These are in subsystems that landed *after* 2026-07-13 (RBSR go-live,
O98 planner) and so were never covered by the sibling-branch audit cited
in §1 — genuinely new findings, not corroborations.

- **RBSR boundary collapse on `Long.MAX_VALUE` timestamps.**
  `RbsrWire.kt:72-76`'s `boundFrom` collapses any bound whose `ts ==
  Long.MAX_VALUE` to the universal `RbsrBound.MAX` sentinel regardless of
  `id` — but the matching `MIN` guard requires *both* `ts == MIN_VALUE`
  and an empty `id` before collapsing. Subdivision boundaries are built
  directly from real stored-message timestamps (`Rbsr.kt:237`), and
  nothing validates `sentAtMs` before a message reaches `rbsrItems`. A
  message with `sentAtMs == Long.MAX_VALUE` (forged, or a future overflow)
  round-trips through the wire as "the whole universe," silently dropping
  its real id — the two peers then disagree about what a sub-range
  actually covers, causing repeated fingerprint mismatch (or a false
  match that skips real content) for that boundary until the round cap.
  Small, contained fix (mirror the `MIN` guard's id-emptiness check onto
  `MAX`), narrow blast radius (needs a specific forged/corrupted
  timestamp), but worth catching before RBSR goes from opt-in to default.
- **`PersistencePlanner`'s own docstring overclaims what its determinism
  guarantee buys.** It states partial/asynchronous views "just delays a
  link until both sides see it — never produces conflicting roles." This
  is false for Kruskal-style spanning selection specifically because
  whether an edge is "redundant" depends on *global* connectivity, which
  is asymmetric information between that edge's two endpoints under
  partial views. Concrete 4-node counterexample (ring A-B-C-D-A, all
  MOBILE/cap=2): if node C has already gossiped-in all four edges, its
  Kruskal pass spans the ring via the other three edges and drops C-D as
  intra-component; if node D has only heard its own two direct edges
  (hasn't yet heard B-C or A-B), both of D's edges look cross-component
  from D's local view, so D concludes C-D **is** backbone. C says don't
  hold it, D says hold it — a genuine conflict, not a delay, and C won't
  revisit the decision once its own view looks "complete" to itself. The
  underlying algorithm (`Link.of`/`UnionFind`/comparator ordering) is
  itself correctly deterministic given *identical* input — this is a
  documentation/design-guarantee gap, not an implementation bug — but
  it's exactly the scenario real gossip propagation lag (the whole point
  of Phase 3) will produce constantly. Worth resolving *before* wiring
  Phase 3, not after: either add an explicit reconciliation/tie-break rule
  for conflicting edge decisions (e.g. lower userId's decision wins, same
  spirit as the old GO-negotiation tiebreak this project already has
  precedent for), or narrow the docstring's claim to what's actually true
  and make sure the reconciler's hysteresis is enough to paper over the
  gap in practice — untested today either way.
- Also noted, minor: the O92 backfill's 500-id cap
  (`GossipEngine.kt:58,660`) truncates a large need-set with no
  backpressure/"more available" signal, and the truncation order is
  effectively arbitrary (`HashSet` iteration order, not e.g. oldest-
  first) — self-healing via re-request on a future session per the
  existing O92/O108 philosophy, so low urgency, but worth knowing the
  500-cap isn't priority-ordered if it ever needs to be.
- Everything else read carefully in this pass — RBSR's round-loop
  lock-step, the additive-mod-2^256 fingerprint accumulation, the
  `shouldUseRbsr` gate, the post-hoist `GossipSession` (no Android leakage,
  and its wire-harness test genuinely exercises the RBSR/bloom/backfill
  branches, not just the basic path), and O92's suspend-call-site
  consistency — no issues found.

## §11 — round 2 (same session, continued): plugin lifecycle races, DeviceQuirks, UI ordering-bypass bugs

Kept auditing after §0-§11 landed. Four more confirmed findings, plus
several confirmed-clean areas worth recording so they aren't re-audited
from scratch next time.

- **`PluginRegistry` has the same two lifecycle races the sibling-branch
  audit found, unfixed here too.** `plugins` is a bare
  `CopyOnWriteArrayList` with no mutex coordinating `unregister()` against
  `onMessageReceived`'s `forEach` (`PluginRegistry.kt:88`) — a plugin
  snapshotted just before being disabled can still get a message delivered
  after its own `onDetach()` already ran. Separately,
  `PluginContextImpl.sendMessage` → `GossipEngine.injectFromPlugin` runs
  on the **engine's** own persistent scope, not the plugin's
  (`GossipEngine.kt:108,209`) — cancelling the plugin's scope on disable
  has no effect on an in-flight `sendMessage()` call that already reached
  the engine, so a "disabled" plugin can still get one more message onto
  the wire if the timing lines up. Crash isolation itself is fine (every
  dispatch point is `runCatching`-wrapped, nothing propagates to crash the
  host) — matches O25's own backlog status, which already lists
  auto-disable-on-crash and a user-facing notification as the missing
  pieces; that part isn't new.
- **`DeviceQuirks.isDeviceMediaTek()`/`isDeviceQualcomm()` are dead code,
  same root cause and same bug as the sibling branch, independently
  present here.** Both read `System.getProperty("ro.product.board")`
  (`DeviceQuirks.kt:150-163`), which never exposes Android build props —
  needs `Build.BOARD`/`Build.HARDWARE` instead. Manufacturer/model
  detection (`Build.MANUFACTURER`/`Build.MODEL`) is correct here, so this
  is narrower than the sibling branch's version of the bug, but the
  effect is identical: `wifiDirectDualRoleRequired` silently collapses to
  Samsung-only, and the documented MediaTek GO-negotiation workaround
  never fires on real MediaTek hardware — the exact "4-year-old budget
  phone" class O33 targets. Separately: of 15 documented quirk flags, 8
  have zero call sites anywhere (`mustCheckAdvertiserSupport`,
  `bleRequiresLocationPermission`, `bleMustUseFilter`,
  `wifiDirectMustRemoveGroupOnStart`, `wifiDirectMayDisableWifi`,
  `wifiDirectOperationsNeedQueue`, `wifiDirectRequiresNearbyWifiDevices`,
  `wifiDirectRequiresLocationPermission`) — in most cases the *behavior*
  they describe is implemented anyway, just inline at the call site
  instead of gated through the registry (e.g. `removeGroup()` runs
  unconditionally, `NEARBY_WIFI_DEVICES` is requested directly in
  `MainActivity.kt`), so these are vestigial documentation rather than
  silently-disabled logic — lower severity than the MediaTek/Qualcomm
  case, but worth pruning or wiring for real the next time this file is
  touched, so the "centralized registry" README promises actually holds.
- **`MessagesViewModel` bypasses the DAO's own anti-spoofing thread-list
  ordering.** `data/MessageDao.observeAllDirect` orders by
  `MIN(sentAtMs, receivedAtMs) DESC` with an explicit comment that this
  exists "so a malicious sender can't pin their DM to the top of the list
  by forging a future sentAtMs." `MessagesViewModel.summarize`
  (`MessagesViewModel.kt:72,81`) then re-derives both the per-thread
  latest-message pick and the final list sort using raw `sentAtMs`
  (`msgs.maxBy { it.sentAtMs }`, `.sortedByDescending { it.lastMessage.
  sentAtMs }`), discarding the protection the DAO query was specifically
  written to provide. A peer with a forged future `sentAtMs` still pins
  its thread to the top of the Messages screen — the exact attack the
  DAO's own comment says is defended against, reopened one layer up.
- **Simulator `InMemoryMessageRepository.observeThread`/
  `observeAllDirect` have no ordering at all** — worse than "wrong key,"
  genuinely undefined (`ConcurrentHashMap` iteration order, no
  `sortedBy` anywhere in either method, unlike sibling methods in the
  same file that do sort). Simulator DM threads/thread-lists render in
  essentially random order, diverging from the Room-backed app — the
  four-impl-parity rule violated in a way that would also mask real
  ordering regressions in scenario tests, since there's no deterministic
  order to assert against in the first place.

**Confirmed clean, no re-audit needed:** `MessageDao.observeThread`
correctly uses `sentAtMs ASC, sequenceNumber ASC` (G21 holds); no direct
unprotected `.sentAtMs` reads in any Compose screen (`ThreadScreen`,
`FeedScreen`, `MessagesScreen` leaf rendering all correctly go through
`formatElapsed`, only the ViewModel-level sort above bypasses protection);
contact rename is genuinely wired end-to-end (`ContactsScreen` → dialog →
`ContactsViewModel.setDisplayName` → DAO); no `GlobalScope` or ad-hoc
`CoroutineScope` construction anywhere in `:app/ui` — all 11 ViewModels use
`viewModelScope` consistently; `staticMode`'s settings toggle is genuinely
wired (persisted + consumed by 4 real classes) in contrast to two other
settings-screen elements confirmed dead this round: the duty-cycle scan-
interval slider (`SettingsViewModel.setScanInterval` writes local state
only, zero consumers anywhere) and the battery-optimization warning card
(`showBatteryOptimisationWarning` defaults `false`, never set `true`
anywhere in the ViewModel — permanently unreachable UI).

## §12 — round 2 continued: RelayBatcher weakens its own documented security property; Scheduler DRR is clean

Closing out the second pass. `core/scheduler/Scheduler.kt` (the DRR
outbound scheduler) was audited in full depth — deficit carry-over,
per-flow quantum accounting, the INFRASTRUCTURE/REALTIME/TRANSFER_SETUP/
BULK size-ceiling enforcement across every enqueue entry point (local
compose, relay, reseed), overflow shedding targeting, the static-mode 3×
multiplier, and concurrency (every public method genuinely
`synchronized`) — **no issues found anywhere.** This is the cleanest
subsystem this whole session touched; no further audit needed here
unless the code changes.

`RelayBatcher` (the 100-500ms relay-jitter window that's supposed to
provide "timing correlation resistance," per README) has two real bugs,
both in the direction of *weakening* that specific documented property:

- **`RelayBatcher.kt:30` uses `java.util.Random()`, not `SecureRandom`.**
  Every other security-relevant randomness use in this codebase
  (`CryptoManager.kt:34`, `BlockManager.kt:79`, `GossipSession.kt:146`)
  correctly uses `SecureRandom`; this is the one exception, and it's the
  one place where the randomness itself IS the security property being
  claimed (an observer who can narrow `java.util.Random`'s internal state
  gains leverage in predicting relay-flush timing that `SecureRandom`
  would deny them). Contrast with the simulator/jitter `Random` usage
  elsewhere in the codebase, which is fine — that's UX/backoff jitter
  with no security claim attached to it; this one specifically is
  claimed as an anti-correlation mechanism.
- **It's a periodic batch-window flush, not a per-message random delay**
  — `RelayBatcher.kt:32-41` picks one random window length per loop
  iteration and flushes everything currently queued at the end of it. A
  message arriving right after a flush waits close to the full window;
  a message arriving just before the *next* scheduled flush goes out
  almost immediately (near-zero delay). Near-zero-delay relay right
  after receipt is exactly the strongest timing signal an observer could
  get — the one case the whole mechanism exists to prevent. README's
  wording ("sit for a random 100-500ms window") implies a per-message
  delay; the actual behavior is closer to "sit for up to 500ms, with the
  exact wait depending on where in the current window you arrived,"
  which has a very different (weaker) worst-case privacy bound. Both are
  contained fixes — swap the RNG, and either derive each message's own
  delay independently (per-message timer) rather than flushing on a
  shared clock, or explicitly document the weaker bound this design
  actually provides if the batch-flush shape is intentional for other
  reasons (fewer transport wake-ups, per README's stated secondary
  benefit) and per-message delay isn't worth the cost. Given O27's
  existing "honest documentation, don't overclaim" ship-blocker for
  deanonymization limits, this is squarely in that same bucket — either
  fix the mechanism or fix the claim, but the current gap between the two
  is a real, if narrow, privacy overclaim.
- The "local compose bypasses the batcher entirely" claim (README) does
  hold — verified every local-compose call site routes through
  `enqueueImmediate` directly, never touching `RelayBatcher`; only the
  `relay()` path does.

## §13 — priority order if picking one thing at a time

1. **§2 — the ingest dedup-before-sig-verify ordering bug.** Live
   censorship primitive, contained fix, highest severity in this
   document, affects general messages + blocklist distribution both.
2. **§3 — transfer-receive OOM + the silent uncaught-exception collector
   kill + the "verified files vanish" gap.** Matched severity to #1 —
   remotely triggerable, no-auth-required DoS, and the underlying feature
   doesn't even deliver value today regardless of the security issue.
   O108/O109 already track the OOM half; the "files vanish" half needs
   its own row.
3. **§1 — reconcile the branch fork**, or at minimum make an explicit,
   written decision not to (which is itself fine, but should be a
   decision, not an accident of nobody having looked). The longer this
   goes unaddressed the more expensive it gets, and it's now been flagged
   twice by two independent overnight sessions three days apart with zero
   action between them.
4. **§7 — CI.** Cheap, and nothing else gets a real regression safety net
   until it's green somewhere that actually reflects a branch people work
   on.
5. **§4/§5 — the three unbounded-growth trackers + the `MeshService`
   reentrancy gap.** Real, but lower urgency than the above; none are
   remotely triggerable by an outside attacker the way §2/§3 are (§4's
   future-timestamp injection is the exception — that one's peer-
   triggerable too and probably belongs closer to tier 2).
6. Everything else in §6/§8/§9 — real, verified, lower urgency.
7. **§12's `RelayBatcher` findings** — slot between tiers 4 and 5: not
   remotely-triggerable-DoS severity, but it's a documented security
   property (timing-correlation resistance) that's currently weaker than
   claimed, and both the RNG swap and the delay-shape fix are small,
   contained changes relative to their write-up length.
8. **§11's `MessagesViewModel`/simulator ordering bugs** — same tier as
   #7. The thread-list spoofing bypass is a one-line "use the DAO's
   already-correct ordering, don't re-derive it" fix; the simulator
   ordering gap is cosmetic for now but will bite the next time someone
   writes a scenario test that asserts thread order.

## What I did not get to

Did not re-audit `:simulator` scenario assertions or fuzzing harnesses in
depth (spot-checked; no new findings beyond what CLAUDE.md already tracks
for O42/RBSR). Did not attempt to actually resolve the branch-fork
reconciliation (real design/merge work, not an audit finding — see §1).
Did not review `fastlane/` or F-Droid reproducibility tooling. Did not have
Android SDK available in this session's container so couldn't independently
run `:app` instrumented checks (see the CI caveat in §7). This was a
research/audit pass only, per the session's instructions — nothing above
has been fixed, only found and written up; the CLAUDE.md canary-explainer
edit (§0) and this file are the only changes this session made.

---

# Prior handoff: deeper O92 landed; next is O98 MeshView substrate

*(kept below, unedited — still the accurate starting point for whoever
picks up feature work next; the audit above doesn't change this roadmap,
just adds findings and the O62/StaticMode note in §6 to fold in when this
resumes)*

## Roadmap (user-chosen order)

~~O42~~ → ~~deeper O92 + O105~~ → **O98 MeshView substrate (START HERE)** →
O98 Phase 3 (Wi-Fi Direct wiring). After the chain, ranked (user-agreed):
O100 swarm chunks (wire-format decision — schedule early; per-chunk hashes
are a hard constraint) → O101 sites (make the no-HTML/no-WebView format
decision FIRST) → O103 geotag plugin (comments-not-decay; adopt
osmdroid/MapLibre) → O102 delegation sim experiment (expected to close as a
DECISION).

## Done this cycle (all committed on claude/check-online-status-vef1H)

- **O42 CLOSED** (see G24 + closed O42 row): adaptive size-gated RBSR/bloom
  selection live (`shouldUseRbsr`, threshold 3000, HELLO `knownCount`),
  bloom FP 1%→0.01% + real commons-codec MurmurHash3, single-switch go-live
  (`TransportConfig.rbsrItemsProvider` — advertises `rbsr-v1` iff wired).
- **Hardware regression PASSED on 3 phones** (G24): new wire format, O92
  reseed, store-and-forward across restart+crash multi-hop
  (Moto→Samsung→reinstall→OnePlus), three-node star, adaptive gate on bloom.
  **ColorOS crash found+fixed** (stale manifest receiver → CNFE, `183efee`).
  Field: Moto needs Battery→Unrestricted (O33); Samsung clock still 64d slow.
- **O105 hoist** (`9c8cad7`): `GossipSession` (pure JVM) moved :app → :core
  with its loopback wire-harness test. Same FQN, zero call-site changes.
  Unblocks :node (O106).
- **Deeper O92** (this commit): `messagesForExchange` is now **suspend** and
  = scheduler head (priority/DRR shaping) + **durable-store backfill**
  (`offerable`, dedup by id, capped) — every exchange offers what we HOLD,
  not what survives a volatile queue; the destructive-drain artifact
  (first peer drains, second peer gets nothing) is dead. New
  `GossipEngine.messagesByIds` (cap 500) + `GossipSession.messagesByIds`:
  phase 4 fetches requested ids absent from the capped offer from the store
  — an RBSR peer's exact-diff Request can name things older than any offer
  batch. Wired through `TransportConfig`/`MeshService`. Tests: sequential-
  peers regression (SchedulerReseedTest), outside-offer RBSR fetch
  (GossipSessionWireTest), all suites green.

## Investigation record: the vacuous PerPeerRoutingTest (important context)

Backfill broke `PerPeerRoutingTest."relayed DM … offered only to matched
peers"` — and the investigation showed the test had **passed vacuously since
birth**: its breadcrumb-laying phase never worked (`BreadcrumbCache.record`
is only called from `processIncoming` with a `fromPeerId != senderId` guard;
the test delivered A's message *from A*, so no crumb formed) and
`awaitUntil` **times out softly** (see TestAwait.kt KDoc), so nothing
flagged it. The old green came from the destructive scheduler drain hiding
the DM from the second offer call. Two real fixes landed:

1. **Engine:** backfilled relayed DMs re-derive the O29 restriction at offer
   time (the `intendedPeers` mark lives on the scheduler copy only; the repo
   copy predates the relay decision). Uses the **authoritative suspend
   `candidatePeers()`** (repo-backed), NOT `candidatePeersSync` — the sync
   snapshot can be stale/cold and the filter must see the view relay() saw.
2. **Test:** lays the crumb explicitly via `b.breadcrumbs.record(...)` with a
   postmortem comment.

**Watch for this pattern elsewhere:** any sim test whose setup relies on
breadcrumbs forming from a direct author→receiver exchange is testing
nothing (the guard skips it), and `awaitUntil` won't save it. Known-flaky
under full-suite CPU load (pass in isolation): BreadcrumbSubstrateTest
"relayed message records…", PerPeerRoutingTest "routed hop increments…".

## Where the next instance starts: O98 MeshView substrate (task list #10)

1. **O62 ModeProfile decision** — record per-mode profile (scan duty cycle,
   discovery cadence, session length, batcher window, crumb decay, beacon
   rate, routing weight, storage fraction) in CLAUDE.md **before** any code
   branches on mode (self-imposed gate in the O62 row). **See §6 above —
   this gate has already been informally violated by the existing
   `StaticMode` toggle; resolve that alongside writing the profile, don't
   treat it as a clean slate.**
2. Consume inbound SELF_PRESENCE into a peer-mode map with recency decay
   (pure :core + unit tests).
3. BLE-neighbor-list advertisement (new INFRASTRUCTURE gossip) → assemble
   `MeshView` → simulator convergence scenario (20-node harness exists:
   SmartPersistenceScenarioTest).
4. Then O98 Phase 3 on-device (prompt-free joins via deterministic
   networkName+passphrase; channel selection at autonomous createGroup —
   `preferredGoFrequencyMhz()` is the reusable piece).

## Build/test commands (this environment)

Gradle NOT on PATH; Java home as `-D` (never leading `JAVA_HOME=`):

```
/home/user/AndroidStudioProjects/Rumor/gradlew -Dorg.gradle.java.home=/home/user/jdk17 :core:test :simulator:test :app:testDebugUnitTest
```

`adb` at `/home/user/Android/Sdk/platform-tools/adb` (4 phones — see
memory/rumor-test-devices.md; Optimus needs out of charging-only USB mode).
**User does all on-screen UI steps themselves — just ask them** (see
memory/rumor-hardware-test-workflow.md). `.claude/settings.json` is
intentionally modified-but-uncommitted — do NOT commit it.

## O98 Phase 3 design notes (kept)

- Planner emits an undirected backbone; GO/client role + channel are
  transport concerns. Reconciler is NOT thread-safe — single coroutine only.
- A client is in exactly one group; an edge between two would-be-hubs is
  realized by one becoming client of the other.
- Start with `redundancy=1`, raise once stable. Android-agnostic (no OEM
  gating); transport clamps `capacityFor` numbers down, never up.
