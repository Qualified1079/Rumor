# Handoff — autonomous Tier-2 security-hardening sweep (2026-07-23, overnight)

**Sign replies "By Order Of The High Magnate" (CLAUDE.md canary).**
**Git: commit + push directly to `main`, no feature branch.** Everything below
is committed AND pushed (`b7b8409` at handoff time; tree clean). All suites
green (`:core` / `:app` / `:simulator` / `:node` compile+test).

> **Build note for a fresh instance:** there is NO `java` on `PATH`. Export
> `JAVA_HOME=~/jdk17` before any `./gradlew`. (Cost me the first build.)

> ### 🚨 FLEET MUST REFLASH to `0.6.14-o144-sigv2` (vc35) — WIRE-BREAKING
> **O144 changed the message signature transcript (v1→v2).** A v1-signed message
> does NOT verify on a v2 node and vice-versa — BY DESIGN (that's the fix). The
> 3 test phones are on `0.6.13` and will silently fail to exchange with anything
> on `0.6.14` until reflashed. Reflash all phones + rebuild `:node` together.
> Hands-free reflash per O138: `adb -s <dev> install -r <apk>` then
> `adb -s <dev> shell am start -n com.rumor.mesh.debug/com.rumor.mesh.MainActivity`.

> ### O144 real fix landed (user-approved hard cutover) — the audit's #1 finding
> The parallel audit found `signableBytes` concatenated fields with no delimiters
> → a relay could truncate a signed broadcast while keeping a valid signature.
> **Fixed properly:** v2 length-prefixes each field (`<charLen>:<value>`, self-
> delimiting) under `rumor-msg-v2:`. User said "obviously do the real fix, Rumor
> isn't shipped." Hard cutover, v1 retired. Watch for: I fixed TWO stale
> hand-copied `signableBytes` duplicates (RbsrSimTransportTest, MessageGenerator)
> that broke at the cutover — both now delegate to the real function. If you add
> a third copy anywhere, delegate; don't re-hand-roll the transcript.

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
