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
