# Handoff — the two overnight audit dumps (2026-07-23 and 2026-07-24) — SCRUBBED 2026-10-10

**~1060 lines of raw audit findings removed because every one of them was
already triaged into a numbered backlog row.** Keeping both copies meant a
reader had to diff prose against rows to tell which findings were still live —
and the rows are the version that gets updated.

- **2026-07-24 session (43 findings)** → **O156–O188**, bodies in
  `docs/OPEN_BACKLOG.md`; 7 pure doc-staleness findings folded into **O122**.
  Rows still carry their original `audit #NN` tags, so a row traces back to its
  finding number.
- **2026-07-23 session** → the O127–O145 range plus the Tier-2 rows.
- Both sessions' "suggested next moves" lists were already marked superseded in
  their own text, and the work they pointed at has since shipped (O144, O156,
  O157, O162, O166, O170, O172, O180) or moved to a row.

**Preserved from the 2026-07-24 pass, because "we checked this and it was fine"
is not recorded anywhere else and is exactly what a later session would redo:**
`docs/ROOMS_THREAT_MODEL.md`, `docs/O79_PROTOCOL_SPEC.md`, `docs/GLOSSARY.md`,
`docs/BRIDGING.md` (its `DmEnvelope`/`DmEnvelopeRegistry` interface listing was
verified to match the code exactly), `docs/SIMULATOR_TESTING.md`,
`docs/UI_BACKLOG.md`, `docs/DESIGN_ANALYSIS.md`, `docs/SCOPE_AUDIT.md`,
`docs/PROJECT_KEYS.md`, `docs/ARCHIMEDES_MERGE_CATALOG.md`,
`docs/NODE_KICKOFF.md` — all internally consistent with the code at that time.
The rest of `docs/wire-format.md` (framing, HELLO/HELLO_PROOF, RBSR frame
format, domain-tag table, `MessageType` enum, sub-payload specs) matched
`MessageStore.signableBytes`, `GossipPacket.kt`'s challenge-bytes helpers and
`Rbsr.kt` precisely. **Caveat: that verification predates the 2026-10-10
`rumor-msg-v3` bump** — `wire-format.md` and the domain-tag table were updated
for v3, but anything else in that list is only verified as of 2026-07-24.

Full text of both dumps is in git history if a finding's original wording is
ever needed (`git log -- handoff.md`).
