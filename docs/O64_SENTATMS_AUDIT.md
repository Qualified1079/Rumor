# O64 audit — every `sentAtMs` reference in the codebase

Compiled while user was asleep. Purpose: when O64 implementation starts, this is the punch list of every site to touch and the categorisation of each.

**Rule from CLAUDE.md O64:** `sentAtMs` is sender-asserted, trivially forgeable, and (post-collapse) not even reliably synchronised between honest nodes. The fix is:
1. Add derived `displayTimeMs = min(sentAtMs, receivedAtMs)` computed property — not on the wire.
2. Ingest-time sanity clamp: `sentAtMs > now + SKEW_TOLERANCE` ⇒ clamp (do not reject — just clamp). Suggest SKEW_TOLERANCE = 5 min.
3. **No protocol behaviour gates on `sentAtMs`.** Eviction, dedup TTL, retry timers MUST use `receivedAtMs` or monotonic deltas.
4. UI "X ago" rendering uses `displayTimeMs` for ordering and `receivedAtMs` for elapsed-time labels.

---

## Category A — Legitimate uses (compose-time, signature, wire format)

These are correct; no change needed.

| Site | Use |
|---|---|
| `core/.../GossipEngine.kt:260` | `sentAtMs = clock.now()` — compose-time stamp. ✅ |
| `core/.../GossipEngine.kt:854` | `sentAtMs = clock.now()` — second compose site. ✅ |
| `core/.../MessageStore.kt:166` | Inside `signableBytes` — covered by Ed25519 signature. ✅ Removing it from the signed set would let a sender or relay arbitrarily rewrite the timestamp without invalidating the signature, which is worse than the current "honest sender stamps once, dishonest sender's lie is detectable as drift." Keep it signed; just don't trust it. |
| `core/.../Message.kt:19` | The field itself. ✅ |
| `core/.../sync/Rbsr.kt:12` | Docstring reference to RBSR ordering by `(sentAtMs, id)`. ✅ Note: RBSR ordering uses the sender-asserted value; this is fine because RBSR's correctness only requires consistent ordering on both sides, not honest-time ordering. A malicious sender forging a far-future timestamp only sorts their own message into the future — they don't disrupt anyone else's items. |
| `simulator/.../MessageGenerator.kt` | Synth-stamp at compose time. ✅ |
| `simulator/.../SimTransport.kt` | Same. ✅ |
| `simulator/.../RbsrSimTransportTest.kt` | Test-fixture stamps. ✅ |
| `app/.../GossipSession.kt` | Likely compose-time (verify). ✅ |
| `app/.../data/Entities.kt` | Room column declaration. ✅ |
| `app/.../adapter/MessageRepositoryAdapter.kt:36,46` | Pass-through between core and Room. ✅ |
| `app/.../plugin/meshtastic/MeshtasticBridge.kt` + `meshcore/MeshCoreBridge.kt` | Compose-time stamps when bridging foreign-network content into Rumor. ✅ |
| `app/.../scheduler/SchedulerTest.kt` | Test fixture. ✅ |

## Category B — Already mitigated correctly

| Site | Mitigation in place |
|---|---|
| `app/.../data/MessageDao.kt:39-48` | `ORDER BY MIN(sentAtMs, receivedAtMs) DESC` — this is the original 9cb2ad9 fix. ✅ **This is exactly the `displayTimeMs` rule, just embedded in SQL.** When O64 lands, this should be refactored to compute the derived `displayTimeMs` once (either as a generated column or in code) and order by it, so the rule appears in one place rather than every query. |

## Category C — Bugs that need fixing (`sentAtMs` used directly where `displayTimeMs` should be)

| Site | Bug |
|---|---|
| `app/.../ui/feed/FeedScreen.kt:88` | `formatElapsed(System.currentTimeMillis() - message.sentAtMs)` — uses sender-asserted time for "X ago" label. A malicious sender claiming `sentAtMs = 0` would render "55 years ago"; one claiming `sentAtMs = future` would render negative. **Fix:** use `receivedAtMs` (what we actually know) for elapsed-time labels. |
| `app/.../ui/messages/ThreadScreen.kt:145` | Same bug. **Fix:** same — `receivedAtMs`. |
| `app/.../ui/messages/MessagesScreen.kt:84` | Same bug. **Fix:** same — `receivedAtMs`. |
| `app/.../ui/messages/MessagesViewModel.kt:72` | `msgs.maxBy { it.sentAtMs }` — picking "latest message" by sender-asserted time. A malicious sender can pin their message to the top of a thread permanently. **Fix:** use `displayTimeMs = min(sentAtMs, receivedAtMs)`. |
| `app/.../ui/messages/MessagesViewModel.kt:81` | `.sortedByDescending { it.lastMessage.sentAtMs }` — thread-list ordering by sender-asserted time. Same pinning attack. **Fix:** sort by `displayTimeMs`. |

