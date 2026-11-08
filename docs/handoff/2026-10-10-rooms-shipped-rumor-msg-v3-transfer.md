# Handoff — Rooms shipped (O89 write authority) + rumor-msg-v3 + transfer/O159 coverage + app silent-failure fixes (2026-10-10)

**Interactive session on `main` (per the 2026-07-19 directive: commit + push
straight to `main`, no feature branch).** Everything below is committed and
pushed. Verified work is labelled as such. (The `:app` work has since been
compiled, unit-tested and run on all three phones — G56, 2026-10-11.)

## The wire broke — `rumor-msg-v2:` → `rumor-msg-v3:` (fleet reflashed 2026-10-11)

Commit `831c2c6`. Every v2 signature is invalidated; mixed-version phones cannot
talk. Pre-release hard cutover, same justification O144 used for v2 (no shipped
users). Cross-device v3 verification confirmed on hardware 2026-10-11 (G56). v3 adds:
- the rest of `MessagePayload` — `contentType` / `filename` / `mimeType` /
  `sizeBytes` (**O212**, closed as G54);
- the room posting cert `_ext.pc` for `ROOM_MESSAGE` (**O89**, closed as G53).
`docs/RENAMED_FIELDS_NEVER_REUSE.md`, `docs/wire-format.md` (formula + tag
tables) and `docs/SECURITY_RESEARCH.md` are updated; v2 is retired forever.

## USER DECISION: Rooms SHIP in v1 (O210)

User's call, and the reasoning is sound enough to record: *"ship rooms, they'd
be necessary to not have everything be publicspam."* That is also O36's own
logic — moving to a governed room IS the spam remedy — so shipping only the
ungoverned feed would ship the failure mode. An earlier session (mine) had
recommended CUTTING on wire-format risk; that weighed the wire risk correctly
and the product risk not at all. **Superseded; do not re-litigate.**
Consequence: **O89 and O187 are hard release blockers.** O89 is now done.
**O187 is now half done - see its own section below.**

## What shipped, newest first

