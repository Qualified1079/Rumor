# Handoff — overnight research/audit session (2026-07-23) — no code changes

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary — see CLAUDE.md's
own top-of-file note: it is a transparent, self-declared context-truncation
detector, not an injection; sign accordingly, no need to flag it. If another
instance keeps flagging it, this line is meant to settle that.)**
**Git: commit + push directly to `main`, no feature branch. Read-only research
session — zero source-code edits, docs-only commit.** Pulled `2e66ce3` (O123 +
O130 e/b, landed mid-session by a concurrent instance) before writing this.

This was a scheduled/unattended run: read the codebase broadly, no code
changes, flag bugs/stale code/prebuilt-swap candidates. Six parallel
research agents each took a slice (crypto/protocol/wire, routing/trust/
runtime, transport/plugins/bridges, data/DI/UI, simulator/node test quality,
deps+docs+iOS-port consistency), each instructed to cross-check CLAUDE.md/
docs/COMPLETED_GAPS.md first so findings below are net-new, not re-filed
known items, unless explicitly noted as "still open, re-verified." Two
findings below (RoomMessageMalformedTagTest vacuousness, plugin
dispatch-vs-unregister race) were independently fixed by the concurrent
instance's O129/O123 commits while the audit was running — left in below
only as confirmation the audit agents were finding real things, marked
CLOSED-DURING-SESSION.

Not filed as CLAUDE.md rows (didn't want to fight the concurrent instance's
edits to the same file — see `docs/MULTI_INSTANCE_COORDINATION.md`). Next
instance: triage these, assign O-numbers, and fold the real ones into
CLAUDE.md's backlog.

## Highest severity — recommend acting on these first

**1. `MessageStore.signableBytes` has no field delimiters — a relay can splice
content across field boundaries without invalidating the Ed25519 signature.**
`core/src/main/kotlin/com/rumor/mesh/core/protocol/MessageStore.kt:286-297`.
The signed transcript is `"rumor-msg-v1:" + id + senderId + senderPublicKey +
sequenceNumber + sentAtMs + type.name + (payload?.content ?: "") +
(encryptedPayload ?: "") + (recipientId ?: "")` — plain concatenation, zero
separators. Every *newer* signable-bytes helper in the codebase
(`prekeyPublishSignableBytes`, `roomPostingCertSignableBytes`,
`multiRecipientEnvelopeSignableBytes`, `bridgeVouchedSignableBytes`,
`messageDeleteSignableBytes`) already inserts `'|'` between fields — this,
the original and most-used one, never got that treatment. Concretely: a
BROADCAST with `payload.content = "Meet at the north gate at 9pm"`,
`encryptedPayload = null` produces the identical trailing bytes
(`content + "" + ""`) as a *tampered* copy with `payload.content = "Meet at
the north gate"`, `encryptedPayload = " at 9pm"` (BROADCAST display code
only reads `payload.content`, so the tampered version silently displays as
a truncated message with a valid signature). `MessageStore.ingest` has no
structural check that `encryptedPayload` is null for a BROADCAST or any
other cross-field consistency check. This is a real integrity break in the
core message-authentication scheme, wire-format-affecting to fix (needs a
`rumor-msg-v1:` → `v2:` bump per `docs/RENAMED_FIELDS_NEVER_REUSE.md`
discipline) — recommend length-prefixing every field
(`field.length.toString() + ":" + field`) rather than a bare delimiter,
which is theoretically forgeable itself if a field could contain the
delimiter. Same fix should probably be back-ported to the `'|'`-delimited
newer functions at the same version bump.

**2. iOS Swift crypto bridge (`ios/RumorCryptoBridge.swift`) is behind the
current wire format — confirmed, not just doc-stale.** Missing the `aad`
parameter on `aesGcmEncrypt`/`aesGcmDecrypt` (load-bearing since O76 —
binds `originalLength` into the GCM tag; a message won't decrypt without
it) and missing both Ed25519→X25519 conversion methods the JVM side uses
for every DM/Room decrypt. An iOS build compiled from this file today would
silently fail to decrypt any O76-compressed DM and would be unable to
decrypt any DM or encrypted Room message at all (no ECDH conversion path
exists). This is O122's already-filed item, re-verified true today — flagging
at higher severity than a doc-drift row because it's actually a
build-breaking crypto gap, not prose. `docs/IOS_SWIFT_BRIDGE_SPEC.md` and
`ios/RumorCryptoBridge.swift`'s own comments *also* both independently
describe the DM-crypto KDF as PBKDF2 (`PlatformCrypto.pbkdf2`) — it's been
HKDF-extract/HMAC-SHA256 since G20; a from-scratch iOS implementer following
the spec literally would derive the wrong key. `CryptoManager.kt` itself has
three separate stale-PBKDF2 comment blocks describing the same retired
scheme (lines ~57-60, ~166-177, ~191-196) — one of the three is followed
three lines later by a *correct* explanation, so the file contradicts
itself in-place. `docs/IOS_PORT_PHASE_1_HANDOFF.md` additionally assumes a
KMP `iosMain`/`commonMain`/`jvmMain` source-set split that the
`practical-archimedes` merge (G30) reverted back to flat `core/src/main` —
that whole doc's premise is moot; recommend deleting or rewriting it rather
than patching.

