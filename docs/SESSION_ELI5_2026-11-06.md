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

---

### 3 — Anyone could make your phone stop sending a file (O232, half → G67)

**The plain version.** Big things (photos, files) are sent in pieces. If the
receiver misses pieces, it asks again ("resend 3 and 7"), and your phone resends.
The receiver can also say "stop, I don't want this" (a TRANSFER_CANCEL), and your
phone gives up. Your phone obeyed "stop" from *anyone*, as long as the note was
addressed to you. So Mallory could kill your file transfer to Bob. Worse, for a
file sent to everyone (a broadcast), any single listener saying "stop" stopped
resends for *every* listener.

**What changed.** "Stop" now counts only from the person the file is for. For a
broadcast file, "stop" from one listener means "stop answering *that* listener's
resend requests". Everyone else keeps getting pieces, and the transfer stays
in progress.

**How I proved it.** Three new tests: (1) a stop from a stranger is ignored, and
the real receiver is still served; (2) broadcast: the listener who said stop isn't
served, and another listener still is; (3) the same as (1), but end to end over
the simulated radio with a genuinely signed forged "stop" and proof it arrived.
The older test, where the *real* receiver says stop and your phone does stop,
still passes. That's the check the fix isn't just ignoring every stop. Teeth: with
the new check removed, exactly the three new tests fail.

**Not done (why it's only half).** The other half of O232 stays open: a file
*you* send is never marked "done", so your phone keeps every sent file's pieces
forever. Fixing that needs a design choice (how a sender learns the file
arrived; how long to keep broadcast pieces), so I left it filed rather than guess.

---

### 3b — Found in passing: the file-cleanup job never runs, and was broken anyway (O249, filed)

**The plain version.** There's a cleanup job that's meant to delete old finished or
cancelled transfer records. Two problems. (1) Nothing ever runs it; it's the
fifth "periodic cleanup" in this codebase written but never wired up. (2) Even if
it ran, it looked for the words `complete`/`abandoned` in lowercase, while the
database stores them as `COMPLETE`/`ABANDONED`. The database treats those as
different words, so it would have deleted nothing.

**What changed.** I fixed (2), a one-line change in `TransferDao.kt`. **That file
is in `:app`, which can't be compiled here, so it's hand-checked only**; it's a
change inside an SQL string, which the compiler doesn't check anyway. I did *not*
wire up (1): that needs a schedule and a test with a controllable clock, so it's
filed as O249.
