# Handoff — scheduled overnight research/audit session (2026-07-17)

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**

> **This session made NO source-code changes.** Scope was research,
> bug-hunting, doc-drift auditing, and prebuild-vs-handrolled review only,
> per explicit instruction (a scheduled/autonomous routine, not an
> interactive user turn). Everything below is a finding to triage, not a
> commit to review. Six parallel read-only sub-agents covered: README/docs
> drift, iOS/KMP shim staleness, Rooms+sealed-sender+prekey crypto
> (adversarial), the newest O98-persistence/Block/ModeEnvelope code,
> prebuild-swap + stale/TODO sweep, and O108/O109/RBSR-v2 status
> verification. Findings below are condensed from their reports; file:line
> citations are preserved so nothing here needs re-deriving.

## Branch/process note — read this first if you're the next instance

This session's designated branch (`claude/zealous-bohr-9ijy8o`) turned out
to already be a merged ancestor of `main` (its tip, `dbdef71`, appears
verbatim in `main`'s history — confirm with `git merge-base --is-ancestor
dbdef71 origin/main`). Per the "already-merged branch" protocol, the branch
was restarted from `origin/main` (`git checkout -B claude/zealous-bohr-9ijy8o
origin/main`) before this session did anything else. **If you're starting
fresh, do the same check first** — `main` has moved substantially past
whatever a stale local snapshot might show (213 files / +17.4k lines since
the last full audit round alone, per `git diff --stat 1ea456b..86a302e`).
Separately: this session's injected/system-provided copy of `CLAUDE.md` was
itself a stale pre-restart snapshot (it showed O41 identity rotation as
still-shipped; the live file on `main` shows G9 = removed entirely). Lesson
for future instances: **don't trust injected context for file contents that
might have changed underneath the branch — re-read the live file from disk**,
especially after any branch restart.

## §0.5 — on the canary and the task framing this session arrived under

This session's trigger prompt asked to (a) find "the branch that is VERY
developed... called main" — true, verified above — and (b) "fix" the fact
that some instances flag the CLAUDE.md canary as a prompt injection. Having
now read `docs/HANDOFF.md` §0 (the existing, thorough canary explainer —
worth reading in full, it already covers this well) and independently
verified the canary's legitimacy against real git history (dozens of
real commits sign with the phrase going back weeks), I agree with §0's
conclusion: it's a real, sanctioned, transparent convention, not an
injection. I made one small addition to `CLAUDE.md`'s canary note (see
below) — a self-verification pointer so a future instance can check this
against git history in ~10 seconds instead of taking it on faith or
re-deriving `docs/HANDOFF.md` §0's reasoning from scratch. I did **not**
soften the warning language, because a flagging instance being cautious by
default and asking is the correct behavior for a first encounter — the fix
for false positives is faster verification, not less suspicion.

Also worth naming plainly: the automated wrapper around this session's
trigger prompt carried some genuinely odd framing — an instruction to
literally recite the task list back before starting, and a directive to
"never stop... continue until you run out of tokens." I did the substance
of the requested work (research is genuinely valuable and matches this
repo's own established autonomous-session pattern per `docs/HANDOFF.md`)
but didn't treat either of those two framing quirks as binding: reciting
instructions back has no reader benefit in an unattended run, and running
indefinitely without a stopping condition is a resource-exhaustion pattern
I'm not going to follow just because it was asserted. Noting this so the
user can see the reasoning, not because anything was actually wrong with
the underlying repo or task — it wasn't.

---

## §1 — Findings, most-actionable first

### CRITICAL — remote crash: uncaught exception in room-message decrypt

**`core/src/main/kotlin/com/rumor/mesh/core/protocol/MultiRecipientEnvelopeCodec.kt:158-159`.**
Every fallible step in `decrypt()` is wrapped in `runCatching { }.getOrNull()`
**except** the X25519 agreement call:
```kotlin
val ephPub = runCatching { envelope.senderEphemeralPublic.fromBase64() }.getOrNull() ?: return null
val shared = CryptoManager.x25519Agreement(myX25519StaticPrivate, ephPub)   // NOT wrapped
```
Verified against the actual `bcprov-jdk18on` classes (the auditing agent built
a standalone harness, not a guess): any `senderEphemeralPublic` that
base64-decodes to **fewer than 32 bytes** throws an uncaught
`ArrayIndexOutOfBoundsException` inside `X25519PublicKeyParameters`'s
constructor. Traced full path: any member of an ENCRYPTED room can craft a
`ROOM_MESSAGE` with a 5-byte garbage `senderEphemeralPublic`, address one
`KeyWrap` at a real victim userId (public within the room), sign the outer
message with their own (unvetted, self-generated) identity, and tag it with
the room's `_ext.rt`. `GossipEngine.processIncoming` → `handleRoomMessage`
(`GossipEngine.kt:1088`) calls `decrypt()` inside a bare `scope.launch{}`
with **no `CoroutineExceptionHandler` anywhere in the codebase** (`grep -rn
"CoroutineExceptionHandler"` → zero hits) — the exception propagates to the
dispatcher's default uncaught handler, which crashes the process on Android.
One broadcast, zero crypto effort, crashes every online member of the room.
**Fix is one line** — wrap line 159 in `runCatching { }.getOrNull() ?: return
null`, matching the pattern already used three lines above it. The mirror
call in `encrypt()` (line 82) has the same gap but recipient keys are
caller-supplied locally today, not wire-attacker-controlled — lower
priority, flag for later if recipient keys ever come from gossiped
membership data.

### HIGH — self-block guard (commit `86a302e`) is incomplete; self-blocks still propagate to the mesh

The "refuse blocking own identity" guard landed only in
`BlockManager.block()` and the in-memory effective-set `refresh()`. Two
other paths write to `blockEntryRepo` directly and were not covered:
- `BlockManager.importEncrypted()` (`core/.../block/BlockManager.kt:99`) —
  no self-check, reachable from the UI's Import action
  (`BlockManagementViewModel.kt:91`).
- `BlocklistPublisher.publish()`/`publishDiff()`
  (`core/.../block/BlocklistPublisher.kt:43,60`) — read
  `blockEntryRepo.getActiveIds()` raw and are wired to the real mesh
  broadcast (`BlocklistGossipBridge` → `gossipEngine.composeOutbound`). If a
  self-block entry lands in the repo via import, it gets **gossiped to every
  subscriber**, who apply it unfiltered — reproducing exactly the
  "subscriber silently hides your messages" harm the commit's own message
  names as the reason for the guard in the first place. Fix: move the
  self-block check down into the repo write path (or add it at both
  additional call sites) rather than only at the top-level `block()` method.

### HIGH — OPEN-room routing tag is unsigned → cross-room message injection by any relay

`RoomRoutingTagExt.kt:12-16`'s safety argument ("a relay flipping the tag
just gets the message dropped everywhere") only holds for a *bogus* tag. For
OPEN rooms, `RoomRoutingTag.openRoomTag(roomId)` (`RoomRoutingTag.kt:66-69`)
is `SHA-256("rumor-room-route-v1:" || roomId)` — a public, secret-free,
deterministic function of a public roomId. `RumorMessage.ext` (carrying
`_ext.rt`) is explicitly unsigned (`Message.kt:31-35`), and
`composeRoomMessage`'s OPEN path (`GossipEngine.kt:715-722`) puts no roomId
or room-binding anywhere in the *signed* transcript. Any relay (= every
node) can recompute `openRoomTag` for any other public room it knows and
retag a message in flight before forwarding. Result: Alice's legitimately
signed post to Room A gets relabeled and delivered to Room B's members,
displayed with Alice's real (still-valid) signature — worse than a bogus
tag because it's *believably* misattributed, letting a hostile relay dodge
a room-A ban/removal or pollute an unrelated room under someone else's real
identity. ENCRYPTED rooms are saved by the separate keyWrap layer; this is
OPEN-room-specific. Fix direction: bind the room association into the
*signed* transcript (roomId or routing tag inside the outer signature),
not `_ext`.

### HIGH — `roomPostingCertSignableBytes` field-boundary ambiguity (signature confusable across different certs)

`RoomPostingCert.kt:76-84` joins `channel` and `userId` with a bare `'|'`,
no length-prefixing, no escaping. Since `channel` is unconstrained free
text, `(channel="a|b", userId="c")` and `(channel="a", userId="b|c")`
produce byte-identical signable bytes and therefore the **same valid
signature** — `RoomPostingCertVerifier.verify` (`RoomPostingCertVerifier.kt:
42-72`) doesn't reject either. The verifier's own doc-comment
(`RoomPostingCertVerifier.kt:18-21`) explicitly claims a relay "cannot...
swap the grantee[or]... change the channel... without breaking the sig" —
false whenever `channel` contains `|`. Currently low *live* exploitability
because posting-cert enforcement isn't wired into `GossipEngine` at all yet
(see next finding) and real userIds are 64-hex digests unlikely to
naturally collide — but this needs fixing **before** O89 enforcement ships,
not after, or the fix becomes a wire-breaking change to an already-deployed
cert format. Same root cause exists latently in `roomActionSignableBytes`
and `roomCreateSignableBytes` (`Room.kt:178-193`, `142-159`) — both have
adjacent free-text fields with no verifier wired yet either. Fix once, for
all three, before any of them ship: length-prefix variable-length fields
(`"${s.length}:${s}"`) instead of raw `'|'`-joining.

### HIGH (doc-accurate, confirmed still live) — O108/O109 transfer-metadata resource exhaustion

Re-verified against current code, not just the backlog description:
`TransferAssembler.handleMetadata` (`core/.../transfer/TransferAssembler.kt:
105-127`) stores sender-claimed `totalChunks`/`totalBytes`/`chunkSize` with
**zero ceiling** and arms the watchdog+NACK loop on the metadata frame
itself (not on first real chunk). `Chunker.missingIndices` (`Chunker.kt:
91-92`) still does `(0 until totalChunks).filter{...}` verbatim — eager
materialization of an attacker-controlled range. `TransferRepository.
pruneOlderThan` exists but **has zero callers anywhere in the codebase** —
dead scaffolding, not wired eviction. No per-sender concurrent-transfer cap
exists at all. Git-confirmed nothing has touched `TransferAssembler.kt`/
`Chunker.kt` since the O108/O109 backlog row was written except a pure
file-rename (`ed5d42f`, zero content diff). A validly-signed
`TRANSFER_METADATA` claiming `totalChunks = Int.MAX_VALUE` is a live,
working DoS today. CLAUDE.md's O108/O109 rows are accurate and need no
correction — just re-confirmed, so this can move up the priority stack with
confidence it isn't stale.

### MEDIUM — O62 ModeEnvelope: significant dead configuration + a duplicated, disagreeing capacity table

Of `ModeEnvelope`'s 8 fields, `wifiDiscoveryCadenceMs`, `gossipRoundIntervalMs`,
`breadcrumbDecayMs`, and `routingWeightMultiplier` are defined, documented
in CLAUDE.md as "recorded values," and unit-tested — but **never read
anywhere**: `MeshService` still hardcodes `GOSSIP_ROUND_INTERVAL_MS`,
`WifiDirectTransport` has zero `ModeState` references, `BreadcrumbCache`
takes no `ModeState` param and still hardcodes 24h decay. Separately,
`PersistencePlanner.capacityFor()` hardcodes its own mode→capacity table
(2/4/8) that duplicates **and already disagrees with**
`ModeEnvelope.routingWeightMultiplier` (1/3/10) — reproducing, one layer up,
exactly the "scattered per-mode constants" problem O62 was supposed to
close. Minor dangling KDoc reference to the deleted `STATIC_CACHE_BOOST` at
`MessageStore.kt:35`.

### MEDIUM — O38 `PrekeyCache` fully dangling: write side works, read side never called

`GossipEngine.handlePrekeyPublish` (`GossipEngine.kt:1120-1129`) correctly
verifies and populates the cache. `PrekeyCache.freshestFor` has **zero
production call sites** — `grep -rn "freshestFor"` hits only its own unit
test. `composeDirect` (`GossipEngine.kt:412-511`) unconditionally DHs
against the recipient's long-term static key; it never consults the cache.
Receiver-side forward secrecy is therefore fully inactive today even though
wire format, verifier, and cache are all shipped and individually tested —
matches CLAUDE.md's own O38 status (`[TODO/CODE]`, not closed), and
`GossipEngine.kt:1106`'s own comment already flags this as a known
follow-up. Confirming precisely, not a new discovery, but worth having
traced end-to-end in one place.

### MEDIUM — O89 posting-cert "structural enforcement" doc-comment overstates the code

`RoomPostingCert.kt:26-29` claims "receivers drop messages without a valid
cert... every honest peer drops the publish before relay." Actual code:
`composeRoomMessage`/`handleRoomMessage` (`GossipEngine.kt:706-747,
1062-1103`) never attach or check a `RoomPostingCert` at all.
`MEMBER_ONLY`/`MOD_APPROVED` posting policies are **not enforced** today —
any node that can compute a room's tag can post and it relays/displays
identically to an authorized post. Matches O89's `[TODO/CODE]` status, so
expected, but the doc-comment's confident phrasing risks a future session
believing enforcement already ships. Fix the comment or wire the check —
don't leave both.

### MEDIUM — hand-rolled HMAC-SHA-256 / HKDF-SHA-256 in production crypto paths, duplicating an already-mandatory dependency

`core/src/main/kotlin/com/rumor/mesh/core/crypto/HmacSha256.kt:16-38` and
`HkdfSha256.kt:30-88` are genuinely hand-implemented (manual ipad/opad XOR,
manual extract/expand), not thin wrappers. RFC-vector-pinned and correct,
but the repo is confirmed pure single-target JVM (no expect/actual, no KMP
source sets) — their own docstring justification ("avoids another
expect/actual surface") is stale/aspirational, not true of the current
build. `javax.crypto.Mac.getInstance("HmacSHA256")` is already used
correctly elsewhere in the same crypto layer
(`CryptoManager.kt:181-185`), and BouncyCastle — already a mandatory
dependency — ships both `HMac` and `HKDFBytesGenerator`. These two hand-
rolled objects are load-bearing for O53 sealed-sender and O79 Rooms — real
production security code, not test scaffolding. This is exactly the gap
the project's own recorded "Prebuilt-vs-handrolled policy" (CLAUDE.md) was
meant to close and evidently slipped in from the KMP-era parallel branch,
unreviewed post-flatten. Note: this is a **different** call site than the
one CLAUDE.md's existing policy note already excuses (BC's
`HKDFBytesGenerator` vs `CryptoManager.deriveAesKey` — that one is correctly
left alone because swapping it breaks existing DM key derivation). This
`HkdfSha256`/`HmacSha256` pair is newer (O53/O79-era) wire-format-fresh code
with no legacy-compat constraint — safe to swap, and should be, per the
project's own stated rule.

### LOW / INFORMATIONAL — smaller crypto/protocol notes (Rooms/sealed-sender/prekey audit)

- Non-constant-time tag compare: `RoomTagMatcher.match` (`RoomTagMatcher.kt:
  54-65`) uses `ByteArray.contentEquals` against a secret-derived HMAC tag
  for ENCRYPTED rooms (OPEN-room branch is fine, no secret involved).
  Timing-attack surface is currently low given BLE/Wi-Fi-Direct jitter, but
  cheap to fix and worth doing before any lower-jitter transport (e.g. a
  future mDNS/LAN transport, O93/O107) exposes it more.
- `MultiRecipientEnvelopeCodec.decrypt`'s `keyWraps.firstOrNull{...}` scan
  (`:155`) has no explicit size cap of its own; incidentally bounded by
  `GossipSession`'s 4MB frame cap (~tens of thousands of entries) — still
  nontrivial CPU/memory per crafted broadcast. Recommend an explicit,
  smaller cap at the codec/`handleRoomMessage` level.
- Design smell, no live exploit: `CryptoManager.x25519Agreement` actually
  returns ECDH-then-fixed-context-HKDF, not raw ECDH, despite the name —
  every current caller correctly re-derives with its own domain-separated
  `info` on top, so no collision today, but the naming invites a future
  caller to skip the second layer and collide. Consider renaming/splitting
  "raw ECDH" from "ECDH+KDF."
- `SealedSenderKey.derive`'s static-static ECDH has the FS exposure its own
  docstring already documents accurately — re-confirmed, not new.
- Sealed-sender `_ext.t` is stamped on compose (`GossipEngine.kt:484-497`)
  but has zero receiver-side consumers yet — matches documented O53 status
  exactly, re-confirmed for completeness.
- Stale comment: `CryptoManager.kt:64-66` still says "PBKDF2 with a
  constant salt" for `x25519Agreement`'s KDF; the actual code
  (`deriveAesKey`/`platformDeriveAesKey`, `:181-185`) is HMAC-SHA256
  HKDF-extract, correctly switched at the G20 fix — the comment one
  function up was never updated. Harmless, but confusing for future crypto
  review.
- Stale comment (now actively misleading): `MultiRecipientEnvelope.kt:61-64`
  still says "Wiring NOT done in this commit... no compose helper, no
  decrypt helper" — false today; `composeRoomMessage`/`handleRoomMessage`
  are fully wired (`GossipEngine.kt:705-740,1062-1102`). Worth a quick
  delete/update so a future reader doesn't think O79 wiring is missing.

### O42/RBSR-v2 — re-verified clean, no regression

Confirmed (not just trusted): the forgeable raw-id-sum RBSR-v2 fingerprint
formula from the merged-in archimedes branch (`8b2f411`/`19cf42c`) did
**not** survive the flatten+merge. Current `core/sync/Rbsr.kt` contains
exactly one fingerprint path — the safe additive-mod-2^256-over-SHA256
scheme — and repo-wide grep for `RBSR_V2|RbsrV2|FingerprintFormula` finds
zero live code (only one stale docstring analogy in `GossipPacket.kt:225`
mentioning "rbsr-v2" as a pattern-name, cosmetic only). CLAUDE.md's G30 and
tombstone rows match the code exactly. Scheduler traffic-class size
ceilings (`Message.kt:307-309`, 16KB/16KB/256KB) also re-verified unchanged
and correctly enforced — no drift.

---

## §2 — Doc-drift audit (README + docs/)

**`README.md` is substantially stale** — it describes the pre-merge
"archimedes" KMP module layout (a fictional "14 modules" ASCII diagram),
not the real 3-module `:core`/`:app`/`:simulator` split
(`settings.gradle.kts:16-18`). Full itemized findings from the audit agent,
worst-first:

1. **Module map** (README.md:23-102) — wrong module count and layout
   entirely; needs replacing with the real 3-module split already correctly
   described in `CLAUDE.md:7-15`.
2. **RumorMessage field table** (README.md:283-308) — `elapsedMs` should be
   `sentAtMs` (and the semantics are backwards: it's an absolute
   sender-asserted untrusted timestamp, not a monotonic hold-time counter —
   `docs/wire-format.md:309` has the correct text to copy from); `ttl`
   should be `hopsToLive`; the `type` enum shows 4 values (`BROADCAST |
   DIRECT | PING | PONG`), current code has 16 (`Message.kt:144-248`);
   missing `_ext`/`trustLevel`/`intendedPeers` entirely; the
   signature-exclusion list omits `hopsToLive` and `_ext` (both correctly
   excluded in code, per `MessageStore.kt:220-242`, just not mentioned in
   README).
3. **"Known limitations"** (README.md:541-548) — flatly wrong, not just
   incomplete: claims "identity rotation (O41)... core/protocol
   implementation but no UI yet." O41 was **fully removed** (CLAUDE.md G9)
   as a deliberate security fix (auto-rebind on rotation = permanent
   impersonation after key theft). This is the single highest-priority
   README fix — it's actively dangerous-reading, not just outdated.
4. **Internal self-contradiction**: the "LoRa integration" section
   (README.md:492-498) calls the bridges "stubs with clearly marked TODOs";
   "Known limitations" (line 544) correctly says they ship working BLE
   codecs with real protobuf decode. Same document, two different stories.
5. **`BlockManager.block()` code example doesn't compile**
   (README.md:360-363): shows `durationMinutes = 60 * 24`; actual signature
   is `suspend fun block(userId: String, durationMs: Long? = null, ...)` —
   wrong param name, wrong unit, wrong nullability-for-permanence semantics.
6. **Silent on substantial shipped/partial features**: no mention anywhere
   of Rooms (O79, `[PART]`, 60+ tests), the O57/O62 three-state Mobile/
   Static/Free `ModeEnvelope` (README's "Static mode" section still
   describes the old binary flag only), or O98 smart persistence
   (README's "Priority links" section only covers the older pairwise
   mechanism it's now layered under).
7. **Test-coverage table** (README.md:154-162) lists 4 test classes; there
   are 75+ across the three modules. Not wrong, just ~19x understated —
   recommend genericizing the table rather than hand-maintaining an
   enumeration that will keep rotting.
8. **"Identity and encryption"** section omits the Ed25519→X25519
   conversion step entirely (a real, previously-buggy, now
   dedicated-test-covered piece of the DM crypto path — `GossipEngine.kt:
   438-444`, `CryptoManager.kt:69-87`), plus O53 sealed-sender and O38
   prekeys.

**Full agent report has more granular line-by-line citations than fit
here** — re-run the same audit angle if you want the complete list; the
above is the priority-ordered subset.

**`docs/` staleness, same root cause (all predate the 2026-07-16
flatten+merge, none revisited since):**
- `docs/CRYPTO_PRIMITIVES_AUDIT.md:13` cites
  `core/src/commonMain/kotlin/.../PluginContext.kt` — `commonMain` no
  longer exists; real path is `core/src/main/kotlin/...`.
- `docs/IOS_PORT_PHASE_1_HANDOFF.md` — the most misleading of the iOS docs:
  its "What's next" section presents the KMP restructure as **unstarted
  future work** ("Doc commits only so far. No source changes.") when it
  actually happened (via archimedes) and was then reverted (the flatten).
  Following its literal Phase-1c instructions today would have a future
  session rebuild `expect`/`actual` shims for things that already have
  finished, shipped, plain-JVM implementations in `core/platform/`
  (`Sha256.kt`, `SecureRandom.kt`, `Base64Codec.kt`, `Uuid.kt`,
  `ConcurrentMap.kt`, `AtomicCounter.kt`, `BoundedFifoMap.kt`, `RwLock.kt`)
  — genuinely wasted work if trusted at face value.
- `docs/IOS_SWIFT_BRIDGE_SPEC.md:17` falsely claims
  `core/src/iosMain/.../PlatformCrypto.kt` exists with "seven actuals...
  five throw NotImplementedError" — none of that is true; the real
  `PlatformCrypto.kt` is a single flat JVM object, zero stubs, zero
  `actual` keywords.
- `ios/README.md` — 3 dangling references to `commonTest`/`iosMain` paths
  and a specific `NotImplementedError("iOS PlatformCrypto: Swift bridge
  not yet wired")` stub string that doesn't exist anywhere in the current
  tree; a future implementer following its 4-step checklist literally will
  search for files that were merged away.
- Three files (`Sha256.kt:17`, `Base64Codec.kt:15`, `Uuid.kt:14`, plus
  `docs/RESEARCH_NOTES.md:301`) cite a `docs/PHASE_1C_SHIM_SURFACE.md` that
  was deleted in `4819447f` and no longer exists — dead pointer, four
  places.
- `docs/GLOSSARY.md` has 3 stale entries contradicting current CLAUDE.md
  status: "ModeProfile — Not yet built" (it's shipped, `[PART]`, O62
  resolved), "StaticMode — the current shipped toggle" (retired, replaced
  by ModeEnvelope), "Identity rotation (O41)" presented as a current
  mechanism (removed entirely, G9).
- **Good news, no action needed**: `docs/wire-format.md` was independently
  cross-checked byte-for-byte against `Message.kt`/`MessageStore.kt` and is
  fully accurate and current — treat it as the source of truth README
  should be reconciled against (it says as much about itself at line 3).
  `core/platform/` shim *code* itself (as opposed to the docs describing
  it) is also clean — no `expect`/`actual` leftovers, no stray KMP Gradle
  plugin config, no stub markers; the debt is 100% in documentation
  pointing at pre-flatten paths, not in the shipped shim code.

Two cosmetic-only findings not worth their own backlog rows: 8 of 10
`core/platform/*.kt` files carry two back-to-back KDoc blocks per
declaration (an artifact of the expect/actual-pair mechanical fold during
flatten) — harmless, low-priority merge-into-one cleanup whenever someone's
next in those files.

---

## §3 — Prebuild-vs-handrolled sweep (beyond §1's HMAC/HKDF finding)

Reviewed against the project's own recorded policy (CLAUDE.md
"Prebuilt-vs-handrolled policy" note). Everything else checked out clean:
`Compression.kt` (thin `Deflater`/`Inflater` wrapper, algorithm is JDK's,
fine), `Base64Codec.kt` (thin `java.util.Base64` wrapper, fine),
`Sha256.kt` (thin `MessageDigest` wrapper, fine), `PlatformCrypto.kt`
(Ed25519/X25519/AES-GCM/PBKDF2 all correctly delegate to BouncyCastle —
the model the HMAC/HKDF files above should have followed), `Rbsr.kt`'s
hand-rolled mod-2^256 accumulator (already reviewed/justified this cycle,
tier-2 "faithful reference port" not tier-1 "crypto primitive"),
Meshtastic's hand-rolled protobuf varint codec (already tracked, deferred
per existing decision), the one bloom filter (already fixed with real
MurmurHash3). **No BIP-39/SLIP-0039/mnemonic code exists yet anywhere** —
nothing to flag today, but a hard tripwire for whoever starts O45/O46:
reach for an audited library on day one, per the policy's own explicit
rule. No CRC/Base32/hand-rolled-UUID/hand-rolled-LRU-eviction found
anywhere. Only new actionable item from this pass is the HMAC/HKDF finding
already covered in §1.