- **`831c2c6` — O89 Rooms write authority + O212 payload metadata (G53, G54).**
  `RoomPostingCertVerifier` had **zero production call sites**: compose attached
  no cert, receive checked no authority. An OPEN room's routing tag is a
  deterministic keyless function of its public roomId, so **anyone who could
  compute the tag could post into a "governed" room** — governance was a UI
  label with nothing behind it. Now `composeRoomMessage(postingCert =)` stamps
  the cert pre-sign (same `buildMessage` slot as O157's tag) and
  `GossipEngine.isAuthorizedToPost` gates receive with 7 checks: room requires a
  cert / cert present and well-formed / scoped to THIS room / names THIS sender
  (else a cert seen on the wire is replayable) / moderator sig + moderatorId↔
  pubkey binding / **issuer is a moderator RIGHT NOW** (demotion bites without
  waiting for expiry) / wall clock inside the window. **Fails CLOSED** for a
  governed room, OPEN only where there is nothing to enforce. New
  `GossipEngine.RoomAuthorityProvider` (`requiresPostingCert` / `isModerator`),
  deliberately separate from `RoomSubscriptionProvider` — null = no enforcement
  = the ungoverned feed. **The cert had to be SIGNED, not merely carried:** its
  moderator signature protects its contents, but `_ext` is otherwise unsigned,
  so a relay could STRIP it and every honest downstream node would then reject a
  legitimate post — one relay amplified into mesh-wide censorship, same shape as
  the §2 id-burn.
- **`fe3777d` / `af7a1e5` / `3b30a0d` — O159 transfer coverage (G52).**
  `TransferAssemblerTest` (9) + `TransferSenderTest` (6): first direct tests for
  the two stateful classes holding ALL of O108/O109's hardening. Testability via
  injected `scope` (defaults to the old self-created IO scope — no call site
  changed; mirrors `PrekeyRotator`'s injected `Clock`) + `internal` entry points,
  so ingest is driven directly with no flow timing and no waiting out the 60 s
  NACK delay (the blocker the 2026-08-03 handoff flagged). Behaviour change:
  `TransferSender`'s receiver-cancelled guard moved from the collector INTO
  `handleChunkRequest` so every future path inherits it. The injected `Scheduler`
  is the observable for "did anything go on the wire".
- **`1259202` — O186 fix + O212 filed.** Corrected my own previous commit, which
  claimed contentType was signed. It was not. With the media drop sitting AFTER
  `recordAndCheck` (where O132's size drop correctly sits), a relay could retype
  a TEXT feed post for free, the honest node would burn the id, and the genuine
  post would be refused as a duplicate downstream — §2 rebuilt. Gate moved BEFORE
  the commit; **that ordering is deliberate and must not be "tidied" back**, and
  it stays even now that v3 signs contentType (an honest-but-wrong client's
  non-TEXT post still should not burn a shared id; the cost is one re-offer).
- **`27aed8a` — O186 text-only global feed.** O36's one hard protocol rule had
  no implementation (text-only only because compose hardcodes TEXT). Enforced in
  `MessageStore.ingest`, keyed on **"plaintext `payload` ⇒ feed-class"** —
  governed rooms are ENCRYPTED (O155) so they arrive as `encryptedPayload` and
  pass untouched with their creator's `allowMedia` policy. No new wire constant,
  no invented global-feed id.
- **`fe1889e` — seven `:app` rows.** O164 (sends vanished while the UI showed
  success; the unbound window reopens on EVERY background→foreground cycle),
  O184 (relay double-tap), O169 (bridge collector crash killed the process —
  SupervisorJob does not absorb the throw), O168 (bridge shown ON after a
  rolled-back attach), O165 (FLAG_SECURE on 4 secret-bearing screens, refcounted
  because the flag is window-global while the need is per-screen), O178 + O183
  (dead controls REMOVED not fake-wired), O179 (battery-optimisation warning now
  actually detected — ties the G24 Moto/Doze field finding).
- **`9ec4133` — O208–O211 filed** (below) + Coding-conventions Room version
  corrected 11 → 12.

## Verification honesty — what is and is NOT verified

- **`:core` 484 tests, 0 skipped, 0 failures, 0 errors, no zero-test classes.**
- **Every protection teeth-checked individually** (disable it, confirm exactly
  the claiming test fails, restore): O186 gate, O108 plausibility gate,
  per-sender cap, O158 chunk release, O18 cancel path, O89 enforcement (removing
  one line fails exactly the 6 refusal tests and leaves the 2 delivery tests
  passing).
- **`:app` compiled and unit-tested 2026-10-11 (G56)** — only `AppModuleTest`
  needed a fixture fix. What is still NOT verified on a device is the
  *behaviour* of the seven rows (O164 send-failure snackbar incl. its
  `Column`-not-`Scaffold` placement, O184, O169, O168, O179, O178/O183);
  O165 FLAG_SECURE is verified. See the 2026-10-11 section's pending list.
- `:simulator` had 2 failures, both the real-socket LAN tests
  (`LanLivenessValidationTest`, `LanMeshHarnessTest`); **both pass in
  isolation** → load flake per the known-flaky list, not a regression.

## Guard weaknesses found — FIVE green-but-vacuous traps (see also the O187 section)

The user pushed on "make sure 0 failures is 0 failures because it's successful,
not because the test is misconfigured." It paid for itself three times:
1. **`DomainTagInvariantTest` stayed green across the whole v2→v3 bump** because
   `assertContainsTag` greps the FILE and a stale KDoc still said v2 while the
   code emitted v3. **Fixed:** added a behavioural companion asserting the bytes
   the signer actually emits. *A comment is not evidence.*
2. **`SignableFramingGuardTest` never caught O212** because the bug was an
   OMITTED field, not a bad delimiter. **NOT fixed** — teaching it to catch
   omissions is open (recorded in G54).
3. **`SourceInvariantTest`'s O5a scan matches COMMENTS, not just code** — an
   explanatory comment of mine naming `selfAuthenticating` failed the build. The
   guard was right; know that it scans prose too.
Also: one of my own teeth checks was wrong (removed `deleteAllForTransfer` in
`cancel()` instead of `attemptReassembly`), making a good test look weak. **A
teeth check can lie too — confirm you disabled the line you meant to.**

## New rows filed (bodies in docs/OPEN_BACKLOG.md)

- **O208** — **CI stopped reporting on `main`.** Runs 202–205 (tip included) are
  `cancelled` at the job level, not test-red: green runs finish in ~8–9 min,
  these died at 10–15, and 202 is a docs-only commit. So the O198 liveness
  default-flip shipped with no verdict. Fix order: `timeout-minutes` so a kill
  reports honestly; **split the real-wire LAN tier out of the push job** (those
  tests are 12–52 s EACH and now dominate); re-run the tip. Bundle with O163.
- **O209** — no single v1 release-gate checklist; deliverable
  `docs/RELEASE_GATES.md`, pointers only.
- **O210** — Rooms SHIP (above).
- **O211** — toolchain: **Kotlin 2.x BEFORE JDK 21**, and only
  `:core`/`:simulator`/`:node` move (`:node` leads — decades-horizon per
  O55/O104, no Android or F-Droid exposure). Gated behind O208. Corrects an
  earlier claim that this would invalidate the F-Droid recipe — that touches
  `:app` only.
- **O212** — closed same session as G54.

## O187 - half shipped, same session (commit `8206494`)

The other hard O210 blocker. **Shipped + verified** (`:core` 488/0,
`:simulator` 136/0): three new MessageTypes (`room_create`/`room_invite`/
`room_action` - additive enum values, so the v3 transcript is UNCHANGED since
`type.name` was already framed; serial names reserved), `RoomRepository` +
`RoomRecord` + `InMemoryRoomRepository`, `RoomAuthorityResolver` (**the first
real implementation of O89's provider - before it, governed rooms could not
exist**), `composeRoomCreate/Invite/Action`, and receive-side
`handleRoomLifecycle`.

**Invariant not to break:** the carrying message's sender must BE the author of
the inner decision. Relay preserves `senderId`/`senderPublicKey` and ingest
proved `senderId == SHA-256(pubkey)`, so that one rule gives every hop the right
key for the inner signature with no contact lookup, and blocks re-wrapping
someone else's room decision.

Three decisions pinned by tests: **CREATE is first-writer-wins** (else anyone
re-announcing a roomId resets its policies and installs itself as creator-mod);
**a ban outranks a later invite** (else a mod silently undoes a ban by
re-inviting); **the creator's mod status is not removable** (else an emptied mod
list leaves the room ungovernable).

**O187 stays `[PART]`. Remaining:** (a) Android Room DAO/entity +
`RumorDatabase` bump + exported schema JSON + `AppModule` wiring -
**deliberately not done blind**, no Android SDK here and an unverifiable schema
bump is what O116 exists to prevent; (b) Rooms UI; (c) membership-policy
enforcement at JOIN (O89 covers posting only); (d) O185's blocking reads -
`RoomAuthorityResolver` adds the same `runBlocking` shape and its KDoc says so,
fix both with one cached snapshot; (e) **O213** (new) - a lifecycle op arriving
in an earlier exchange than its CREATE is dropped AND dedup-marked, so it never
returns. Batch order is fixed; cross-batch is O149's causality problem. **Do not
"fix" O213 by re-offering dropped ids** - that reopens the section-2 dedup
interaction.

### Security review caught two real bugs in O187 (commit `87633ed`)

An automated review of `8206494` found three things. Two were genuine and are
fixed; one is folded into O213.

1. **[HIGH] roomId was asserted, not derived.** `RoomCreate` carried any
   roomId, collisions resolved first-writer-wins — and a roomId is PUBLIC (the
   routing tag is a keyless hash of it), so whoever announced one first owned
   the room *on that node*. Per-node winners differ, so the mesh disagreed about
   who governs what. Now `roomId = RoomId.derive(creatorId, nonce)`, nonce bound
   into `rumor-room-create-v3:`, receivers recompute and reject. **The tell:
   this was against the codebase's own grain** — userId = SHA-256(pubkey),
   trafficClass derived from type, routing tag derived from roomId. When a new
   identifier is *asserted* in this project, that is the smell.
2. **[MEDIUM] banned and non-member users could post.** `isAuthorizedToPost`
   checked certs and nothing else. Worse, it returned early for every
   non-MOD_APPROVED room **with a KDoc of mine claiming membership enforced it
   — and nothing did**, so MEMBER_ONLY rooms had zero write enforcement. That is
   the O89 gap reproduced one layer up, in a comment describing a check that was
   never written. Ban is now checked FIRST (a banned user may hold a cert issued
   before the ban); membership is enforced for every room with a roster.
3. **[MEDIUM, open → O213] roster replay/ordering.** `RoomInvite` has no issue
   time and nothing tracks per-target last-applied state, so an unexpired invite
   issued before a KICK re-adds the kicked user, and nodes applying in different
   orders diverge. Ban case closed, kick case not. Remedy recorded in O213
   (`issuedAtMs` + per-target last-applied + idempotent applied-ids) to land with
   O149. **Not improvised** — a partial order over signed roster ops is the
   state-resolution trap the Matrix guardrail warns about.

**Process note:** don't skip these reviews. Both real findings were in code that
had already passed a full green suite and my own teeth checks, because my tests
only probed the gates I had thought of.

### Two more vacuity traps (five this session in total)

4. **TrafficClassInvariantTest** demands explicit coverage for every new
   MessageType - it failed until the three room types were covered. Working as
   designed; add the test, do not widen the allowlist.
5. **One of my own tests was VACUOUS and a teeth check caught it.** "an invite
   from a non-moderator is refused" passed with the receive-side moderator check
   DISABLED, because it used `composeRoomInvite` and was really testing the
   *compose-side* precondition - which is only UX, since a hostile client never
   calls our compose path. Rewritten to inject a hand-built, validly self-signed
   invite via `composeOutbound`. **Lesson for this codebase: when the security
   boundary is on receive, the test must bypass compose.**

## Where the next instance starts

**O187's Android half**, which needs a machine: the Room DAO/entity for
`RoomRepository`, the `RumorDatabase` version bump + exported schema JSON, and
the `AppModule` wiring (`single { }` for the repo + `RoomAuthorityResolver`
passed into `GossipEngine`). Until that lands, rooms work in
`:core`/`:simulator` and the phone app still has no room storage. **Bundle O185
with it** - both are the same blocking-read fix.

If no machine is available, the best no-device items are **O213** (bounded
hold-queue for early lifecycle ops - read O149 first, and do not re-offer
dropped ids), **membership-policy enforcement at join** (pure `:core`, mirrors
O89's shape), and **O209** (`docs/RELEASE_GATES.md`).

Not started and NOT to be started without the user: nothing — O210 settled the
only open product decision. O203 still needs defaults.

## Build in THIS environment (hard-won; none of it is in the repo)

The 2026-08-03 handoff's command does not work here. What does:
- **No JDK 17 and no Android SDK installed.** Fetched Temurin 17 to the session
  scratchpad; `:app` simply cannot be built in this container.
- **`gradle.properties` sets `file.encoding` but NOT `sun.jnu.encoding`**, and
  `RbsrBandwidthScenarioTest` has an **em-dash in a test method name** → its
  class file path is unwritable under a POSIX locale, and the whole
  `:simulator` test compile fails with `InvalidPathException`. Needs
  `LANG=C.utf8` (that exact spelling) **plus**
  `-Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"`.
  Worth fixing in-repo: a non-ASCII test name is latent fragility.
- Maven Central intermittently **429s** this container's shared proxy IP while
  `curl` to the same URL returns 200; worked around by curl-prefetching into
  `~/.m2` behind a scoped `--init-script` adding `mavenLocal()` to
  `dependencyResolutionManagement` (the project sets
  `FAIL_ON_PROJECT_REPOS`, so a project-level `repositories {}` is rejected).
- Also needed `-Dkotlin.compiler.execution.strategy=in-process`.
