# Simulation & harness results — running log

Append new harness findings here (newest first within a section). Two tiers:

- **Abstract model** (`RelayEvictionModelTest`) — pure set-algebra store-and-forward;
  hundreds of nodes, fast, can model *unbuilt* features (O193 eviction/ACK/retry).
  Design-space trends only — NOT real code.
- **Real-engine harness** (`MeshHarness` / `MeshHarnessSweepTest`) — every node is a
  `SimNode` running the actual `GossipEngine`/relay/dedup/breadcrumb/X25519+AES-GCM.
  DM delivery measured by ciphertext landing in the recipient's real store.
  **Fidelity caveat:** real engine, *simulated wire* (`SimTransport`, in-process) —
  does NOT exercise `LanTransport` (TCP/mDNS/session framing).
- **Real-wire harness** (`LanMeshHarness` / `LanMeshHarnessTest`) — same real
  engine but over a REAL `LanTransport` on loopback (real TCP + `GossipSession`
  wire), mDNS off for deterministic topology, wired via `onPeerLocated`. Top of
  the ladder. Slow (~10 s/gossip round → tens of seconds for multi-hop), small N.
  Proven 2026-08-01: a DM relays 0→1→2 over real sockets (a middle node carries
  ciphertext it isn't the recipient of). This is where the "other stuff"
  (peer-cap, duty-cycle, malicious modes) will be layered next.

Fidelity ladder: abstract math → real engine / sim wire → real engine / real
`LanTransport` (loopback) → real `:node` processes (~32 cap, CPU-bound; measured
2026-08-01).

---

## LAN harness (`LanMeshHarness`) — sliders to add (add each as we go)

The real-wire tier is slow (~10 s/gossip round) so it does **fidelity spot-checks**
(does the real transport/relay actually do X), not statistical sweeps — those stay
on the fast `MeshHarness` (sim-wire) tier. Checklist:

- [x] **Arbitrary topology / peer-cap** — edges are a `start()` param (multi-hop line proven).
- [x] **Node duty-cycle** (online/offline via transport stop/re-start + re-wire) — `setOnline()`; proven 2026-08-01: a DM sent to an OFFLINE recipient is carried by the relay over real TCP and delivered when the recipient returns (the O202/O55 store-and-forward property on real code).
- [x] **Dead/hostile relay** — `hostile` set; a wired node that absorbs but offers nothing onward. Proven 2026-08-01: DM routes AROUND a hostile relay via an honest alternate path (diamond), and is BLOCKED when the hostile relay is the only path (line, negative control).
- [x] **Byzantine — flood** — `flood()`; proven 2026-08-01: a legit DM survives a 250-broadcast junk flood (past the 200 offer cap) over real wire (starvation resistance). Remaining byzantine variants (replay, drop-but-claim) need a misbehaving *session* wrapper — follow-up.
- [defer] **Link loss / latency / jitter / bandwidth** — real TCP injects none; needs a socket-conditioning seam in `LanTransport` (prod change, not worth an autonomous edit). The fast `MeshHarness` tier already carries these and showed re-offer *defeats* independent per-message loss (0–50%→100%).
- [by-design on MeshHarness] **Delivery-rate driver** — statistical sweeps stay on the fast sim-wire tier; the real-wire tier does fidelity spot-checks (10 s/round makes many-trial sweeps impractical).
- [characterized] **Scale** — real-transport scale is the `:node` process cap (~32, CPU-bound; measured 2026-08-01); the in-process LAN harness is socket/thread-bound (dozens). No separate slow multi-hop scale test.

---

## Real-engine harness — connectivity sliders (2026-08-01)

Base (harsh, to make knobs visible): 24 nodes, 12 rounds, 48 DMs, 3 seeds/cell.
A well-connected 45-round mesh delivers **~100% on every slider** — the real
relay's store + per-round re-offer IS a built-in retry, so normal conditions
already hit the 99%+ target.

| Slider | Result | Reading |
|--------|--------|---------|
| peer cap | 1→65%, ≥2→100% | sharp connectivity threshold; degree-2 suffices |
| **node duty cycle** | 100/70%→100%, 50%→89%, **30%→31%** | **the dominant delivery risk** |
| link loss | 0–50%→100% | re-offer defeats independent per-message loss (0.5¹²≈0) |
| dead/hostile relays | 40%→91% | mesh routes around non-relaying nodes gracefully |

**Load-bearing conclusion → HARDENING PRIORITY: node duty cycle.** Delivery is
robust to loss and dead relays but collapses when nodes are mostly offline
(30% online → 31% delivered). Under the O55 threat model (weeks of duty-cycled
survival on intermittent power, <2%/hr scan budget) this is the delivery risk
that matters. Mitigation directions (unexplored): longer/priority message
persistence, rendezvous-aware re-offer scheduling, opportunistic burst sync on
contact, anchor/always-on relay nodes (O54/O104). Tracked as **O202**.

## Abstract model — O193 relay-ephemeral DM caching (2026-08-01)

See `docs/SECURITY_RESEARCH.md` §O193 for the full writeup. Headline: recommended
eviction bundle = **spray-k + on-ack + TTL**; on-relay is a scale-amplified trap;
the stressor's "95%" is a sender-escape artifact, and ~20–30% sender uptime → 99–100%.

## Real `:node` process caps (2026-08-01)

8-core / 16 GB desktop: ~90–120 MB RSS per node, **CPU-bound** (mDNS full-mesh,
O(N²)). Comfortable to ~16–24 nodes; cliff at 40 (load 0.4 → 26). RAM would allow
~60–70. Real-time propagation (no downclock knob). Small-N/high-fidelity tier.

## O202 experiment — custody vs epidemic vs HYBRID under duty-cycle (2026-08-01)

`CustodyVsEpidemicTest` (abstract). Custody = store R's DMs only on R's known
contacts (who meet R); epidemic = everyone carries; **hybrid = custody by default,
widen to epidemic if undelivered by a deadline (round 30 of 120)**. Model refined
2026-08-01 to **stop accumulating storage on delivery** (an ACK halts carrying,
per O193) — so storage = carrier-rounds UNTIL delivery, the honest cost (this
changed the absolute numbers vs the first custody run; trends unchanged).

| duty | epidemic (del% \| storage) | custody | **hybrid** |
|------|----------------------------|---------|------------|
| 100% | 100% \| 34  | 100% \| 5  | 100% \| **5** |
| 50%  | 100% \| 90  | 100% \| 15 | 100% \| **26** |
| 30%  | 100% \| 222 | 95% \| 33  | 100% \| **159** |
| 15%  | 81% \| 677  | 30% \| 41  | 63% \| **486** |

**Conclusion → O202 design: HYBRID custody (validated).** Custody alone is cheap
but collapses under scarcity (15%: 30% vs epidemic's 81%). Hybrid is the best of
both in the common case (30–100% duty: **full delivery at custody-level storage** —
it never widens because delivery beats the deadline). At extreme scarcity it's a
genuine middle ground: **doubles** custody's delivery (30%→63%) at less storage
than epidemic, though it can't fully match epidemic's 81% — earlier escalation
would help (a tunable). Pairs with presence-triggered flush (O30) + priority
persistence. Ship custody-by-default with a delivery-deadline widen.

## Revalidation (2026-08-01)

Reran O193 abstract (`RelayEvictionModelTest`) — main table + accuracy
decomposition **byte-identical** to the original run (deterministic). MeshHarness
real-engine sweeps **trend-stable** (duty-cycle dominant 30%→~33%, loss defeated,
dead-relay graceful); ±few% run-to-run from real-engine async ingest timing (the
real-engine tier is approximate, not deterministic — expected).

## Real `:node` binary — multi-hop relay (2026-08-01)

Beyond the earlier full-mesh broadcast check: with 3 REAL `node.jar` processes in
a deterministic line (`--no-mdns --lan-port` + `--peer`; node0—node1—node2, node0
and node2 NOT directly wired), a broadcast POSTed to node0 landed in node1's store
(round 1) then RELAYED to node2 (round 2) over real TCP. Confirms multi-hop
store-and-forward on the genuine desktop runtime (MeshRuntime + LanTransport), not
just SimNode. Enabled by new `:node` flags `--no-mdns`, `--lan-port`, `--peer` and
a `LanTransport.start(port=)` param (defaults preserve behavior).

### O202 — hybrid escalation-deadline sweep (2026-08-01)

When should hybrid widen custody→epidemic? Sweep of the deadline (round):

| duty | widen@5 | widen@15 | widen@30 | widen@60 | never (pure custody) |
|------|---------|----------|----------|----------|----------------------|
| 30%  | 100% \| 190 | 100% \| 206 | 100% \| 159 | 100% \| 92 | 95% \| 33 |
| 15%  | 73% \| 733 | 72% \| 624 | 63% \| 486 | 45% \| 265 | 30% \| 41 |

Tension: earlier widening recovers low-duty delivery (15%: 30%→73%) but wastes
storage at higher duty (30%: widen@5 costs 190 vs widen@60's 92, both 100%). →
**an ADAPTIVE deadline keyed on observed duty/delivery-difficulty beats any fixed
one**; a fixed ~15–30 is a reasonable static compromise. Feeds the O202 build.