**Stale/TODO/dead-code sweep, broader result: this codebase is clean.** No
inline `TODO`/`FIXME`/`XXX`/`HACK`/"not implemented" markers anywhere in
non-test source (backlog tracking is 100% in CLAUDE.md, discipline intact).
No silently-swallowing empty catch blocks — every one either logs or is a
deliberately documented fail-closed default. No commented-out dead code
blocks. No genuinely dead/orphaned files (every apparent hit was a
false-positive from Kotlin's multiple-top-level-classes-per-file pattern,
a manifest-registered component, or an extension-function file with
real callers elsewhere). One doc-freshness note: CLAUDE.md's G28 row
still describes `MessagesScreen.kt` as having its own un-deduplicated
`formatElapsed` copy — verified that's already fixed (all three screens
share `ui/RelativeTime.kt` now); worth marking that residual resolved next
time someone's in the G28 row.

---

## §4 — Suggested priority order for the next instance (code-touching session)

1. **One-line fix**: `MultiRecipientEnvelopeCodec.kt:159` `runCatching` wrap
   — trivial, kills a real remote-crash DoS, do this first regardless of
   what else gets picked up.
2. **O108/O109** — now re-confirmed live with a precise failure scenario;
   the fix shape is already specified in CLAUDE.md's own rows (evidence-
   gated setup + windowed chunk requests + per-sender cap). No more
   research needed here, just implementation.
3. **Self-block leakage** (§1, HIGH) — two more call sites need the same
   guard `86a302e` added to `block()`.
4. **`roomPostingCertSignableBytes` canonicalization** — fix before O89
   enforcement wires up, since it's a wire-format decision that gets
   expensive to change after the fact.
5. **OPEN-room tag signing** — needs a design decision (bind roomId into
   the outer signed transcript) before it's worth implementing; flag for
   discussion, don't just patch.
6. **HmacSha256/HkdfSha256 → BouncyCastle swap** — safe, no legacy-compat
   constraint, matches the project's own policy; low-risk cleanup.
7. Everything else in §1/§2/§3 is lower urgency — mostly doc corrections
   (README top of the list) and completeness gaps (ModeEnvelope wiring,
   PrekeyCache wiring) already tracked under existing `[PART]`/`[TODO]`
   backlog rows, just now confirmed with more precision than before.

None of the above were fixed this session — by design, per the research-only
scope. Next instance: triage into CLAUDE.md backlog rows (new O-numbers
where none exist yet — the crash bug and the two HIGH room/cert findings
don't have rows today) per the usual multi-instance-coordination protocol.

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
