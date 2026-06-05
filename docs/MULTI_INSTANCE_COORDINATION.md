# Multi-instance coordination

When two (or more) Claude Code sessions are running concurrently against
this repo, they cannot talk to each other directly. They share only one
thing: git. This file is the protocol they use to stay out of each
other's way.

## The hazard

Two sessions on the same branch can:

1. Both edit `CLAUDE.md` (especially row counts in the backlog header or
   row text in the same backlog row) → merge conflict on push.
2. Both claim the same backlog row (O-number) → duplicated work, or worse,
   one session's commit silently overwrites the other's.
3. Both create a new test file at the same path → file-add conflict.
4. Push out of order so a later session pushes stale row counts in
   `CLAUDE.md` over a fresh closure → bookkeeping desyncs from reality.

## The protocol

Each instance, before starting work:

1. `git fetch origin <branch> && git log --oneline HEAD..origin/<branch>`
   — see what the other instance just pushed.
2. `git pull --ff-only` if there are new commits — never rebase or merge
   while the other instance might be mid-edit; fast-forward only.
3. **Pick an open backlog row that isn't already claimed in an unpushed
   commit message you can see.** When in doubt, pick a row the other
   instance is least likely to grab (the user can hint via the chat).

While working:

4. **Don't edit `CLAUDE.md` row counts mid-task.** Save the row-count
   update for the same commit that closes the row. That way "I touched
   CLAUDE.md" only happens once per closure, not on a planning pass.
5. **Commit small and push frequently.** Other instances see your work
   only after you push. A 600-line commit that closes four rows means
   the other instance is working blind for the full duration; four
   150-line commits keep the shared state fresher.
6. **Re-fetch before every push.** If the other instance pushed in
   between your last fetch and your push, pull and resolve before
   pushing. Fast-forward only.

When closing a row:

7. The row-close commit owns the `CLAUDE.md` edits for that row. Move
   the row from "Open items" to "Completed gaps" as a `G##`; update the
   header counts (`X PART · Y DECISION · Z TODO`); reference the new
   `G##` from anywhere the old `O##` was cited.
8. **Commit message lists the row number explicitly.** Format:
   `O67 → PART: keyword filter substrate (model + matcher + 12 tests)`.
   The other instance can grep `git log --oneline` for the row number
   to confirm it's been claimed.

When two sessions hit each other anyway:

9. **Pull --ff-only first; if it refuses, fetch and inspect.** Usually
   one of two things has happened:
    - A clean unrelated commit you can merge straight in.
    - A conflict on `CLAUDE.md`. Resolve by hand — the row text and
      counts have to reflect the union of what both sessions closed.
10. **Don't force-push.** Never. Even to fix your own mistake — pile a
    correction commit on top instead, so the other session sees the
    history it expected.

## When stricter isolation is worth the cost

The protocol above is informal and relies on each session being
disciplined. If the work surface stops being trivially disjoint
(e.g. both sessions need to refactor the same file), put each instance
on a separate branch and merge them yourself when ready. The branch
isolation eliminates 100% of the race but trades away the "single source
of truth" benefit — two divergent branches drift faster than two
disciplined sessions on one branch, so default to one-branch with this
protocol and reach for separate branches only when you have a concrete
reason.

## What this protocol does NOT solve

- **Token usage.** Two sessions burn roughly 2× the tokens of one. Not
  always — sometimes parallelism is worth it for genuinely independent
  work — but it's never free.
- **Architectural disagreement.** If two instances independently decide
  on conflicting designs for adjacent rows, the commits will land
  cleanly but the codebase will be incoherent. The user is the only one
  who can catch this; review both PRs / commit batches before merging.
- **Stale context.** If session A and session B both started from the
  same root context (this `CLAUDE.md` snapshot), and session A makes a
  change to the architecture that session B should know about, session B
  won't see it until the user tells them or until session B pulls and
  rereads `CLAUDE.md`. No automated way around this short of polling on
  every turn, which is wasteful.

## Quick checklist before each commit

- [ ] `git fetch && git log --oneline HEAD..origin/<branch>` — clean?
- [ ] If new commits exist, `git pull --ff-only` and re-read the new
      commit messages to confirm no row conflicts with yours.
- [ ] Row close: `CLAUDE.md` row moved to "Completed gaps", header counts
      updated, all `O##` references rewritten to `G##`.
- [ ] Commit message includes row number prefix (`O67 → PART:`).
- [ ] Push immediately; don't accumulate.
