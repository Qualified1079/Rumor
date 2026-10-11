# Session ELI5 — 2026-10-11 (overnight away-mode build)

Plain-English log of everything done while you slept. **Newest work at the
bottom.** Each entry: what it is, why it matters, where the code lives, how I
proved it, and whether it needs your sign-off.

> Branch: **`away/2026-10-11-overnight`** — *nothing* was pushed to `main`, as
> you asked. Review this file, then merge/cherry-pick/bin whatever you like.
> Every commit is signed "By Order Of The High Magnate".

---

## ⚠️ Read this first — two things you should know before reviewing

### 1. The three phones were NOT reachable from this session

You said three phones were connected. They're connected to **your machine** —
this session runs in a **cloud container** with no `adb`, no Android SDK, and no
route to your LAN. I checked for all three. So:

- **Zero on-device testing happened.** Nothing in this log is device-verified.
- **The whole `:app` module could not even be compiled**, let alone tested
  (no Android SDK in the container). Every `[TODO/EMU]` and `[TODO/HW]` row, and
  every row whose fix lives in `:app`, was therefore **off-limits** — I skipped
  them rather than write code I couldn't build.
- What I *could* do, and did: `:core`, `:simulator`, `:node` — pure JVM, fully
  testable here. That's also where the last two away sessions did their work, so
  the backlog there is deep enough to fill a night.

If you want on-device work next time, the session has to run on the machine the
phones are plugged into (local Claude Code, not a cloud session).

### 2. The container had no JDK 17

`JAVA_HOME=/home/user/jdk17` from the handoff didn't exist (fresh container, only
JDK 21, and the build's toolchain pins 17). I downloaded Temurin 17 to
`/home/user/jdk17` so the build works. That's container setup, not a repo change
— nothing committed for it. Maven Central was also rate-limiting (HTTP 429) at
session start, which cost ~20 minutes of retries before dependencies resolved.

---

## TL;DR of what got built

1. **Phones now wait longer before deciding a neighbour is gone** (O205) — the
   "is this relay still alive?" timer is no longer a flat 30 seconds; it scales
   with how often your phone actually meets other phones in its current power
   mode. Stops battery-saving phones from spraying messages everywhere over a
   link that was never down. ✅ shipped + tested.
2. **Found and fixed a real message-delivery bug** (O208) — a relay holding a
   message *for you*, with *you standing right there*, would sometimes not hand
   it to you. ✅ shipped + tested. **This is the one worth your attention.**
3. **Fixed the test harness lying about preconditions** (O209) — one existing
   test was proving nothing at all, and it's how #2 was found. ✅ shipped.
4. **Wrote the first tests for the file-transfer defences** (O159, part) — five
   anti-abuse checks on the receiving side had **zero** tests. Now covered, and
   I verified each test actually catches the thing it claims. ✅ shipped.
5. **Three more tests that couldn't tell success from nothing-happened** (O210) —
   including one that made a working feature look broken. ✅ shipped.

---

## Work log


### 1 — Phones shouldn't give up on a quiet neighbour too fast (O205)

**The plain version.** When your phone wants to send a direct message, it tries
to pass it along a known trail toward the recipient rather than shouting it at
everyone (cheaper, more private). Before using a trail, it asks "have I heard
from the next phone on it recently?" If not, it assumes that phone is gone and
falls back to shouting.

"Recently" was hardcoded to **30 seconds**. The problem: a battery-saving phone
only looks around for other phones about every **30 seconds**, so a perfectly
healthy neighbour routinely goes 30+ seconds without being heard from. The phone
would declare it dead and shout — wasting exactly the battery the power-saving
mode exists to save.

**The fix.** The timer is now worked out from how often the phone *actually*
meets other phones in its current mode, rather than being one number for
everything:

| Mode | Meets peers about every | New "still alive" window |
|---|---|---|
| Mobile (in your pocket, saving battery) | 30 s | **90 s** |
| Static (sitting on a shelf) | 15 s | **45 s** |
| Free (plugged in, full power) | 5 s | **15 s** |

Three contacts of grace, so one missed beat doesn't condemn a live link.

**Where:** `ModeEnvelope.livenessWindowMs`, `GossipEngine` (new optional
`modeState`), plus one line each in the app's wiring and the simulator.

