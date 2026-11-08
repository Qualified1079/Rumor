# Handoff — DIRECT_ACK + routing redesign/validation + audit cleanup (2026-08-02 → 08-03)

Away-mode session (user stepped in and out; corrected me twice — both corrections
were right). Pure `:core`/`:simulator`, no phone. Everything below is committed +
pushed to `main` and green EXCEPT the one `@Ignore`d WIP test called out first.

## ~~TOP PRIORITY: finish the mid-path-dropout routing test~~ — DONE (`0bed5ce`)

Directive removed 2026-10-10: the `@Ignore` came off, the harness bug was the
`dmId` lookup (it used `knownMessages().first()` instead of `composeDirect`'s
return), and the result is written up in the 2026-08-03 entry above and in
`docs/SIM_HARNESS_RESULTS.md`. **The one durable lesson from it is kept here
because a later session will hit it again:** `SimTransport` CANNOT test routing
— it offers the raw repo snapshot, ignoring the `hopsToLive` decrement, the
`offerable` filter, and (critically) it leaks `@Transient` `intendedPeers`
across the in-process "wire". Drive routing tests through the REAL
`GossipEngine.messagesForExchange` + `deliverExchange`, and round-trip every
offered message through `WireJson.encode/decode` to strip transients exactly as
a socket would. `SmartRoutingReachTest` / `SmartRoutingDropoutTest` show the
pattern.

## What shipped this session (all committed + pushed to main, suites green)
- **O202 — on-receipt end-to-end DM delivery ACK (`DIRECT_ACK`).** New MessageType;
  recipient auto-ACKs on receipt; sender learns via `GossipEngine.deliveryReceipts`.
  Relay-never-ACKs, no ack-loop, signed acked-id, inbox-suppressed, once-per-DM.
  Later hardened: DIRECT_ACK now backfills from `offerable` + routes with the
  breadcrumb re-derivation like a DIRECT (was scheduler-copy-only = fragile).
  `DirectAckDeliveryTest`.
- **O160 — routed/flooded TTL split, redesigned after user correction.** First fix
  (routed = 2× flood budget) was a flat ration; user: routed hops should ride the
  breadcrumb FREE and only spend flood budget where the trail ends. Final: routed
  hops advance a `routedHops` odometer bounded only by `MAX_ROUTED_HOPS=64` (a
  stale-trail cap; dedup is the real loop-breaker); flood budget spent only on the
  no-crumb fallback. Removed the coupled `MAX_TOTAL_HOPS` ceiling.
- **O204 (filed, sim-DONE) — `hopsToLive` does NOT bound reach.** The durable
  store-backfill (`offerable`, `hopsToLive>0` only) re-offers stored copies at
  their as-received TTL; the per-hop decrement lives only on the ephemeral relay
  copy. So flood + routed both reach the whole dedup-bounded component. **Routing's
  real, validated benefit is TARGETING, not reach:** `SmartRoutingReachTest` — a
  routed DM touched 10/26 nodes (backbone only) vs flood 26/26. (This is what the
  dropout test above is stress-testing.)
- **O171 / O176 / O177 / O158** — audit-residue fixes: lock `PersistenceCoordinator.recent`;
  cap `BreadcrumbCache.snapshot` (2048); wire the 4th orphaned prune
  (`MeshViewTracker.pruneStale`) into `pruneMaintenance` + guard test; release chunk
  BLOBs after a completed transfer (happy-path storage leak) + regression guard
  (first real coverage toward O159).

## Still parked on USER DECISION (do not build without a call — O203)
- "Delivered ✓✓" UI + delivery-state persistence → needs a **Room schema bump**.
- Relay-side on-ack eviction (O193) → needs privacy/deliverability **policy
  defaults** (sim-recommended bundle: spray-k + on-ack + TTL, k≈4, X≈24h, default-OFF).
- Recommendation recorded in `docs/OPEN_BACKLOG.md` O203: do the schema bump once,
  wire the ✓✓ UI + safe default-on sender-side offer-suppression together; hold
  relay-eviction until defaults are set.

## Where the prose lives
- Plain-English ELI5 of the whole session (for the user): `docs/SESSION_ELI5_2026-08-02.md`.
- Sim numbers + methodology: `docs/SIM_HARNESS_RESULTS.md` (O160/O204 section).
- Backlog rows: O202/O203/O204 (+ indexed in CLAUDE.md Tier 4); O158/O160/O171/O176/O177 marked DONE.
