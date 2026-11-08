# Handoff — simulation campaign for delivery/privacy design (2026-08-01 → 08-02)

Away-mode session (user: "simulation session, validating concepts in the todo").
Everything is pure-JVM sim + real-desktop `:node` validation — no phone needed.
Build with `JAVA_HOME=/home/user/jdk17` (Temurin 17; `java` is NOT on PATH — use
`/home/user/jdk17/bin/java` for direct `node.jar` runs). All results are logged in
**`docs/SIM_HARNESS_RESULTS.md`** (read its top "Cross-cutting synthesis" first).

## The simulation harness now exists in three fidelity tiers (all in `:simulator` tests)
1. **Abstract model** — pure store-and-forward math; hundreds of nodes; models
   *unbuilt* features (O193 eviction/ACK/retry). Fast, deterministic.
2. **`MeshHarness`** (`MeshHarnessSweepTest`) — real `GossipEngine`/relay/dedup/
   crypto over in-process `SimTransport`. Sliders: peer-cap, duty-cycle, link-loss,
   dead-relay. Real engine, *simulated wire* (async → ±few% run-to-run).
3. **`LanMeshHarness`** (`LanMeshHarnessTest`) — same engine over a REAL
   `LanTransport` on loopback (real TCP + wire), mDNS off, topology via
   `onPeerLocated`. Proved: multi-hop relay, duty-cycle carry, dead-relay
   routing/blocking, byzantine-flood survival. Slow (~10s/gossip round) → spot-checks.
   - Real `:node` **binary** also validated (broadcast + multi-hop line relay);
     process cap ~24–32 (CPU-bound, mDNS full-mesh).

**Prod changes (all default-preserving, core suites green):** `LanTransport` gained
`Config.enableMdns` + a `start(port=)` param + `onPeerLocated` made public; `:node`
gained `--no-mdns`/`--lan-port`/`--peer` for deterministic multi-process topologies.

## Design conclusions the sims produced (numbers in `docs/SIM_HARNESS_RESULTS.md`)
- **Node duty-cycle is THE dominant delivery limiter** (loss + dead-relays the mesh
  shrugs off). It's the O202 risk, O194's eclipse weak point, and O23's pressure
  source. → **O202 (duty-cycle hardening) is the top priority.**
- **O193**: recommended eviction bundle = **spray-k + on-ack + TTL**; on-relay
  (forward-then-forget) is a *scale-amplified* delivery trap (56%→13% as N grows).
  The stressor "95%" is a sender-escape artifact; ~20–30% sender uptime → 99–100%.
- **O202 hybrid custody** (custody-by-default, widen to epidemic on a delivery
  deadline) = full delivery at custody-level storage in the common case; deadline
  should be **adaptive** to duty.
- **O23**: storage eviction must be **delivery/ACK-aware** (drop delivered first),
  not FIFO (+19pp under pressure).
- **O194**: **trust-weighted peering** (prefer known contacts) defeats sybil eclipse
  at any density — confirms the recorded decision.
- **O102** p≈0.5 halves broadcast airtime; **O195** mix-batching ~1/k unlinkability
  at latency∝k/rate; **O197** onion routing is partial/contact-degree-gated;
  **O76** 6 buckets may over-pad (71% overhead vs 27% at 12).
- **Highest-leverage next protocol addition: a lightweight per-message
  delivery-ACK.** It unlocks on-ack eviction (O193), delivery-aware eviction (O23),
  sender-retry-until-quit, and hybrid-custody escalation. **It does not exist yet**
  (O40 `MESSAGE_DELETE` is the closest substrate). This is the thread to pull next.

## Infrastructure & housekeeping
- **Away-mode Stop hook** (durable, cross-instance): `~/.claude/settings.json` Stop
  hook → `~/.claude/away-mode-stop-hook.sh`, gated on the flag file
  `~/.claude/away-mode.flag`. Present = blocks Stop (keeps working); absent = normal.
  **User directive: never end away mode yourself — only the user deletes the flag**
  (memory `away-mode`). Currently OFF (user turned it off at session end).
- **friend→known** rename logged in `docs/UI_BACKLOG.md` — UI strings cheap; the
  `isFriend` DB column needs the next schema bump. Not done (a ctrl-F).

## Open / next
- **O38 prekey-FS-window sim** — I wrote one, then caught a modeling error (it
  conflated rotation *cadence* with *validity*; the FS window is **validity**-
  governed, and the real tradeoff is validity = FS-exposure vs offline-sender
  fallback). Deleted the flawed file uncommitted. Redo with validity as the swept
  param + an offline-sender fallback dimension. It's in the todo.
- **O202** is now well-characterized in sim → ready to *build* (hybrid custody +
  delivery-aware eviction). The delivery-ACK is the shared prerequisite.
