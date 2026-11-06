# Session ELI5 — 2026-11-06 (security fixes from the implicit-state-machine audit)

Plain-English after-action report for every backlog row this session fixed and
closed. **Newest work at the bottom.** Each entry: what was wrong, why it mattered,
what changed, how I proved it, and what I did *not* do. Each row is its own commit
(`git log --grep=O2xx`), so any one can be reverted alone.

Earlier the same day: the `away/2026-10-11-overnight` branch was merged into `main`
(row numbers renumbered — see that commit), and O229–O248 were filed from an audit
of "implicit state machines": places where the app's state is spread across loose
flags instead of one explicit state, so the pieces can disagree.

---

## How everything here was verified (applies to every entry)

- **Built and tested in a cloud container**: `:core`, `:node`, `:simulator` (pure
  JVM). **`:app` cannot be compiled here** (no Android SDK) — any `:app` line is
  hand-reviewed only, and each entry says so if it touches `:app`.
- **Teeth check on every fix**: I disabled the fix, confirmed that exactly the new
  test fails, then restored it. A test that still passes with the fix removed
  proves nothing.
- **Every "must be refused" test has a positive control**: proof the hostile input
  really arrived, so "nothing happened" means "refused", not "never got there".
- The full simulator suite has one known load-flaky class
  (`LanLivenessValidationTest`, real sockets). When it goes red under the full
  suite I re-run it alone and say so.
- Maven Central rate-limits this container's IP (HTTP 429). Builds go through
  Google's Maven Central mirror via a throwaway Gradle init script in the session
  scratchpad — nothing about that is committed.

---

### 1 — Anyone could mark your messages "delivered" (O233 → G65)

**The plain version.** When you send Bob a direct message, Bob's phone sends back
a small "got it" note (a DIRECT_ACK) so your phone can show "delivered". Your phone
believed *any* "got it" note addressed to you, from *anyone*, about *any* message
id. Mallory could send "got it, re: your message #123" and your phone would mark
your message to Bob delivered — when Bob may never have received it.

**Why it mattered.** Not visible yet (the ✓✓ UI is O203, not built), but the moment
it ships it would be a lie the app tells you about whether someone got your
message. Under the long-collapse threat model that is exactly the information you
act on.

**What changed.** `GossipEngine` now looks up the acknowledged message in your
own message store and accepts the "got it" note only if (a) you wrote that
message, (b) it was a direct message, and (c) the person who sent the note is the
person you sent the message to. No new state was needed — your own sent DMs are
already stored.

**One honest cost.** If your phone's store has evicted that sent message (it keeps
a bounded number), a real "got it" note for it is now ignored. That can only *hide*
a ✓✓, never show a false one.

**How I proved it.** New test in `DirectAckDeliveryTest`: a third phone (Mallory)
sends genuinely signed "got it" notes — one for your real message to Bob, one for a
made-up id. Positive control: both notes provably reached your phone. Then: no
"delivered" for either. The original test (Bob's real note *does* mark it
delivered) still passes, which shows the fix is not just blocking everything.
Teeth: with the check disabled, the new test fails and the others pass.

**Not done.** Per-message delivery state (pending → passed-on → delivered) is still
two separate signals the UI has to combine; that lands with O203's delivery column.

---

### 2 — Any phone could make itself your "priority" link (O234 → G66)

**The plain version.** Two phones can agree to be "priority" partners: your phone
then works harder to keep a standing connection to that one. The handshake is "can
we be partners?" → "yes". Your phone accepted the "yes" without checking it had
ever asked. So Mallory could just send "yes" and become your priority partner,
holding a standing link to you without you ever choosing her. That matters because
a peer that shapes your persistent links is in a position to shape what you see
(the eclipse / sybil family, O135/O194).

**What changed.** Your phone now remembers who it asked (in memory, at most 256
outstanding requests, each good for 7 days, because under the long-collapse model
an answer can take that long). A "yes" counts only if it answers one of those
questions, and is used up once. Also, accepting someone else's request now checks
the request was actually addressed to you.

**Something you should know.** Nothing in the app calls this handshake today:
there's no "request priority link" button. The only live way a contact ever became
a priority peer was the forged-"yes" hole itself. Priority links in practice come
from the backbone planner (O98), which this doesn't touch. So closing the hole
removes no working feature.

**How I proved it.** New `PriorityLinkAcceptBindingTest`. (1) Mallory sends a
genuinely signed "yes" nobody asked for; positive control proves it arrived and
Mallory is a known contact; result: not a priority peer. (2) Control: you ask Bob,
Bob says yes, and Bob *does* become your priority peer, so the fix is not a
blackhole. Teeth: with the check removed, test (1) fails.

**Not done.** There is still no "stop being partners" message (one-sided removal
only), and a restart forgets outstanding requests, so you'd have to ask again.
Both only matter once a UI for this exists.