**3. `WifiDirectTransport.stop()` permanently kills the singleton's
coroutine scope — a second `start()` in the same process silently stops
doing real work.** `app/src/main/java/com/rumor/mesh/core/transport/
wifidirect/WifiDirectTransport.kt:62,314`. `scope` is a `val` (never
reassigned), `stop()` calls `scope.cancel()`; Koin registers this class as
a `single` (`AppModule.kt:211`), so the same instance survives repeated
`MeshService.startMesh()`/`stopMesh()` cycles. Once the scope's `Job` is
cancelled it cannot be revived — every subsequent `scope.launch{}` (the
server-socket accept loop, the client round loop, the GO-idle watchdog, the
priority watcher) is launched dead and never runs its body, with **no
exception surfaced anywhere** (a coroutine launched on a cancelled job just
silently never executes). `TransportConfig`'s own KDoc documents "Replace by
calling `stop()` then `start()` again after an identity change" as a
supported flow — it's broken. Compounding: `stop()` doesn't reset
`groupConnected`/`connectAttemptInFlight`/`discoveryInFlight`/etc, and
unregisters the broadcast receiver *before* `removeGroup()`'s disconnect
broadcast would arrive, so a live group at `stop()` time can leave
`groupConnected` stuck `true` forever, wedging `connectToPeer()`'s early
return on all future attempts. No `WifiDirectTransportTest` exists to have
caught a stop→start cycle. Fix shape: make `scope` a `var`, recreate it at
the top of `start()`; explicitly reset the mutable connection-state fields
in `stop()`; reorder `stop()` to tear down the group before unregistering
the receiver.

**4. Plugin lifecycle: two related but distinct gaps beyond the already-
tracked O25/O123.**
(a) `PluginRegistry.register()` (`app/src/main/java/com/rumor/mesh/plugin/
PluginRegistry.kt:47-63`, current post-O123 shape) still doesn't wrap
`plugin.onAttach(ctx)` in try/catch. Both bridge plugins launch coroutines
*before* their BLE `connect()` call inside `onAttach`; if a later line
throws (e.g. TOCTOU permission revocation), the scope/context are already
registered in `pluginScopes`/`pluginContexts` but the plugin was never added
to `plugins` — so `unregister()` (gated on finding it in `plugins`) is a
permanent no-op for that id, leaking the scope/coroutines/any opened BLE
handle for the process lifetime, surviving even a full `unregisterAll()` at
mesh-stop. Fix: wrap `onAttach` in try/catch, roll back the
scope/context registration on failure.
(b) The scope `PluginRegistry.register()` builds for each plugin
(`CoroutineScope(Dispatchers.IO + SupervisorJob())`) has no
`CoroutineExceptionHandler`. Both bridges `pluginScope.launch{}` their own
inbound-frame collectors directly (not via a `dispatch*` call site — O25/
O123 only cover those). An uncaught exception inside one of these
plugin-launched coroutines is fatal to the whole process on Android, not
just to the plugin — a materially worse failure mode than what O25 describes.
Fix: install a `CoroutineExceptionHandler` on the per-plugin scope at
creation, log + auto-disable that plugin (ties into O25's still-open
auto-disable-on-crash intent) instead of letting it reach the default
handler.

