# Scope audit — ratified 2026-07-19

Line-by-line pass over the backlog against the project framing (see memory
`rumor-priority-framing`): Rumor is **designed for SHTF/grid-down but must exist and
persist as a legitimate, law- and convention-abiding app in the normal world** —
distributed like seeds so ordinary people already have it when the grid goes down.
"Prepper project" does NOT license ignoring current law, app-store rules, or moderation
norms.

**Rubric per row:** (1) does it serve the SHTF mesh + threat model? (2) does it serve
real-world legitimacy (law / app-store / moderation)? (3) is it protocol/reliability
substrate (current priority) or UI/ecosystem (real but deferred — UI is ultra-low
priority right now). Dispositions: **KEEP-CORE** (critical path now), **KEEP-DEFER**
(in-scope, not now), **CUT** (removed), **TIDY** (resolved, move to COMPLETED_GAPS).

## Ratified rulings (user, 2026-07-19)

**IN — corrected from my initial "cut" (these are NOT scope creep):**
- **O81 image classifiers** — ultra-cheap quality-of-life AND app stores increasingly
  require moderation/filtering for social apps; serves both criteria at once.
- **O78 blocklist attestation** — blocking is essential and often needs to be done in
  bulk via shared blocklists; unattested blocklists become blind echo-chamber makers, so
  signing / merging / diffing / legitimacy-confirmation is real infrastructure, not creep.
- **Plugin platform (O15/O24/O26/O49/O50, O23/O25/O88/O123)** — necessary. When app
  updates are infrequent or never, the community must be able to ship mods / plugins /
  datapacks for what it needs. This is core to the "persist independently" model, not a
  deferrable marketplace nicety.

**OUT — removed:**
- **O86 payments** — a DEX / external plugin does this; not Rumor's job, not a companion
  we build. (Row simplified to a hard exclusion in `docs/DECISIONS.md`.)
- **O75 MCU relay firmware** — dropped (row removed from `CLAUDE.md`).

**Everything else: keep or tidy.**

## Full disposition

**KEEP-CORE — on the critical path now**
- Phone-P2P + mesh substrate: O98, O93, O107, O94, O124, O30, O31, O43, O100 (the
  content-addressed chunk-identity wire piece; defer the swarm machinery)
- Crypto / threat model: O20, O44, O38, O39, O53, O48, O115, O112, O108, O109, O117,
  O118, O21 (pinning half)
- Reliability / longevity (survives being stashed then relied on for months): O116, O120,
  O23, O121, O77 (defer-until-big-box), O33 (battery numbers everything keys off)
- Bandwidth on constrained links: O76 (compression matters far more over LoRa than WiFi)
- Community comms + real-world-legit content controls: O79 (rooms), O81 (classifiers),
  O78 (attested blocklists)
- Plugin platform (community extensibility): O15, O24, O23, O25, O26, O49, O50, O123
- Identity longevity: O45 (mnemonic backup), O69 (reframe as duress/panic-wipe; drop the
  App-Store-5.1.1 framing)
- Cheap hygiene: O122 (doc-drift), and the small correctness pins in O121

**KEEP-DEFER — in-scope, not now (mostly UI/ecosystem; UI is ultra-low priority)**
O80, O89, O67, O90, O88, O68, O97, O110, O111, O113, O70, O71, O22, O46, O1, O8, O6, O7,
O72 (Nostr — folds under the transport-SPI thinking), O103 (maps — on-thesis but
plugin/UI), O102 (run the cheap sim, then likely close as a decision)

**KEEP — plugin-tier, LOW priority ("streamline the inevitable")**
Radio/bridges (O54, O4, O5, O17, O119, O118, O48, O8, O6, O7), sites (O101), maps (O103).
Philosophy (user, 2026-07-19): these streamline a thing people will do anyway — the way
people already build "sites" on Telegram/Signal/Instagram (hacky) or hand-carry data
between radio meshes (cludgy). Rumor makes them clean, as **plugins**, but they are NOT the
point of the project and never outrank the core. `:node`/anchor-host (O106) is [STRUCT],
kept, but also not ahead of phone-P2P.

**KEEP as-is — conventions/framing + recorded decisions**
Conventions: O27, O36, O51, O55, O56, O58, O59, O60, O65. Decisions (in
`docs/DECISIONS.md`): O11, O12, O63, O82, O104, O125.

**CUT** — O86 (→ exclusion decision), O75 (removed).

**TIDY — resolved/closed rows still in open tables (move to COMPLETED_GAPS)**
O62 (resolved — BUT its per-mode value table is a LIVE reference; move the resolved marker,
KEEP the value table findable), O57 (closed → G39), O66 (informed the now-closed O42).

## Re-tier question — RESOLVED: NO (user, 2026-07-19)

**Radio stays low priority / Tier 5, plugin-tier — NOT first-class.** Phone-to-phone device
mesh is priority #1, full stop. The radio additions exist with respect to the reality of
long-term comms needs, but they are plugins that make an expected thing easier rather than
kludgy — same philosophy as sites: people already build sites on Telegram/Signal/Instagram
(hacky), and Rumor just streamlines it; it is not the whole point. So the bridge/radio
cluster keeps its Tier 5 home; O107 (transport SPI) stays where it is. The §12 research
informs *how* the radio plugin should be built (LoRa-over-ISM, compact framing, digest
lane), not *how high* it ranks.

## Applied

O75 removed; O86 narrowed to a hard exclusion; re-tier rejected (radio stays Tier 5). Still
queued: TIDY (O62/O57/O66 → COMPLETED_GAPS, keeping O62's live per-mode value table findable).
