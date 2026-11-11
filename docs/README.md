# docs/ — what each file is for, and when to open it

One line each so nobody has to open a file to find out it was the wrong one. Start at `handoff.md` §1 (repo root), not here.

## Open before you touch the matching area

| File | Open when |
|---|---|
| `backlog/INDEX.md` | choosing or looking up work (`python scripts/backlog.py show O123` for one row) |
| `SIMULATOR_TESTING.md` | writing any `:simulator` or `:core` scenario test |
| `RENAMED_FIELDS_NEVER_REUSE.md` | touching a wire format, domain tag, `_ext` key or `MessageType` name |
| `wire-format.md` | reading or changing what goes on the wire |
| `BRIDGING.md` | any Meshtastic/MeshCore bridge work, especially encrypted bridging (O5) |
| `O79_PROTOCOL_SPEC.md`, `ROOMS_THREAT_MODEL.md` | Rooms work |
| `MULTI_INSTANCE_COORDINATION.md` | another instance may be working on the same branch |
| `BUG_HISTORY.md` | touching `:app` tests, Room entities, or a file that exists in two modules |
| `GLOSSARY.md` | a coined project term is unclear |
| `DECISIONS.md` | a cross-ref says "per O12" (also the prebuilt-vs-handrolled policy) |

## Reference, consult on demand

| File | Contents |
|---|---|
| `UI_BACKLOG.md` | bodies of the UI rows folded into O143 |
| `DESIGN_ANALYSIS.md` | full reasoning behind speculative/future rows |
| `SIM_HARNESS_RESULTS.md` | running log of simulation and real-harness measurements |
| `SECURITY_RESEARCH.md`, `SYBIL_RESEARCH.md`, `MODERATION_AUTHORITY_RESEARCH.md`, `RESEARCH_NOTES.md` | prior-art research behind security, sybil-resistance and moderation rows |
| `CRYPTO_PRIMITIVES_AUDIT.md` | O84 audit of crypto primitives |
| `PROJECT_KEYS.md` | policy and log for project signing keys |
| `FDROID_BUILD.md` | F-Droid build recipe |
| `IOS_*.md` | iOS port plan, Swift bridge spec, App Store checklist (decision O63) |

## History, not instructions

| File | Contents |
|---|---|
| `handoff/` | one file per session, newest listed first in the root `handoff.md` |
| `handoff/eli5/` | plain-English session reports written for the user |
| `backlog/done/`, `backlog/archive/` | completed rows; tombstone and shipped-substrate records |
| `ARCHIMEDES_MERGE_CATALOG.md` | keep/drop decisions from the 2026-07-16 branch merge |
| `SCOPE_AUDIT.md` | scope audit ratified 2026-07-19 |
| `NODE_KICKOFF.md` | `:node` kickoff plan, done 2026-07-22 |

Older files in the history group refer to `docs/OPEN_BACKLOG.md`, `docs/COMPLETED_GAPS.md` and backlog tables in `CLAUDE.md`. Those were split into `backlog/` on 2026-11-10; a row number means the same thing in both.
