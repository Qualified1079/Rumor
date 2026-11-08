# Handoff — away-mode security-review sweep (2026-07-31)

Away mode was ON for this session, now OFF (user returned and asked to wrap up).
Desktop/JVM was the test environment (no phones this session). Followed the
2026-07-30 handoff's explicit priority: **the systematic security review in
`docs/SECURITY_RESEARCH.md`, do-first passes in order.** Three units shipped,
each its own commit, pushed to `main`. `:core`/`:simulator`/`:node` green.

**Environment gotcha (fixed, will bite the next session if reverted):** the
toolchain defaults `JAVA_HOME` to `/app/extra/jbr` = **JDK 25**, which Gradle
8.13 / its Kotlin DSL compiler cannot parse (`IllegalArgumentException: 25.0.2`
during build-script compile). A **JDK 17 lives at `/home/user/jdk17`**. I pinned
`org.gradle.java.home=/home/user/jdk17` in `~/.gradle/gradle.properties` (machine-
local, not committed). If builds suddenly fail on a version-parse error, check
that line is still there.

**Shipped this session (each its own commit):**
- **`0d7d89b` — O156 signature-splice bug class killed structurally (do-first #1).**
  The O144/O156/O157 bare-delimiter transcript splice had shipped, been "fixed,"
  and recurred 3×. Now: ONE primitive `core/protocol/SignableFraming.kt`
  (`framed`/`framedList`, netstring `<len>:<value>`) backs every free-text
  transcript incl. `MessageStore.signableBytes`; six vulnerable builders re-framed
  under fresh `-v2:` tags (keywordFilterList = the live vuln; messageDelete,
  bridgeVouched, roomCreate, roomAction, roomPostingCert — the last four latent/
  unwired but fixed pre-emptively); all six `-v1:` tags recorded retired in
  `RENAMED_FIELDS_NEVER_REUSE.md`. **`SignableFramingGuardTest` source-scans every
  `*SignableBytes`/`*ChallengeBytes` builder and FAILS the build on any new
  bare-delimiter join** unless on a justified splice-safe allowlist (blocklist×2,
  prekey, envelope, roomInvite, hello×2 — every field fixed-shape token or
  self-signed). `SignableFramingInjectivityTest` demos each former v1 collision +
  the v2 non-collision. Full 12-transcript classification table in
  `SECURITY_RESEARCH.md` §DO-FIRST #1 (marked ✅). DomainTagInvariantTest / RoomTest
  updated to the v2 tags.
- **`5cc7362` — crypto-primitive audit, partial (do-first #3).** RNG/AES-GCM
  **reviewed clean** (SecureRandom-backed, fresh random 96-bit IV per encrypt,
  per-message ephemeral keys → no nonce-reuse risk; recorded in the doc, no code
  change). **Constant-time fix:** `RoomTagMatcher.match`'s ENCRYPTED branch compared
  the secret-derived tag `HMAC(routingKey, messageId)` with early-returning
  `contentEquals` = a byte-at-a-time tag-forgery timing oracle. Added
  `core/crypto/ConstantTime.equals` (XOR-accumulate) + routed both matcher branches
  through it; set the convention on `SealedSenderTag` KDoc (its unwired receiver-side
  match MUST use it). `ConstantTimeTest` pins correctness incl. high-bit/sign-ext.
  Other `contentEquals` sites are over public data (pinned pubkeys, content hashes,
  userIds) — not oracles.
- **`813cfcc` — O172 RBSR frame-flood cap (do-first #4).** `Rbsr.respond()` looped
  over an unbounded frame batch, each Fingerprint frame an O(N) store scan → one
  ~4MB / ~30k-frame packet = O(frames×N) CPU on large-store anchors. Added
  `MAX_RBSR_FRAMES_PER_ROUND=10_000` (far above any honest delta round; with
  `MAX_RBSR_ROUNDS=12` bounds total per-session work). `RbsrFrameCapTest` proves it
  via a call-counting storage. Backlog rows O156/O172 marked DONE in `CLAUDE.md` +
  `docs/OPEN_BACKLOG.md`.

**IN PROGRESS — nothing written, tree clean (investigation only):**
- **O176 (`BreadcrumbCache.snapshot` unbounded in-memory map)** — do-first #4
  continued. Confirmed the real bug: the ConcurrentMap's **key count** (distinct
  target userIds) has NO bound; each value is capped at 5 but targets aren't, and
  `pruneOld()` only prunes the persistent repo, not the snapshot. **Fix plan:**
  bounded access-ordered LRU mirroring `MeshViewTracker`'s pattern — replace the
  `ConcurrentMap` snapshot with a `synchronized` access-order `LinkedHashMap`
  overriding `removeEldestEntry` (compute() triggers `afterNodeInsertion`, so
  eviction fires), add a `maxTargets: Int = 2000` ctor param (mirrors
  `MeshViewTracker.maxPeers`) so a test can use a small cap and assert the LRU-
  oldest target is evicted via `candidatePeersSync` returning empty. Was about to
  read `BreadcrumbRepository` for a test fake when the session ended.

**Assessed, deliberately NOT done:**
- **O177 (`MeshViewTracker.pruneStale` dead code)** — LOW severity: `peers` is
  ALREADY LRU-capped at `maxPeers=2000` on insert (MeshViewTracker.kt:70-72), so
  there is no memory leak — `pruneStale` is a pure optimization with no callers.
  The row's real value is extending `PruneWiringInvariantTest` to catch this 4th
  orphaned-prune instance; do that with O176 (same file neighborhood, same theme).

**Next-session candidates (security review continues, in `SECURITY_RESEARCH.md` order):**
1. Finish **O176** (plan above) + fold in **O177**'s invariant-test extension.
2. Remaining do-first #4 DoS rows: **O166** (LanTransport unbounded pre-HELLO
   accept loop — `:node`/O104 makes this the laptop-AP attack surface), **O185**
   (per-`ROOM_MESSAGE` blocking SQLite reads via `runBlocking`).
3. Remaining do-first #3 crypto: HKDF extract/expand + domain-tag coverage,
   Ed25519 canonicalization (confirm the lib rejects non-canonical S / small-order),
   finish secret zeroization (O115/O121c — thread `CharArray` for passphrases).
4. Do-first #2: the LINDDUN + STRIDE threat-model artifact (docs, larger).
5. O157's other half (routing-tag-not-in-signed-transcript) is still open — same
   root class as O156 but a distinct fix (bind the resolved room INTO the signed
   transcript; a posting cert alone doesn't stop post-signing retag).
