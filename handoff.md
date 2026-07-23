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