**How I proved it.** Two tests. The interesting one builds a 6-phone mesh where
everyone talks on a **45-second** beat — deliberately between "Free" (15 s) and
"Mobile" (90 s) — so the *same* network and the *same* traffic separate the
modes. On Mobile the message stays on the trail and never touches the bypass
phone; on Free it shouts. **And the control that makes that meaningful:** I then
hold the mode at Mobile and change *only* the timer back to the old 30 s — the
shouting comes back. That proves the timer is what causes the behaviour (and not
some other difference between the two modes), and it reproduces the original bug
on demand. A third arm checks the longer window doesn't make the phone slow to
react to a *genuinely* dead relay: it doesn't, because a phone it has never heard
from at all has no timer to expire.

**Needs your sign-off on one thing:** the three numbers (90/45/15 s) are a
judgement call, not a measurement — "3 contacts of grace" is the same reasoning
the old 30 s encoded for a 10 s cadence. If you'd rather they were 2 or 5
contacts, it's a one-line change.

---

### 2 — ⭐ A relay would withhold a message from the person it was for (O208)

**This is the most important thing in this log.** It is a real delivery bug in
shipped behaviour, and I found it by refusing to accept a flaky test as noise.

**The plain version.** Say Carol sends you (Alice) a direct message. It hops to
Bob. Bob has a trail note saying "to reach Alice, go via Dave." Bob then marks
the message "only offer this to Dave" — and *you are standing right there*, Bob's
direct neighbour. Bob does not give you your own message.

You did still get it, eventually: Bob re-offers everything in his store on later
rounds, and that re-offer path has a special case for "is this peer the actual
recipient?" So the two code paths **disagreed**, and the wrong one ran first.

**Why "eventually" isn't good enough here.** Your whole threat model (O55) is
weeks-long infrastructure collapse with duty-cycled phones. Two phones may be in
range of each other for *one* round, once, for hours. "He'll offer it next time"
can mean never.

**Why it hid.** It looked like a flaky test. Whether the bug bit depended on
whether Bob's marked copy was still in his outbound queue at the exact moment the
recipient was offered to — so the test failed about 1 run in 3 and looked like
timing noise. It had presumably been doing that for a while.

**The fix.** One line: the recipient is always included in the "offer to" set at
relay time, so both paths agree. The recipient is trivially the best next hop.

**How I proved it.** A new test builds the adversarial shape on purpose (Bob's
*only* trail note for Alice names Dave, not Alice), offers to Alice first while
the marked copy is still queued, and checks she's offered the *routed* copy —
not an unrouted store copy that would make the test pass for the wrong reason.
**The control:** Carol (the sender — neither the recipient nor on the trail) must
*still* be refused the message. If she ever gets it, the fix has quietly turned
targeted routing back into shouting, and the privacy/bandwidth win is gone. I
also verified the test **fails without the fix and passes with it** — so it is
actually measuring the fix.

---

### 3 — The test harness was lying about preconditions (O209)

**You told me mid-session:** make sure a test's result actually means what it
claims, because "0 failures" can mean "0 of the checks ever ran." That is exactly
what was happening here, and chasing it produced #2.

**The plain version.** Most simulator tests do "wait until X has happened, *then*
check the thing I care about." The shared helper that does the waiting
(`awaitUntil`) gave up silently after 3 seconds and let the test carry on
regardless. So if the setup never happened, the test didn't fail — it just
quietly tested a different, usually empty, situation.

Two real costs, both measured rather than assumed:

- **One test was proving nothing at all.** `SelfEchoTest` is supposed to check
  that your phone ignores its own presence beacon when a neighbour bounces it
  back. It waited for the beacon to be *saved to the neighbour's database* — but
  those beacons are deliberately never saved (they're throwaway chatter). So the
  wait could **never** succeed. It timed out every single run, then asserted "my
  own ID didn't show up" — which is trivially true when the beacon never went
  anywhere. **It would have passed with the actual safety guard deleted.**
- **An unmodified checkout failed 2 runs out of 4.** I verified that on pristine
  code with my changes stashed, so it wasn't something I broke: a different
  assertion failed each time, none of them the property under test.

**The fix.** The wait now **fails the test** when it times out and says which
wait expired. I raised the budget 3 s → 10 s (a wait that succeeds costs only as
long as the effect really takes, so a generous ceiling is nearly free). The
helper has its own test, including "does it actually fail on a never-true
condition." `SelfEchoTest` now waits on a signal that *can* fire, and asserts two
positive controls first: the neighbour really *processed* the beacon, and really
*does offer it back*. Without those, "nothing bad showed up" can't tell a working
guard from a message that never moved. I wrote both rules into
`docs/SIMULATOR_TESTING.md`.

**Worth knowing:** the full suite is green now, but this container has 4 cores and
some real-wire tests (`LanMeshHarnessTest`, `LanLivenessValidationTest`) are
timing-sensitive — I measured them failing in isolation on pristine code too.
I did not "fix" those by loosening anything; if they reappear I'll report them
rather than paper over them.


---

### 4 — The file-transfer defences had zero tests (O159, part)

**The plain version.** When someone sends you a file, it arrives as many small
numbered chunks plus a "manifest" describing them. Your phone has five defences
against a malicious sender abusing that:

| Defence | What it stops |
|---|---|
| Manifest sanity check | A sender claiming "this file has 2 billion chunks" to make your phone commit to 2 billion chunks of work |
| 8-transfers-per-sender cap | One sender opening unlimited simultaneous transfers to fill your storage |
| Chunk-number bounds | A chunk claiming to be number 99 of a 2-chunk file |
| Chunk-size bounds | A chunk 10× bigger than the manifest said chunks would be |
| Per-chunk fingerprint check | A bad sender slipping in corrupt bytes for one chunk, which would otherwise only be caught after the *whole* file arrived and was paid for |

**All five had no tests whatsoever.** (The storage leak you had fixed earlier,
O158, was the proof that gap was live — nothing would have caught it.)

**What I did.** Five tests, driven through the real simulated radio — the hostile
manifests and chunks are genuinely signed and arrive over the wire exactly as an
attacker's would, rather than being handed to the code directly.

**The part you specifically asked for.** "Nothing got stored" is the correct
outcome of a working defence *and* of a test whose message never arrived. So every
case has a twin that differs **only** in the field being tested and must be
accepted. The best example: the 8-transfer cap's control is that a **different**
sender still gets through — otherwise the "defence" would be a weapon, letting one
sender lock everyone else out of your phone.

Then I **deleted each of the five defences in turn and confirmed the matching
test fails.** That check immediately earned its keep: my first manifest-sanity
test **passed with the defence deleted** — it was checking too early, before the
bad manifest had been processed. I'd have shipped a test that proved nothing.
Fixed by sending a *good* manifest after the bad one and waiting for the good one
to register, which proves the bad one was already dealt with.

**What's still missing (honest):** the *sending* side has no tests, and the
"chunk didn't arrive, ask again" retry timer still can't be tested — it uses real
60-second waits with no way to fast-forward. That needs a small refactor I did
**not** do rather than fake. It's recorded in the backlog.

---

### 5 — Three more tests that couldn't tell success from nothing-happened (O210)

Same theme as #3. Clearing the last flaky tests turned up three, and one of them
matters beyond tests:

1. **The 1-in-3 flake** — a routing test waited for the wrong signal, then
   inspected the wrong copy of the message. Now waits for the specific thing it
   inspects. 8 runs out of 8 green afterwards.
2. **A test that got *easier* the slower the machine was.** It checked "this
   message must NOT reach B" after a fixed 25-second wait. On a loaded machine,
   nothing reaches anywhere in 25 seconds — so it passed for free. That's the
   dangerous direction: a **silent false pass**. It now waits for proof the
   network could carry a message in that window (an unrestricted broadcast must
   get through on the same path), so "it didn't arrive" means something.
3. **⚠️ A working feature looked broken.** The real-radio test for the liveness
   routing feature you shipped default-on was failing, and it looked like the
   feature didn't work. It isn't the feature. This container has 4 cores and the
   test spins up six real network stacks; sometimes the bypass route **never
   connects at all**, and when that happens the message sits still — which from
   the test's point of view is identical to "routing failed."

   I instrumented it for 2 minutes per run: in the bad run **nothing at all**
   moved across the bypass; in good runs the message arrived in ~30 seconds,
   **including when I forced the liveness timer fully stale as a control.** So
   your feature does work over a real wire. The test now proves the network
   formed *before* measuring routing on it, and says so plainly if it didn't.

   **Why you should care:** that test is the evidence the handoff cites for
   flipping liveness routing default-on. It was capable of failing for reasons
   that have nothing to do with the thing it certifies — in either direction.

I also raised the real-radio patience from 120 s to 240 s. To be clear, that's
patience, not a weakened check: a four-hop delivery at a 10-second radio cadence
can't physically finish in much under 40 s, the old ceiling was only ~2.4× that,
and a wait that succeeds costs only as long as the delivery actually takes.

