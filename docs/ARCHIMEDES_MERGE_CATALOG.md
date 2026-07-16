# Archimedes → main merge catalog

Decision log for merging `claude/practical-archimedes-wmySm` (+156 commits since
`9cb2ad9`, 253 files, ~16.7k insertions) into `claude/check-online-status-vef1H`
(+36 commits, 72 files), result to become the new `main`. Written 2026-07-16 on
branch `merge/archimedes` before any code moved.

## Ground rules (agreed with user)

1. **Authority rule:** on conflict, field-verified code wins. Everything in
   G18–G24 (transport, connection flow, session claiming, O92 reseed/backfill,
   adaptive RBSR gate) was bought with real-device debugging; archimedes'
   equivalents are 5-weeks-dormant and never field-tested. Archimedes wins where
   it has features this branch lacks.
2. **Layout:** flatten to pure-JVM `core/src/main` (user decision 2026-07-16).
   BUT built with Apple devices in mind: keep archimedes' `core/platform/` shim
   files as plain-JVM classes (fold each expect/actual pair into one file, keep
   call sites going through them), keep `ios/` bridge spec + IOS_PORT docs in
   tree, avoid new JVM-only APIs in `:core` hot paths. Re-splitting to KMP
   expect/actual later is then per-file mechanical. Their O63 decision (iOS via
   KMP `:core`, never a separate app) carries over as the recorded future path.
3. **One coherent wire format** at the end; all test phones reflash together
   (G24 precedent). Check every union decision against BOTH branches'
   `docs/RENAMED_FIELDS_NEVER_REUSE.md` (merge the two files — append-only).
4. Ratchet: resolve subsystem-by-subsystem; suites compile+green before moving
   on; simulator scenarios; then 3-phone hardware regression.

## A. Decision conflicts — user arbitration required, blocks the affected code

| Conflict | check-online position | archimedes position | Notes |
|---|---|---|---|
| **O36 rooms** | "No global broadcast room, ever" (FireChat poisoning lesson); closed-membership groups only | "Public Rooms allowed and ON by default, incl. a global Room (text-only, relay drops media for it)"; O79 Rooms protocol built on this | Determines whether O79 Rooms + O89 posting certs + RoomRoutingTag + RoomSubscription* come across at all, and in what shape. Their O36 body records real reasoning (self-selection, moderated-Room remedy, text-only global). |
| **G9 / O41 identity rotation** | Shipped (auto-rebind on rotation message: outer sig by new key + continuity sig by old key) | **Removed entirely**: stolen-old-key attacker signs a rotation to their own key → every contact auto-rebinds → permanent impersonation takeover. Names reserved in RENAMED_FIELDS forever. Deletion use case → signed IDENTITY_RETIRED (no successor). | Their security argument looks correct as stated — our shipped O41 auto-rebind is hijackable by exactly the key-compromise it exists to recover from. Options: adopt removal; or keep wire type but gate rebind behind manual recipient confirmation (their row suggests this as the future redesign). |

## B. Port (archimedes-only work, comes across; flattened to src/main)

- **Crypto substrate:** `HkdfSha256`, `HmacSha256`, `Ed25519ToX25519` (+ golden-vector,
  roundtrip, conversion tests), `CryptoGoldenVectorsTest`. Their CryptoManager split
  (common/jvm/ios) folds back into one JVM `CryptoManager` — zipper against ours
  (which has the G20 DM fixes; verify equivalence, theirs has the O91 full wiring).
- **O38 prekeys (receiver-FS groundwork):** `model/Prekey`, `PrekeyCache`,
  `PrekeyVerifier` + tests. Status: wire shape + verifier shipped; rotation
  scheduler, sender cache, deletion-on-expiry still open.
- **O40 relay-deletion-on-ACK (their G28):** `MessageType.MESSAGE_DELETE`,
  `MessageDeletePayload`, `MessageDeleteVerifier` (sender OR recipient may issue,
  third parties rejected). Closes our open O40 row. Also prerequisite for the
  black-hole rep design (handoff session-B thread 1).
- **O53 sealed-sender:** `SealedSenderKey/Tag` + `wire/SealedSenderTagExt` + tests.
  Compose-side stamp shipped; receiver-side precompute/matching open.
- **O64 `sentAtMs` untrusted-everywhere (their G24):** `displayTimeMs = min(sentAtMs,
  receivedAtMs)` + `SentAtMsLintTest` allowlist guard + UI consumption. Directly
  fixes our §11 MessagesViewModel thread-pinning bypass; supersedes part of O96.
- **O85 two-tier dedup (their G23):** Tier 0 LRU 200k + Tier 1 bloom long-tail.
  NOTE: does NOT fix the §2 dedup-before-sig-verify ordering bug — apply the §2
  fix on top of the two-tier structure during the zipper.
- **O80 `ModeProfile` + matcher** (`core/mode/`) — the exact artifact our O62 gate
  demands. Zipper with our `StaticMode` (9 files) — ModeProfile supersedes it.
- **O67 keyword filters:** `core/filter/*` (matcher/publisher/subscriber/verifier
  + gossip bridge), `KeywordFilterRepository` + Room DAO/entities/adapter. UI open.
- **O76 compression + padding:** `wire/CompressedPaddedCodec`, `PaddingBuckets`,
  `CompressedPaddedExt` + tests. Compose-side flip landed; capability negotiation
  open. Wire-affecting — fold into the unified wire format.
