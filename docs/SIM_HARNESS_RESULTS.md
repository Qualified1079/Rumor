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
- [ ] **Byzantine/malicious modes** — drop-but-claim, replay, flood — needs a misbehaving transport wrapper.
- [ ] **Link loss / latency / jitter / bandwidth** — real TCP has none injected; needs a conditioning socket wrapper.
- [ ] **Delivery-rate driver** — many DMs → aggregate %, if we accept the wall-clock cost (else keep on `MeshHarness`).
- [ ] **Scale** — more nodes within the in-process socket/thread ceiling (~10–30).

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