## Category D — Worth a second pass

| Site | Why |
|---|---|
| `simulator/.../engine/SimTransport.kt` and `simulator/.../engine/MessageGenerator.kt` | Verify that sim-side ordering doesn't accidentally depend on sender-asserted time in a way that masks the production bug. The simulator's `Clock` injection (O12) means all sim-time is internally consistent, but if an assertion or trace reads `sentAtMs` as "the real time," replace with `receivedAtMs`. |
| `core/.../sync/Rbsr.kt` line 12 docstring | Re-read after the audit. **Conclusion above (Category A) stands** — RBSR ordering on sender-asserted time is fine because both peers see the same forged stamp and order it consistently. But document this explicitly in the spec (`docs/wire-format.md` §2.9) so future implementers don't think they need to "fix" it. |

---

## Implementation order when O64 starts

1. **Add `displayTimeMs` extension property** on `RumorMessage`:
   ```kotlin
   val RumorMessage.displayTimeMs: Long
       get() = minOf(sentAtMs, receivedAtMs)
   ```
   No wire format change. Pure-Kotlin, lands in `commonMain` after Phase 1b/1c.

2. **Add `SKEW_TOLERANCE_MS = 5 * 60 * 1000L` constant** in `MessageStore`.

3. **Ingest-time clamp** in `MessageStore.ingest`:
   ```kotlin
   val now = clock.now()
   val clampedSentAtMs = if (msg.sentAtMs > now + SKEW_TOLERANCE_MS) now else msg.sentAtMs
   // Store msg with clampedSentAtMs into the repo
   ```
   Note: clamping rewrites a signed field, so the stored copy will not verify against the original signature anymore. **Two options:**
   - Store both `sentAtMs` (original, for sig verify on outbound relay) and `clampedSentAtMs` (for local use). Adds a field but keeps the signature valid.
   - Don't store the clamped value — only use it at display/sort time via `displayTimeMs`. **Lean toward this.** It's smaller, and `displayTimeMs = min(sentAtMs, receivedAtMs)` already handles future timestamps by capping at receive time.
   - **Decision:** the `displayTimeMs = min(sentAtMs, receivedAtMs)` formulation already does the right thing for forged-future timestamps (a future `sentAtMs` is dominated by `receivedAtMs` in `min`). So the explicit clamp may be **unnecessary** — the `min` IS the clamp, for display purposes. The only remaining concern is "what if a malicious node forges `sentAtMs = 0`?" — then `displayTimeMs = 0`, the message renders as "from 1970." Not great UX but not a security bug. Could add a separate UI rule "if `displayTimeMs` is suspiciously far from `receivedAtMs`, just show `receivedAtMs`."

4. **Fix Category C sites** — straight find-and-replace per the table. ~5 lines each.

5. **Refactor MessageDao** to use `displayTimeMs` as a generated column or computed in Kotlin once; remove the inline `MIN(...)` from queries.

6. **Document in wire-format.md §3.1** that `sentAtMs` is sender-asserted and receivers must use `displayTimeMs`. (Already noted in the draft.)

---

## Decisions deferred

1. **Whether to do an explicit ingest-time clamp**, vs. relying on `displayTimeMs = min` to handle it implicitly. Lean: rely on `min`. Cheaper, fewer fields, signature stays valid on relay.
2. **`SKEW_TOLERANCE_MS` value if explicit clamp goes in.** 5 min is a guess matching the O64 note; could be larger (1 hr) for post-collapse drift tolerance, since the threat model isn't "forge time" any more, it's "honest nodes have desynced clocks." Probably want a generous value, e.g. 1 hr.
3. **Whether Category C UI fixes ship before Phase 1b lift** — they're small `:app` edits, fully decoupled from KMP. Could land in a separate small PR right now. Lean: yes, land them now; they're a real security bug. **Held back from this session because changing UI code without you reviewing the framing copy risks the kind of "I almost made a decision" you flagged.**

---

## Total touch surface

- 5 UI bug fixes (Category C)
- 1 Dao refactor (Category B)
- 1 new `displayTimeMs` extension (+ 1 line in `Message.kt`)
- 1 constant (+ maybe 3 lines for explicit clamp if we go that route)
- Maybe 2-3 simulator test fixture verifications (Category D)

Real effort: 1–2 hours after Phase 1b lands. Decision-blocked on (1) above.