- **O90 thread + mention metadata:** `wire/ThreadAndMentionExt` + tests.
  Substrate + compose shipped; UI open. Interacts with the ordering decision
  (task #8): thread refs are a step toward option D (causal refs).
- **O31 HELLO route ads + sig-domain v2 (`HelloChallengeV2Test`):** their HELLO
  signs a v2 transcript; our HELLO added `knownCount` (RBSR gate). MUST unify
  into one HELLO transcript — highest-care wire item in the whole merge.
- **G27 `PluginContext.signWithLocalKey`** — pre-req for O20/O44 Keystore work.
- **Invariant tests:** `TrafficClassInvariantTest`, `DomainTagInvariantTest`,
  `SourceInvariantTest`, `SentAtMsLintTest` — port all; they encode invariants as
  executable checks.
- **`core/platform/` utils:** `BoundedFifoMap` (use for §2/§4 unbounded-map fixes),
  `AtomicCounter`, plus the 10 shims (Base64, Sha256, SecureRandom, Uuid, Clock,
  ConcurrentMap, RwLock, Compression, PlatformCrypto) folded to plain JVM.
- **Scheduled messages Android wiring:** `ScheduledMessageDao/Entity/Adapter` —
  closes the "Room adapter deferred" remainder of our G15 (engine-side already
  shared). Check whether MeshService polling wiring exists on their side.
- **Docs tree:** `wire-format.md`, `MULTI_INSTANCE_COORDINATION.md`,
  `CRYPTO_PRIMITIVES_AUDIT.md` (their G26), `RESEARCH_NOTES.md`, `PROJECT_KEYS.md`,
  `FDROID_BUILD.md`, `ROOMS_THREAT_MODEL.md` + `O79_PROTOCOL_SPEC.md` (even if O36
  decision drops Rooms, keep specs as record), IOS_* docs, `fastlane/` metadata.
- **Their O82 decision** (median-clock consensus rejected; per-use-case local
  fixes) — carries into our O95/ordering decision (task #8) as input.
- **Their G25 (O39 key-lifecycle audit)** — port the audit conclusions; our O39
  row stays open only for the deltas (constant-salt HKDF note from session B).
- **`RumorLog`, `SimClock`, sim/scenario upgrades** — take unless they collide
  with our sim fixes (PerPeerRoutingTest etc. — ours win per rule 1).

## C. Conditional on the O36 decision

`model/Room*`, `RoomPostingCert*`, `RoomRoutingTag*`, `RoomTagMatcher`,
`RoomSubscriptionRepository/Dao/Entities/Adapter`, `MultiRecipientEnvelope*`
(their O52-subsumed group-key mechanism), room bits inside GossipEngine/
composeDirect/ThreadViewModel. If user upholds our O36 (no public rooms):
MultiRecipientEnvelope + closed-membership subset may still be worth porting
for O52; the open/global-room surface stays out.

## D. Drop / ours-wins

- `iosMain/` actuals (drop; shims become JVM), KMP `core/build.gradle.kts`
  (keep ours; add their non-KMP deps), their `gradle.properties` KMP flags.
- Their versions of: `GossipSession`, `WifiDirectTransport`, `MeshService`
  connection flow, `Rbsr.kt`/`RbsrWire.kt` (ours has additive fingerprint +
  adaptive gate + field verification; theirs is [PART] and older), `BloomFilterData`
  (ours: murmur3 + 0.01% FP), O92 reseed/backfill, `AppModule` structure (theirs
  merged additively for new repos), their `ci.yml` (ours + task #6 fixes).
- Their O42 row status (ours is CLOSED with field verification — newer).
- Their O2 row ([PART]) — our G19 closed it.

## E. Known zipper hotspots (the 29 textual conflicts + high-risk overlaps)

`GossipEngine` (biggest: their rooms/sealed-sender/delete/prekey hooks × our
backfill/RBSR/breadcrumb work), `MessageStore` (their two-tier dedup + rate
counter × our ingest path; apply §2 fix here), `GossipPacket`/`Message` model
(union of fields; check RENAMED_FIELDS), `DuplicateFilter` (two-tier replaces
ours; re-seed API from O92 must survive), `CryptoManager` (fold 3-way),
`NeighborStore`, `InMemoryRepos`, `AppModule`, `RumorDatabase` (schema union →
bump version, one destructive migration pre-release is fine), 6 sim tests,
`CLAUDE.md` (merge backlogs: renumber colliding G/O rows — BOTH branches used
G19–G29 and O62–O109 ranges for different things; ours is canonical numbering,
theirs get new numbers), the two handoff files (keep both, ours at root).

## F. Wire-format union checklist (single deliberate design, then reflash all)

1. HELLO: one signed transcript carrying BOTH `knownCount` (ours) and
   `routeAds`/v2 domain (theirs); bump sig-domain version once.
2. `_ext` registrations: sealed-sender tag, compressed+padded, thread/mention,
   room routing tag (if O36 allows) — confirm no key collisions.
3. New MessageTypes: MESSAGE_DELETE (+ ROOM_* if kept); confirm enum names
   against both RENAMED_FIELDS files.
4. Bloom: ours (murmur3, 0.01%) is canonical; their two-tier filter's Tier 1
   uses BloomFilterData — port on top of our impl.
5. Identity rotation wire type: per the G9/O41 decision above.
6. Merge both `docs/RENAMED_FIELDS_NEVER_REUSE.md` files append-only.

## G. Validation ladder

1. `:core:test` + `:simulator:test` + `:app:testDebugUnitTest` green (their
   ported tests included — commonTest/jvmTest fold into src/test).
2. Simulator scenario suite (both branches' scenarios).
3. Flash all phones together (wire break OK), G24-style regression: 3-node star,
   restart reseed, multi-hop store-and-forward, DM roundtrip, plus smoke of the
   newly-ported surfaces that have UI.
4. Fast-forward/push result to `main`; keep both original branches as archive
   refs until hardware pass is green.
