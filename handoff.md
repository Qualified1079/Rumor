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
