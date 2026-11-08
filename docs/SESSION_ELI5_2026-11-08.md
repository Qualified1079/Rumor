# Session ELI5 — 2026-11-08 (cloud-only fixes while the phone setup is pending)

Plain-English after-action report. **Newest work at the bottom.** Same rules as
`docs/SESSION_ELI5_2026-11-06.md`: one commit per row; `:core`/`:simulator`/`:node`
built and tested in the cloud container (via a scratchpad Maven mirror, since Maven
Central rate-limits this IP); `:app` not buildable here, so any `:app` line is
hand-reviewed only; every fix teeth-checked (fix removed → the new test fails).

Housekeeping: the merged `away/2026-10-11-overnight` branch could not be deleted
from here (the git proxy returns HTTP 403 for branch deletion). It is fully merged,
so nothing is lost; delete it from GitHub's branch page if you want it gone.

---

### 1 — A "repeat N times" message could repeat forever (O240, half → G69)

**The plain version.** You can schedule a message to repeat, say "every hour, 3
times". The counter worked like this: after each send, subtract one; at zero,
stop. But "repeat forever" was stored as −1, and the code said "if the count is
below zero, it's forever". So a schedule whose count was already 0 (the default
value) went 0 → −1 → *forever*. Any negative number also meant forever.

**What changed.** Only exactly −1 means forever. Any other count that runs out
ends the schedule after its last send. Asking to schedule "repeat 0 times" is
now refused up front.

**How I proved it.** A new test puts schedules with counts 0 and −5 straight into
storage, skipping the new up-front check so that check isn't what makes the test
pass. Each sends once and is then gone. A second test checks "repeat 0 times" is
refused. The existing "repeat forever" test still passes, so forever still works.
Teeth: with the old arithmetic back, the new test fails.

**Worth knowing.** Nothing in the app schedules repeating messages yet (there's no
button), so this was a trap waiting for the first person to build one.

**Not done.** If the phone crashes between sending a scheduled message and
updating the schedule, it sends again after restart; and a scheduled DM that
can't be sent (contact unknown) is quietly thrown away. Both stay in O240.

---

### 2 — A misbehaving phone could skip steps in the sync conversation (O242 → G70)

**The plain version.** When two phones sync, they follow a fixed script: say
hello, prove who you are, swap a summary of what you have, ask for what you're
missing, send messages, say "that's all, N messages", then "got these" and "who's
online". Your phone was too forgiving. If the other phone skipped a step, yours
read the *next* step's message as the skipped one and carried on confused. If the
other phone stopped sending messages without saying "that's all", yours accepted
whatever it had. If it said "that's all, 2" after sending 1, yours didn't notice.

**What changed.** Every step must now be the step it should be; anything else
ends the session (the next sync round starts clean). "That's all, N" must match
the number actually received. Also, your phone only offers the faster sync method
(RBSR) when it can actually run it. And when a sync is too big to finish in one
go, your phone no longer stops talking halfway while the other side is mid-
sentence. It keeps taking turns, just stops collecting, so neither side gets
stuck.

**How I proved it.** A new test plays a scripted fake phone that does a real
handshake and then sends exactly the wrong thing each time: skips a step, stops
without "that's all", miscounts, swaps the last two steps. Every case now ends the
session. A control case where the fake phone follows the script perfectly still
completes and delivers its message. Teeth: run against the old code, all five
wrong-thing tests fail. (My first versions of two of them passed on the old code
too, because the old code happened to trip later on those inputs. I rewrote them
so they really test the fix.)

**What I could NOT prove.** The "too big to finish" change only kicks in above
50,000 messages in one sync, so it's reviewed, not tested.

**Full-suite note.** In the full parallel run, `TransferAssemblerHardeningTest`
timed out once waiting 10 s for a transfer to register; it passes 5/5 alone, so
it's load, like the known LAN flake. If it keeps happening under the full suite it
deserves the same treatment §5 of the 11-06 report gave the watchdog test.