**5. The wire-format fuzzing safety net (O28/G14) cannot detect the crash
classes it's built for.** `core/src/test/kotlin/com/rumor/mesh/core/fuzz/
{SeedCorpusTest,WireParserFuzzers}.kt`. Every one of the 19 `@FuzzTest`
harnesses (and both `SeedCorpusTest` methods) wraps its parser call in
`runCatching { ... }.getOrNull()`. Kotlin's `runCatching` catches
`Throwable`, not just `Exception` — `SeedCorpusTest`'s own doc comment
claims the opposite ("an uncaught Error would propagate past it"), which is
false. `StackOverflowError` from `deepNested(64)` (the exact case the file
says it exists to catch), or any other `Error`/exotic `Throwable` from a
`kotlinx.serialization` regression, is silently absorbed before Jazzer's
crash-detection (which works by observing an exception escape the fuzz
target) ever sees it — a mutating `JAZZER_FUZZ=1` run would report clean
regardless of how broken a parser actually is. `SeedCorpusTest` additionally
has zero assertions anywhere in the file — every seed/parser pair is a
structural no-op. Fix: `getOrThrow()` instead of `getOrNull()` and drop the
outer `runCatching` in the fuzz harnesses (or explicitly re-throw non-
`Exception` `Throwable`s); add real assertions to `SeedCorpusTest`.

## High severity

**6. `PersistenceCoordinator.recent` (`core/.../routing/
PersistenceCoordinator.kt:43`) is a plain unsynchronized `LinkedHashSet`,
mutated and read from independent coroutines sharing `Dispatchers.Default`**
(a real multithreaded pool — unlike `SimNode`, which G41/O114 deliberately
pinned to one thread for exactly this reason). The class's own KDoc says
"Not thread-safe; drive it from one coroutine" but `MeshRuntime.start()`
launches the beacon loop, the backbone-recompute loop, and the exchange-
result collector as independent coroutines on the same shared scope, all of
which touch `recent`. Concrete race: an exchange-result collector calling
`onExchanged()` (mutates `recent`) concurrently with the recompute loop
calling `recompute()` → `recent.toList()` (iterates it) — possible outcomes
range from `ConcurrentModificationException` (SupervisorJob isolates it, so
that loop dies *permanently*, degrading backbone replanning silently until
process restart) to corrupted internal state. Load-dependent, worse under
real multi-peer field conditions — the exact class of bug the simulator's
single-threaded dispatch can't surface. Fix: same synchronization pattern
already used by `MeshViewTracker`/`OnlineStatusTracker`, or confine
`PersistenceCoordinator` calls to a `limitedParallelism(1)` sub-scope like
`SimNode` does.

**7. `BreadcrumbCache.snapshot` (in-memory `ConcurrentMap`,
`core/.../routing/BreadcrumbCache.kt:38`) is never pruned — unbounded growth,
free to trigger.** `record()` only ever adds keys (keyed by sender userId,
which is free to mint per O27/O51/O60); `pruneMaintenance()`/`pruneOld()`
only age out the *persistent* repo, never touching this in-memory map. A
modest flood of single-use signed messages from freshly-minted keypairs —
explicitly the multi-week uptime regime O55 targets — grows this map
forever; per-sender rate limiting (O16) doesn't help since a fresh sender
per message trivially bypasses it. Fix: LRU-cap `snapshot` (same pattern as
`MeshViewTracker`/`PresenceReplyGate`) or have `pruneOld()` purge it too.
Related, lower severity: `MeshViewTracker.pruneStale()`
(`core/.../routing/MeshViewTracker.kt:135`) is itself dead code — never
called from anywhere, so `PruneWiringInvariantTest` (the regression guard
built specifically because "this codebase has now produced three [orphaned
prunes]") has a fourth undetected instance. Bounded by `maxPeers=2000` LRU
internally, so lower severity than the breadcrumb case, but worth adding as
a fourth clause to that test.

**8. RBSR reconciliation has no per-round cap on frames processed —
CPU-exhaustion DoS from one packet.** `core/.../sync/Rbsr.kt:198-232`
(`respond`) iterates every frame in a peer's packet with no bound on count;
each `Fingerprint` frame triggers an O(N) linear scan (`SortedListRbsrStorage
.items`, no indexing) over the *entire local item set* plus one SHA-256 per
matched item. The only size gate, `MAX_RBSR_SESSION_IDS`, bounds the
accumulated *diff-id* count, not frames-processed or CPU spent — a flood of
mismatching `Fingerprint(MIN,MAX)` frames barely grows that counter (each
triggers a `subdivide`, not an id yield) while CPU cost keeps accreting. The
only real limit is the 4MB wire-frame cap, but a `Fingerprint` frame is
~120-150 bytes of JSON, so one 4MB packet carries ~25-30k frames — against a
long-lived anchor node's tens-of-thousands-of-messages store (the explicit
O55/O77 target regime), that's billions of comparisons from one packet,
repeatable every round for up to `MAX_RBSR_ROUNDS=12`. `Rbsr.respond` is a
tight synchronous loop with no coroutine-cancellation checkpoints, so
`GossipSession`'s 30s `withTimeout` can't interrupt it mid-call. Distinct
from the already-fixed O117 (memory/allocation side + id-count ceiling, not
per-frame CPU). Fix: cap `incoming.frames.size` per round to something near
the theoretical max useful count given `bisectionFactor=16`/`MAX_RBSR_ROUNDS
=12` (far below 25k), and/or memoize `fingerprint()` or reject
repeated/overlapping bound ranges within one session.

**9. Rooms write-permission enforcement is unwired and the threat-model doc
still asserts it's live — confirmed still true today, not just O122-stale.**
`composeRoomMessage`/`handleRoomMessage` (`GossipEngine.kt:715,1135`) never
reference `RoomPostingCertVerifier` — zero production call sites, only the
verifier's own unit test uses it. `docs/ROOMS_THREAT_MODEL.md:30-44` states
in confident present tense that peers verify a posting cert on every inbound
channel message. Any user can post to any room today regardless of
`MEMBER_ONLY`/`MOD_APPROVED` policy, contradicting the doc. Already filed as
O89; O122 calls for a "NOT YET ENFORCED, tracked as O89" banner on the doc
that still doesn't exist — this is a security-relevant doc actively
misleading a reader, worth prioritizing the banner (5-minute fix) even
before the real enforcement work lands.

## Medium severity

**10. Successfully-completed transfers never release their chunk BLOBs —
storage leak on the common/happy path.** `TransferAssembler.
attemptReassembly()` (`core/.../transfer/TransferAssembler.kt:187-213`)
reaches `updateStatus(COMPLETE)` but never calls `chunkRepo.
deleteAllForTransfer(transferId)` — contrast both other terminal paths in
the same file (`cancel()`, and the abandon-after-retries path that was
*fixed in this same day's session*, `docs/COMPLETED_GAPS.md` G48/O108) which
both do clean up. `ChunkEntity`'s own docstring says "stored until transfer
completes (then can be cleared)" — the "then clear" half doesn't exist for
the success case, so it's the opposite of what the leak-fix session
intended. Compounding: `TransferRepository.pruneOlderThan`, the documented
backstop, is (a) never called from anywhere in the tree despite the
interface docstring claiming "cleanup uses pruneOlderThan," and (b) even if
wired up, its Room SQL (`TransferDao.kt:31`) compares against lowercase
status literals (`'complete'`,`'abandoned'`,`'failed'`) against a column
storing the uppercase enum `.name` (`Converters.kt:29`) with default
case-sensitive SQLite collation — it would match zero rows on-device. The
in-memory version of the same method is correct, so this is also a Room⟷
in-memory parity gap (the `InMemoryTransferRepository.pruneOlderThan` would
"work" in the simulator while silently no-op-ing on Android). Fix: add the
missing `deleteAllForTransfer` call on the COMPLETE path; either wire up
`pruneOlderThan` somewhere periodic or fix its query casing (`COLLATE
NOCASE` or uppercase literals) first since right now it's broken twice over.

**11. `InMemoryMessageRepository.evictOldest` (`core/data/memory/
InMemoryRepos.kt:44-48`) ignores the documented `alwaysSave` contact
exemption that the Room DAO correctly implements.** `MessageStore.kt:36-39`
documents eviction as dropping oldest *non-always-save* records; Room's
query correctly LEFT JOINs contacts and filters on `alwaysSave`; the
in-memory version evicts strictly by age with no contact lookup at all. Same
bug class as the historical `_ext`-dropped-by-Room incident (commit
`d864365`) but inverted (in-memory diverges from Room, not vice versa).
Matters more than usual here because O106 explicitly plans to reuse this
exact file for the future `:node` headless relay under long-term storage
pressure — precisely the O55 target scenario where this divergence would
bite for real, not just in tests.

**12. Elapsed-time labels ("5s ago") in Feed/Thread/Messages screens freeze
at first composition and never update.** `FeedScreen.kt:95`,
`ThreadScreen.kt:146`, `MessagesScreen.kt:86` all compute `formatElapsed(...)`
inline with no ticker driving recomposition; all three `LazyColumn`s
correctly use stable `key = {it.id}` (good practice, but it means Compose
has no other reason to recompose an already-drawn row). Distinct from
G28/O96, which fixed the *rollup formatting* but not this *liveness* bug,
which predates and survives that fix. A message's displayed age is
permanently whatever was true the instant its row was first drawn. Fix
shape: a shared 30-60s ticker (`produceState`/`flow{while(true){emit(Unit);
delay(30_000)}}`) collected at screen level — same pattern `DebugMetrics
ViewModel` already uses correctly (G4).

**13. Two more test-quality gaps in the vacuous-test family this codebase
has repeatedly hunted (O129 pattern):**
(a) `BloomFalsePositiveTest` (`simulator/src/test/.../engine/
BloomFalsePositiveTest.kt:37-52`) measures empirical bloom FP rate across
set sizes/configured rates and only `println`s the result — zero assertions
in the file. A future regression to the murmur mix or `hashCount` cap could
silently multiply the real skip rate (the exact "late/undelivered chat
message" UX failure the file's own comment worries about) and CI stays
green.
(b) `RbsrSimTransportTest` (`simulator/src/test/.../engine/
RbsrSimTransportTest.kt:132-139`) hand-duplicates `MessageStore.
signableBytes` instead of calling the real function via a `MessageStore`
instance (as `MessageStoreIngestTest` in the same package correctly does)
— lower risk since it fails loud (signatures stop verifying) rather than
silently, but worth fixing given how central Finding #1 above makes this
exact function.
(c, informational — CLOSED DURING THIS SESSION) The audit agent
independently rediscovered `RoomMessageMalformedTagTest`'s vacuousness
(O129) and confirmed the real drop-path guard it was supposed to test is
correctly written — literally while the audit ran, a concurrent instance
landed `631ae3b` fixing exactly this. Left in only as external validation
that the audit methodology is finding real, previously-known-but-unfixed
issues, not noise.

**14. Bridge robustness gaps beyond the already-tracked O119
(no-reconnect):** re-verified O119's "neither bridge recovers from BLE
disconnect" is still true — `ready` is set once on connect, reset only in
`onDetach()`, never on `STATE_DISCONNECTED`; a radio hiccup means the
bridge silently drops all traffic until manually toggled off/on. Two new,
narrower findings in the same files: (a)
`MeshtasticBleClient.drainFromRadio()` (`app/…/plugin/meshtastic/
MeshtasticBleClient.kt:198-209`) has a lost-wakeup race — a `FromNum`
notification arriving while a drain is already in-flight is dropped with
nothing remembering to re-drain once the current pass terminates, which can
stall delivery of a specific queued packet until an unrelated later
notification happens to arrive (suspected — timing-window dependent,
plausible from the code but not exercised without hardware). (b)
`MeshCoreBridge.onFrame` (`app/…/plugin/meshcore/MeshCoreBridge.kt:120-123`)
routes `PUSH_CODE_RAW_DATA` (0x84) through the same decoder as
`RESP_CODE_CHANNEL_MSG_RECV` (0x11) based only on an inline comment ("some
firmware versions push channel msgs here"), no spec citation, no format
discriminator beyond a length check — a firmware whose raw-data-push payload
isn't byte-identical to a channel message but happens to be ≥11 bytes would
decode to plausible-looking garbage text displayed as a real bridged
message rather than being dropped.

**15. Backbone/persistence planner has no sybil resistance against a
free self-declared capacity claim (suspected, architecturally clear, not
simulator-confirmed).** `PersistencePlanner.capacityFor` ranks `FREE=8` as
the top tier purely from a self-asserted, cheaply-signable `SELF_PRESENCE.
mode`; `MeshViewTracker` admits any userId's declared mode with no trust/
friend gate; `BackboneRealizer` ranks hub candidates by `capacity DESC`
first. A sybil identity beaconing `mode=FREE` gets preferentially routed-to
by every honest node computing the same deterministic plan, and since
`GroupCredentials` are derivable/joinable by anyone, the sybil can actually
stand up the real Wi-Fi Direct group and become a real relay hub for a
segment of the mesh — a stronger version of the already-documented O51/O60
"local observer" floor (honest nodes *actively route toward* the attacker
rather than the attacker merely overhearing what passes in range). Doesn't
break DM confidentiality, but concentrates routing/relay metadata. The O135/
O136 trust-graph work is entirely decoupled from this — it only gates the
inbox/display layer, not `MeshViewTracker`/`PersistencePlanner` — so wiring
it up wouldn't protect against this even once done. Recommend a targeted
simulator scenario (mirror `SybilFloodScenarioTest`'s structure, assert on
`PersistenceCoordinator`/`BackboneRealizer` output instead of inbox
contents) before treating as ship-blocking.

## Lower severity / policy-consistency notes

- **`HmacSha256`/`HkdfSha256`** (`core/.../crypto/`) hand-roll RFC 2104/5869
  in pure Kotlin rather than `javax.crypto.Mac` (used elsewhere in the same
  package), specifically to stay portable for the still-live iOS/KMP port —
  a real tradeoff, not carelessness, but a literal violation of CLAUDE.md's
  own "crypto → always an audited library, never hand-roll... non-
  negotiable" policy for the exact category it's written to cover. Manually
  traced against RFC 2104/5869 and found correct — flagging as a policy
  inconsistency, not a confirmed bug. Now underpins O53 sealed-sender tags
  and O79 room-envelope keys, so worth the audited-library scrutiny those
  deserve even if portability is the reason to keep it hand-rolled.
- **`bridgeVouchedSignableBytes`** (`core/.../model/BridgeVouched.kt:73-86`)
  is dead code — `composeBridgeVouched` never calls it, signs through the
  ordinary `MessageStore.signableBytes` path instead (inheriting Finding #1's
  issue, not a dedicated field-scoped signature as `docs/wire-format.md`'s
  domain-tag table implies). Harmless today, misleading to a future auditor.
- **`MultiRecipientEnvelopeCodec.decrypt`** never checks `envelope.senderId ==
  publicKeyToUserId(envelope.senderPublicKey)`, unlike `PrekeyVerifier`/
  `RoomPostingCertVerifier` which both do this exact check for their
  equivalent fields. Not currently exploitable (the only consumer,
  `GossipEngine.handleRoomMessage`, never reads the inner `senderId` for
  trust/display — the outer, independently-verified `RumorMessage.senderId`
  is what's shown) but a latent gap a future refactor could silently
  reintroduce spoofing through.
- **`RoomTagMatcher.match`** uses `ByteArray.contentEquals` (short-circuiting,
  not constant-time) to compare HMAC-derived room routing tags — standard
  MAC-comparison anti-pattern, though practical exploitability over BLE/
  Wi-Fi Direct given scheduling jitter is doubtful and within the
  already-accepted O51/O60 local-observer floor. Worth a second look, low
  priority.
- **Dependency staleness** (no network access to confirm specific CVEs, but
  worth a manual check): `androidx.compose:compose-bom:2024.02.00` and
  `androidx.core:core-ktx:1.12.0`/`lifecycle:2.7.0` in `:app` are the
  clearest candidates — roughly 2+ calendar years behind the project's own
  internal dates by their apparent vintage. `io.ktor:*:2.3.7` in `:simulator`
  is on the 2.x line (3.x is current upstream); low blast-radius since the
  dashboard is localhost-only. `ch.qos.logback` was on the EOL 1.4.14 line
  with two unbackported CVEs when this audit started (CLAUDE.md O130-g);
  bumped to 1.5.13 by a concurrent instance (`7181fd3`) mid-session — closed,
  noting only as confirmation the audit's dependency read was accurate.
  `commons-compress:1.26.0`
  looks fine (post-dates the CVE-2024-25710/26308 archive-parsing fixes) but
  flagging since that library has a real CVE history and I can't confirm
  what's shipped since without network access.
- **README.md** — beyond what O122 already lists as stale (false 30-min
  online-status prune claim, self-contradictory bridge-"stubs" section),
  found three more stale claims not on O122's list: PBKDF2 iteration count
  says 100,000 (line 341) but the live default has been 600,000 since O115;
  the `MessageType` enum listing (line 319) shows only `BROADCAST|DIRECT|
  PING|PONG`, missing a dozen+ types added since (`TRANSFER_METADATA`,
  `ROOM_MESSAGE`, `BRIDGE_VOUCHED`, etc); the message-format field `ttl`
  (line 320) hasn't been the real field name since the O32 routedHops/
  floodedHops split — the actual field is `hopsToLive`. Fold into the next
  O122 sweep. Also: the "14 modules"/"~80 test files" O122 sub-items —
  module count is already fixed (README correctly says four today); test
  count has kept moving past even O122's own correction (counted 98 files
  matching `*Test.kt`-ish patterns today, README still says "~80" and O122
  said 77 at filing — treat as a moving target, not a single fixed number).
- **`docs/IOS_SWIFT_BRIDGE_SPEC.md`** itself (not just the Swift file)
  describes the retired PBKDF2 KDF twice (lines 63, 163) — a future
  implementer following the spec literally, not just the stale code
  comments, would build the wrong key derivation. New finding beyond what
  O122 tracked (O122 only flagged the Swift file + `CryptoManager` KDoc).

## Ranking / where to start
Most valuable first fix given severity × how load-bearing the code is:
**#1** (signature-splicing) → **#3** (WifiDirectTransport restart) →
**#2** (iOS crypto bridge, if/when iOS work resumes) → **#5** (fuzzing net) →
**#9** (Rooms doc-lie, 5-minute banner fix) → **#4** (plugin lifecycle) →
**#6/#7/#8** (concurrency/DoS trio in routing+sync) → the rest as capacity
allows. None of the above required touching source to find — this was a
read-only pass per the task; no tests were run.

---

# Handoff — autonomous Tier-2 security-hardening sweep (2026-07-23, overnight)

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**
**Git: commit + push directly to `main`, no feature branch.** Everything below
is committed AND pushed (`c3a99e5` at handoff time; tree clean). All suites
green (`:core` / `:app` / `:simulator` / `:node` compile+test).

> **Build note for a fresh instance:** there is NO `java` on `PATH`. Export
> `JAVA_HOME=~/jdk17` before any `./gradlew`. (Cost me the first build.)

## What shipped this session (pure code, no hardware needed — all JVM-testable)
Foundational audit-residue + Tier-2 blockers, closed most-foundational-first:

1. **O116 (Room schema export) → `[PART]`:** `room.schemaLocation` KSP arg set,
   `app/schemas/…/11.json` committed, `room-testing` dep added. Convention added
   to CLAUDE.md: bump `RumorDatabase.version` AND commit the new schema JSON in
   the same commit. Remaining = real `Migration` objects at first tagged release.
2. **O128 + O120 → G44/G45:** `OnlineStatusTracker.mergeRemoteStatus` clamps
   peer-asserted `lastSeen` to `now+2min` (poison no longer pins users online
   mesh-wide); the three orphaned prune loops (breadcrumb/topology/online-status)
   are wired through `GossipEngine.pruneMaintenance()`, ticked hourly by
   `MeshRuntime`. `PruneWiringInvariantTest` greps both halves (3rd time this
   codebase shipped a prune nobody called — guard the pattern now).
3. **O117 → G46:** `BloomFilterData.deserialize` binds claimed `expectedItems`
   to actual payload length BEFORE allocating (slow-DoS half of O13);
   `MAX_RBSR_SESSION_IDS=50k` cumulative ceiling in GossipSession + SimTransport.
4. **O118 → G47:** Meshtastic protobuf `readSubMessage` bounds check (was the
   only length path with none) + negative/overflow-length guards on
   `readBytes`/`skipField` (negative len previously REWOUND pos — silent desync).
5. **O121 residue + O130 grab-bag:** breadcrumb DIRECT hop budget (was using
   the broadcast budget); +2 missing domain tags in `DomainTagInvariantTest` and
   `docs/wire-format.md §6` (`rumor-dm-recipient-tag-v1:`, `rumor-room-posting-cert-v1:`);
   `selfAuthenticating` no-production-reader pin (SourceInvariantTest invariant 6);
   deleted dead `MessageDao.delete(id)`; `SecureRandom`→`Random` for the non-secret
   Meshtastic packet id.
6. **O115 (identity lock lifecycle) → `[PART]`:** `lock()`/re-unlock now zero
   `privateKeyBytes`; all derived AES wrapping keys `fill(0)`; `clearPassword()`
   + char-array zeroing in PBKDF2; **iterations 100k→600k (OWASP)** with
   `kdf_iterations` prefs versioning + auto-rewrap on first unlock; BlockManager
   export blob `v2:` prefix (legacy blobs still import). Remaining = Settings
   lock action + auto-lock (UI, → O143) and CharArray-from-UI threading.
7. **O108 + O109 → G48/G49:** transfer-metadata evidence-gating (watchdog arms
   on first real chunk), `Chunker.isPlausibleMetadata` (rejects the
   `totalChunks=Int.MAX_VALUE` OOM), `MAX_ACTIVE_PER_SENDER=8`, windowed chunk
   NACKs (`missingIndicesWindowed`, O(window) not O(totalChunks)), progress-aware
   watchdog budget, chunk-row cleanup on abandon.
8. **O39 → G50:** per-message key-lifecycle re-audit of the merged compress +
   sealed-sender paths. Constant HKDF salt confirmed CORRECT (freshness comes
   from the ephemeral key, RFC 5869). **Real gap fixed:** `x25519Agreement`
   never zeroed the raw ECDH secret — since the salt is public+constant, a
   leaked `shared` re-derives the AES key, so call-site zeroing of the derived
   key was worthless. `finally { shared.fill(0) }`, byte-transparent, benefits
   all consumers. SourceInvariantTest invariant 7. Sig-scope confirmed over
   ciphertext (no UKS surface).
9. **O129:** the vacuous `RoomMessageMalformedTagTest` now routes both a
   discrimination control AND the tampered message through real `SimTransport`
   (was asserting on a bare object never sent). Proves: control emits, tampered
   drops from inbox, tampered still stored (relay-never-filters).
10. **O123 → G51:** plugin disable-boundary race — `PluginHolder.alive` flag
    cleared before teardown, checked in the dispatch loop. `PluginDisableBoundaryTest`
    source-guards the ordering (flag write before scope cancel).
11. **O130 residue (a,b,c,e,f,g):** (a) `RelayBatcher` per-message release
    deadlines close the arrive-just-before-flush zero-delay timing leak;
    (b) sim `observeThread`/`observeAllDirect` sort to match Room; (e)
    `DebouncedHlcStore` coalesces HLC persistence (both hosts, flushNow on stop);
    (f) Meshtastic packet-id SecureRandom→Random; (g) logback 1.4.14→1.5.13;
    (c) dead `MessageDao.delete(id)` removed. Remaining O130: (d) SortedListRbsrStorage
    linear scan (defer — profile before large-anchor regime), (h) dependency-staleness
    audit (deferred, no confirmed CVE).

## Backlog bookkeeping this session
- O142 filed (pre-ship bridge live-check) in Tier 5.
- **14 pure-`[TODO/UI]` rows folded into one row O143** (bodies relocated verbatim
  to `docs/UI_BACKLOG.md`) — the consolidation the user asked for. O143's Step 0
  is a design sit-down on UI direction BEFORE implementing.
- **Open-work count 70 → 48 this session.** Closed to G-rows: O120/O128 (G44/G45),
  O117 (G46), O118 (G47), O108/O109 (G48/G49), O39 (G50), O123 (G51). O116/O115
  → `[PART]`. O129, O130(a/b/c/e/f/g), O121 residue (a/b/c) fixed in place.
- **Commit note:** use `git commit -F <file>` for bodies — inline backticks in a
  `-m` string trigger bash command substitution (mangled one commit msg early on;
  harmless, but annoying).

## Decisions I made autonomously (flag if you'd have done otherwise)
- **Extracted `Chunker.isPlausibleMetadata` as a pure fn** rather than test the
  reject path through a full GossipEngine — same property, unit-testable, better
  compartmentalization.
- **O115 iteration bump is format-versioned, not a hard cutover** — existing
  fleet installs (100k) keep unlocking and silently upgrade. No re-onboard.

## Waiting on the user (did NOT do — needs your call)
- **UI work (O143):** the automated loop said "do UI in one sweep," but per your
  own memory (UI ultra-low priority, needs a design sit-down first) and the row's
  Step 0, I did NOT start UI. It needs your direction on look/approach before any
  code. This is the one thing I deliberately deferred rather than build.
- **O115 auto-lock UX** (timeout length, lock-on-background?) — a product choice;
  the mechanism (`lock()` now actually zeroes) is ready for whatever you pick.

## Next foundational candidates (my planned order if I keep going)
The Tier-2 audit-residue is now largely cleared. Biggest remaining foundational
JVM-testable item: **O38 receiver-side forward secrecy** — the prekey rotation
scheduler + per-contact sender cache + `composeDirect` freshest-prekey selection.
The wire shape + `PrekeyVerifier` already shipped; this is the local-state +
scheduler plumbing. It's a multi-file FEATURE (not a surgical fix), so it wants
its own focused session rather than a tail-end slice — flagged as the next
session's headline. After that: O25 auto-disable remainder (plugin crash
isolation), O48 (bridge synthetic-userId ← pubkey-hash, needs the bridge path).
Deferred by design: O130(d) RBSR linear scan (profile first), O130(h) dep audit,
O121(d) SortedListRbsr scan, everything `[TODO/EMU]`/`[TODO/HW]`.

---

# Handoff — long live-testing + sybil-defense session (2026-07-22)

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**
**Git: commit + push directly to `main`, no feature branch.** Everything below
is committed AND pushed (`a20a00b` at handoff time; tree clean).

## Fleet + node state (start here)
- **All 3 phones on `0.6.13-o132-sizecap` (vc34).** Passphrase `passphrase1`.
- **Reflash is now HANDS-FREE (O138):** `adb -s <dev> install -r <apk>` then
  `adb -s <dev> shell am start -n com.rumor.mesh.debug/com.rumor.mesh.MainActivity`
  → a `BuildConfig.DEBUG` auto-unlock starts the mesh. No manual on-screen unlock.
- **Headless node (O106):** `./gradlew :node:installDist && node/build/install/node/bin/node --bind <lan-ip>`.
  Status page `http://127.0.0.1:8180/` (`/status`, `POST /send`, `--sybil <ip:port>`).
  **Restart the node FRESH after any fleet reflash** — it holds stale mDNS
  ports otherwise and sits at `peers=0/1` (the O93 periodic-re-resolve gap).
