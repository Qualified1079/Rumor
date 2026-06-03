# Backlog "already done" audit

Walked through the CLAUDE.md open-items list looking for entries whose described work is already shipped — same shape as the O13 finding earlier this session. Reading the file vs. reading the code.

Each finding has:
- **Status** — what the row currently says, what the code actually shows
- **Suggested action** — what the row should become (close, partial, no change)

You decide whether to edit CLAUDE.md.

---

## CLOSE — O3: Reliability half of throughput+reliability ranking

**Row says:** `[TODO/CODE]` — "Reliability score (success/failure ratio, drop count) is not tracked. Add a `failureCount` to `Route` and rank by `bytesRelayed / (1 + failureCount)`."

**Code says:** Done.
- `core/model/Route.kt:17` — `val failureCount: Int = 0`
- `core/routing/TopologyTracker.kt:75-80` — increments `failureCount` on session-failed events.
- `core/routing/TopologyTracker.kt:67,85` — comment confirms ranking formula `bytesRelayed / (1 + failureCount)`.
- `app/data/RumorDatabase.kt:22` — schema v5 added `RouteEntity.failureCount` migration note.
- `app/data/RouteDao.kt:13` — DAO comment says O3 ranking, uses the formula.

**Suggested action:** Move O3 to "Completed gaps" as a new G-entry: "G19 — O3 reliability-aware route ranking. `failureCount` field on Route + RouteEntity (schema v5). TopologyTracker increments on session failure. DAO orders by `bytesRelayed / (1 + failureCount)`. Commit: <find>."

---

## CLOSE — O13: BloomFilterData OOM on adversarial expectedItems

**Already documented** by entry 5 of `docs/SLEEP_SESSION_LOG.md` earlier this session.

**Code says:** `deserializeOrNull` companion exists, wired at the attack surface in `GossipSession.kt:239`. Other call sites use the raw constructor on locally-trusted sizes only.

**Suggested action:** Move O13 to "Completed gaps." Same shape as G18 (RBSR rounds investigation closed). Replace with note: "G20 — O13 closed. `deserializeOrNull` graceful fallback in core/transport/wifidirect/BloomFilterData.kt; receiver-side adversarial bloom no longer crashes the engine."

---

## CLOSE — O16: Relay-path spam — per-sender ingest rate limiting

**Row says:** `[TODO/SIM]` — "Mitigation: add a per-`senderId` token bucket in `MessageStore.ingest`…"

**Code says:** Done.
- `core/protocol/MessageStore.kt:30-78` — full O16 per-sender token bucket. `_rateLimited: AtomicLong` counter, `rateLimitedCount` field, gate at line 71-75 skipping ingest entirely when bucket exceeded.

**Suggested action:** Move O16 to "Completed gaps" as "G21 — O16 per-sender ingest token bucket in MessageStore. Drops over-rate inbounds at sig-verify time, well before relay. `rateLimitedCount` metric exposed."

---

## RECLASSIFY — O12: deterministic-replay non-determinism

**Row says:** `[DECISION]` — "Both [escalation paths] done as preemptive measures. Neither done preemptively — the 5% band keeps the assertion useful as a regression catch."

**Code says:** The "Neither done preemptively" framing is **stale**:
- `simulator/scenario/TopologyBuilder.kt:34-37` — single-threaded executor is in use ("O12 escalation" comment).
- `simulator/engine/SimNode.kt:160-161` — `knownMessages()` returns `sortedBy { it.id }` ("for determinism" comment).

**Both escalation paths are now shipped.** The row hasn't been updated to reflect that. It was the canonical example of "we'll do this if needed" — the doc just didn't get updated when we did.

**Suggested action:** Update O12 row body to read something like: "Both escalation paths now in place — single-threaded `sim-scope` dispatcher in TopologyBuilder; `knownMessages()` sorted by id in SimNode. Combined with the Clock injection (`receivedAtMs = clock.now()` per O12 follow-up), scenario 01/04 deterministic-replay assertions hold within ±5% across runs."

---

## RECLASSIFY — O25: Plugin crash isolation

**Row says:** `[TODO/CODE]` — "All calls into plugin code wrapped in try/catch. On unhandled exception: log + disable + notify."

**Code says:** Partial.
- `app/plugin/PluginRegistry.kt:75` — `runCatching { plugin.onDetach() }` on teardown ✅
- `app/plugin/PluginRegistry.kt:88-94` — try/catch around `onMessageReceived` with log ✅

What's NOT done:
- Auto-disable plugin for the session after a crash.
- Persistent disable across restarts on init-crash.
- User-visible notification "Plugin X crashed and was disabled."
- Crash-report endpoint per-plugin.

**Suggested action:** Reclassify O25 from `[TODO/CODE]` to `[PART]`. Update body: "Exception isolation per-call done; PluginRegistry wraps onMessageReceived + onDetach with try/catch + log. NOT done: auto-disable on crash, persistent disable across restarts, user notification, crash-report endpoint."

---

## NO CHANGE — items I checked and they're as the row says

- **O15** DEX loader — no `DexClassLoader` references in tree. Truly not started.
- **O21** TOFU emoji fingerprint — `publicKeyToUserId` exists (the cryptographic binding) but no emoji-fingerprint helper or display-name pinning UI.
- **O22** ScheduledMessage Room adapter — no `*Scheduled*` files under `app/data/`; row's "Room adapter deferred to next Android session" is accurate.
- **O17** BRIDGE_VOUCHED — payload + signature work done per G13; per-bridge UI toggles deferred per the existing row.

---

## Net effect if you accept all suggestions

- **3 rows move to Completed gaps**: O3, O13, O16. Counts shift from 71 → 68 open.
- **1 row gets body update**: O12 (note both escalation paths shipped).
- **1 row reclassified TODO/CODE → PART**: O25 (partial done).

The "TODO/CODE" subcount in the counts line bumps down by 3 (O3, O13, O16 leaving) — 28 → 25.

---

## Suggested CLAUDE.md edits

I have NOT made these edits. They're proposed for your review on wake. Three new G-entries to add to the "Completed gaps" section, three row-deletions from "Open items", one body-rewrite, one classification flip. ~30 lines of editing total.

If you'd rather I just do it, say so — I held back because (a) Completed-gaps entries often want a commit-hash reference that you'd know better than me (I can search for it but you can verify); (b) the framing of the new G entries should match your voice; (c) the counts line is right above the table and easy for me to typo.

Files: `docs/BACKLOG_ALREADY_DONE_AUDIT.md` (new, this doc).
Rollback: `rm docs/BACKLOG_ALREADY_DONE_AUDIT.md`.
