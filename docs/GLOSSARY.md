# Rumor glossary

Project-specific and easily-confused terms. Borrowed terms (Sybil, gossip, DTN)
are listed only where Rumor uses them in a particular way. When you coin a new
term in code or docs, add it here.

## Sync & summary

- **RBSR** — Range-Based Set Reconciliation. Rumor's name/abbreviation for the
  Negentropy algorithm (Meijers 2023). Two peers converge on their set
  difference in `O(d log n)` round-trips instead of shipping a full summary.
  Elsewhere in the literature it's "Negentropy" / NIP-77; "RBSR" is ours.
- **Bloom summary** — the fallback set-summary below the RBSR size gate: a Bloom
  filter of known message ids, probed by the peer for membership.
- **NeighborDigest** — a compact Bloom of our known-set sent early in a session
  purely so each side can compute an **overlap fraction** afterward.
- **knownCount** — HELLO field carrying our set size, so both peers compute the
  same adaptive gate (`shouldUseRbsr`) and never split modes.
- **Overlap fraction** — 0–1, what share of our offer the peer already had. Feeds
  batch shaping and diversity selection. Stored per-peer in **NeighborStore**.
- **Reseed** (O92) — on mesh start, rehydrate the volatile scheduler + dedup
  filter from the durable store, so a restarted phone still offers what it holds.
- **Offerable** — durable-store query for offer-eligible messages (BROADCAST/DIRECT
  with hops left), freshest-first; the backfill source for an exchange offer.

## Routing

- **Breadcrumb** — a reverse-path hint: "messages *to* X recently arrived *via*
  peer P," recorded per inbound message. The Tier-1 routing substrate (O29).
- **intendedPeers** — hard offer filter on a relayed DM: once breadcrumbs name
  candidates, that copy is only offered to those peers, not flooded to all.
- **routedHops vs floodedHops** — the split TTL (O32). A breadcrumb-routed hop
  increments `routedHops`; an untargeted hop decrements the flood budget. Hard
  ceiling `MAX_TOTAL_HOPS` over their sum.
- **Managed flood** — the baseline: relay to everyone, dedup suppresses repeats.
  Routing (breadcrumbs) narrows this; flood is always the fallback.
- **Next-hop routing** (not source routing) — a DM carries no path list; each node
  decides the next hop from its own local view (O59). Structural anti-leak choice.
- **Persistent link** (O2 / G19) — a held-open Wi-Fi Direct group that re-gossips
  every ~10s without re-running discovery/negotiation. Automatic for nearby peers.
- **Priority peer** — a contact flagged for a mutual persistent link. Now mostly
  superseded by automatic persistence; the bookkeeping flag remains.
- **Anchor** — a node that carries routing weight (STATIC/FREE mode, plugged in).
  The **backbone** is the connected set of anchor links (O98).
- **Covering set / degree-bounded backbone** — O98's smart-persistence output: a
  connected overlay where everyone is reachable multi-hop but no device exceeds
  its Wi-Fi Direct group-size cap.

## Trust & bridging

- **TrustLevel** — `VERIFIED` (native, full Ed25519), `BRIDGE_VOUCHED` (foreign
  content in a Rumor-signed envelope, relayable — O17), `BRIDGED` (foreign, never
  re-relayed).
- **BRIDGE_UNSIGNED** — sentinel in the signature field for bridge-injected
  traffic; honored **only** for `LOCAL_BRIDGE` source, never over peer transport.
- **DmEnvelope** — pluggable per-network DM crypto (O5a). **Architecture B**
  (passthrough) = bridge sees only ciphertext; **Architecture A** =
  decrypt-and-re-encrypt, bridge is an endpoint that can read.
- **Vouched** — a bridge signs "I received this from network N" (delivery), *not*
  "this content is authentic." Distinct trust level so users don't conflate them.

## Traffic & scheduling

- **TrafficClass** — derived, never on the wire: `INFRASTRUCTURE` / `REALTIME`
  (≤16 KB), `TRANSFER_SETUP` (≤256 KB), `BULK` (oversized falls here).
- **DRR** — Deficit Round Robin, the scheduler's fairness discipline: each sender/
  class drains a byte quantum per round so no one monopolizes bandwidth.
- **RelayBatcher** — holds relayed messages a random 100–500 ms before queueing,
  for timing-correlation resistance. Local composes bypass it.

## Modes & presence

- **UserMode** — `MOBILE` (moving, low anchor weight, default) / `STATIC` (still,
  eligible anchor) / `FREE` (dedicate resources, subsumes STATIC). O57.
- **ModeProfile** — the single per-mode config bundle (scan duty cycle, session
  length, crumb decay, etc.) everything consumes instead of scattered mode checks
  (O62). *Not yet built — gates all mode-branching code.*
- **StaticMode** — the current shipped on/off toggle that raises cache/scan
  aggressiveness; the precursor to full UserMode.
- **Self-presence beacon** — a signed "I am running as X mode" message (O30). An
  **entry pulse** on going STATIC/FREE and a symmetric **exit pulse** (mode=MOBILE)
  on leaving, so peers demote a ghost anchor immediately.

## Identity

- **userId** — `SHA-256(publicKey)` hex. The only identity; cryptographically
  bound to the key, so it can't be forged or claimed.
- **TOFU pin** — Trust On First Use: first pubkey seen for an id is pinned; a later
  mismatch is a key-swap attempt and is dropped.
- **deviceId** — opaque per-install random id, shown only in Settings. Never an
  identity or security anchor.
- **Identity rotation** (O41) — migrate to a new key with a **continuity
  signature** by the old key proving authorization; contacts rebind under the new id.
- **Ed25519→X25519** — the RFC-7748 conversion (O91) letting one identity key both
  sign (Ed25519) and do DM key-agreement (X25519).

## Metrics & infra

- **CanaryMetrics** — in-app health telemetry (queue depth, sig failures, exchange
  RTT). Unrelated to the CLAUDE.md canary below.
- **DuplicateFilter / dedup** — the seen-id set that suppresses re-ingest and
  re-flood; the load-bearing anti-spam primitive.
- **Canary (docs)** — the "By Order Of The High Magnate" sign-off line in CLAUDE.md;
  if it stops appearing, context has scrolled past the top of that file.

## Process / backlog vocabulary

- **O-numbers / G-numbers** — backlog IDs in CLAUDE.md: `O#` = open item, `G#` =
  completed gap. Tombstones (`O99`) kept so findings aren't rediscovered.
- **Status tags** — `[PART]`, `[DECISION]`, `[TODO/CODE|SIM|UI|EMU|HW]`, `[STRUCT]`.
- **Assay** — a deliberate build-vs-adopt review round (e.g. the prebuilt-vs-
  handrolled policy of 2026-07-13).