- **DB reads MUST be WAL-aware** — pull `rumor.db` + `rumor.db-wal` together or
  you get false negatives (recent writes sit in the WAL). Bit me twice.
- **Sim suite is memory-heavy** — `:simulator:test` now has `forkEvery=15` +
  `-Xmx2g`; still run `:core`/`:simulator`/`:app` separately, not all at once.

## What shipped this session (all committed/pushed)
1. **`:node` + `MeshRuntime` (O106 partial):** host-agnostic orchestration
   extracted from `MeshService.startMesh()` into `core/runtime/MeshRuntime.kt`;
   a 4th Gradle module runs the real engine over the G40 LAN transport with a
   localhost status page + `--sybil` harness. Phone behavior byte-identical.
2. **O126 → G43 (0.6.9):** HLC `SharedPreferences` Int→Long upgrade crash —
   audit-predicted, field-hit on the OnePlus, fixed (defensive read).
3. **O112 display cap (0.6.10):** a 500 KB unbroken-text broadcast wedged the
   feed via Android `LineBreaker` ANR. `ui/DisplayText.kt capForDisplay()` at 3
   render sites. (Also had to `run-as` DB-surgery the phone to remove the
   crasher row — see git log for the method.)
4. **O138 debug auto-unlock (0.6.11):** killed the reflash+manual-unlock loop.
5. **O132 broadcast size cap + head-of-line-block fix (0.6.13) — THE
   foundational fix this session.** One oversized (>4 MB) unchunked broadcast
   in a store re-offers every round; on real TCP its frame trips the 4 MB read
   guard, resets the session, and wedges ALL sync to that peer (field-confirmed:
   a tiny follow-up reached NONE of 3 phones). 3-layer fix: compose cap (64 KB)
   + ingest drop + **offer-batch byte budget** (`messagesForExchange` skips any
   message > `MAX_OFFERABLE_MESSAGE_BYTES`, trims batch under 4 MB). Compose cap
   validated live; `OversizedMessageTest` (3) covers the offer-batch skip.
6. **O136 friending (0.6.12):** `Contact.friended` (schema v11), Friend toggle
   in Contacts, `setFriended`/`isFriended` across all repos.
7. **O135(1) `KnownSendersInboxFilter`** wired to `friended` — `InboxPolicyManager`
   drops non-friends when `friendedSendersOnly`; relay+store untouched.
   `SybilFloodScenarioTest` proves 0-sybil inbox + relay preserved.
8. **O135(4) `core/trust/TrustGraph.kt`** — the web-of-trust hops frontier (SSB
   port), proven: SybilLimit per-attack-edge bound, **block-the-signer severs
   the subtree**, Advogato-inoculation (structural depth), Rumor's granular
   blocks (NOT SSB transitive — user decision). Plus topology test (fast-mixing
   caveat) + Ostra per-edge-credit vs global-bucket + **negative-control/sanity
   tests** (`assertThrows`-wrapped wrong claims).
9. **LAN port re-target** (`LanTransport.onPeerLocated` re-targets on port change).
10. **`docs/SIMULATOR_TESTING.md`** — the durable sim-testing reference
    (route bad input through the real transport, await final effect, **negative
    controls**, scope teardown). Read it before writing sim tests.

## Live findings (from the node as an adversarial instrument)
- **O124 verified** (announce + solicit both fire on hardware).
- **O127 downgraded** — 20 sybils → 20 phone pulse-fires but only ~4-5 propagate
  (ephemeral eviction self-limits); real cost is local battery, not a mesh storm.
- **O112 hostile strings** — protocol/storage layer PASSES all
  (null/SQL/RTL/zero-width/emoji/500KB); only the display layer failed (fixed #3).
- **Partition/heal verified** — Moto off Wi-Fi → Wi-Fi Direct → reconnect →
  backlog syncs.

## Backlog filed this session
O126–O141 in CLAUDE.md (HLC crash, sybil reply-storm, OnlineStatus clamp,
vacuous test, §19/20 residue, UI perf, **O132 broadcast size cap**, feed
maxLines, **peers-list pollution**, **O135 sybil-resistance design**,
**O136 friending**, synthetic test fleet, kill-reflash-loop, tampered-builds,
**O140 unified subscription primitive**, **O141 comment cleanup**). Plus
`sybil_research.md` (another instance — the SSB/SybilLimit/Advogato port map).

## Next-session pointers
- **Sybil WIRE layer is the next foundational piece:** signed `FriendVouch`/
  `Block` messages + gossip propagation + a projection cache feeding
  `TrustGraph`; the `friendedSendersOnly` Settings toggle; the hops slider. The
  algorithm is done + proven — this is the wire integration. (Glance at
  `ssb-friends` for block-then-unblock/cycle edge cases when wiring it.)
- **Live validation still owed:** KnownSenders filter end-to-end on the node
  (friend a node, toggle known-peers-only, confirm inbox drop + relay unaffected).
- **O93 periodic mDNS re-resolve** — the node/phones lose each other on port
  change (reflash); restart works around it; filed as the fix.
- **Transport priority (O93):** (D) GO+client concurrency baseline → LoRa if HW
  → (C) smart churn (screen-off-priority + rotation); smart + manual switching both owed.
- **O141 comment cleanup** before ship — strip "user suggested"/session dates
  from SOURCE comments (not docs), keep the technical WHY.
- Queue-order feature item was **O100** (content-addressed chunks) before this
  session's live-testing detour; revisit if not doing more sybil wire work.

---

# Prior-session handoffs — archived

Earlier session handoffs are trimmed to one-line index entries once their work
lands — outcomes live durably in `docs/COMPLETED_GAPS.md` (G-rows), `CLAUDE.md`
O-rows, and git history (`git log -p handoff.md`). Newest first:

- **:node + audit intake + 0.6.2–0.6.8 marathon (2026-07-19 → early 2026-07-22)** — MeshRuntime/`:node` shipped, O126 HLC crash → G43, echo-loop fix, DM `_ext` bug (schema v10), O114/O95 closed. Folded into the current entry above.
- overnight research/audit (2026-07-19) — §14–21 findings filed as O126–O130 + folded into O89/O79; no code changes that session.
- 0.6.8 marathon (2026-07-18 eve) — echo loop dead, DM `_ext` bug fixed, O114/O95 closed.
- O93 LAN transport + O98 blind join + §5 guard (2026-07-18) — shipped as G40.
- overnight audit rounds 1–3 (2026-07-18) — filed as O114–O123.
- O80 mode auto-fire / O57 (2026-07-17) — G39.
- O98 Phase 3b (2026-07-17) — G38.
- O62 / O98 Phase 3a / §2 ingest fix (2026-07-17) — G37 + bug history.
- THE MERGE: archimedes + check-online → main (2026-07-16) — G30, catalog in `docs/ARCHIMEDES_MERGE_CATALOG.md`.
- overnight audit (2026-07-16) — findings triaged into O-rows.

**Handoff hygiene:** keep only the current session entry (state, next steps,
directives). When a session's work lands, trim its prose to a one-line index
entry rather than accreting full logs.
