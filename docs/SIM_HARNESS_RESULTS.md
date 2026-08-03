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

## Cross-cutting synthesis (2026-08-01)

Across every delivery experiment below, **one variable dominates: node duty-cycle**
(how often nodes are online). It is the delivery limiter (O202), the weak point of
eclipse defense (O194), and the pressure source for storage eviction (O23). Loss
and dead-relays the mesh shrugs off (re-offer + reroute); *being offline* is what
hurts. → **duty-cycle hardening is the top priority (O202).**

The validated design stack that emerges:
1. **Delivery engine** — store-and-forward + per-round re-offer already IS a retry;
   normal conditions hit ~100% on the real engine.
2. **Under scarcity (low duty):**
   - **Custody** (keep R's DMs on R's known contacts, not everyone) = 6.6–12× less
     storage at equal delivery; **widen to epidemic if undelivered by a deadline**
     (hybrid, ideally adaptive) — O202.
   - **Delivery/ACK-aware eviction** (drop delivered DMs first), never FIFO = +19pp
     under storage pressure — O23.
   - **Sender retry-until-ACK** pushes delivery 2%→89% but plateaus; needs light
     carrier persistence to close the last ~10%.
3. **Trust-weighted peering** (prefer known contacts) defeats sybil eclipse at any
   sybil density — O194/O135.
4. **Airtime** — probabilistic/neighbour-aware forwarding halves broadcast airtime
   at ~full coverage — O102.
5. **Privacy costs latency, honestly (O27)** — mix-batching ~1/k unlinkability at
   latency ∝ k/rate; onion routing is partial (contact-degree-gated).

**The unifying enabler is per-message delivery-knowledge (an ACK).** It powers
on-ack eviction (O193), delivery-aware storage eviction (O23), sender-retry-until-
quit, and hybrid-custody escalation. It does NOT exist yet (O40 `MESSAGE_DELETE` is
the closest substrate) — **building a lightweight delivery-ACK unlocks the whole
stack.** That's the single highest-leverage protocol addition these sims point to.

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

## O23 experiment — bounded-store eviction: delivery-aware vs FIFO (2026-08-01)

`StorageEvictionTest` (abstract). Under a bounded per-node store + pressure
(sparse mesh, 60% duty, 90 DMs, cap sweep), which eviction policy delivers more?

| cap | FIFO | delivery-aware |
|-----|------|----------------|
| 3   | 77%  | 88%  |
| 5   | 81%  | **100%** |
| 8   | 85%  | 100% |
| 15  | 90%  | 100% |
| ∞   | 100% | 100% |

**Conclusion → O23 design: eviction MUST be delivery/ACK-aware, not FIFO.**
Shedding already-delivered DMs first keeps undelivered ones alive longer — at
cap=5, delivery-aware holds 100% while FIFO drops to 81% (+19pp for the same
budget). Converges only at unbounded cap. Ties O193 (ACK is the local "delivered"
signal this needs) and O202 (storage efficiency under scarcity). Cheap connectivity
(fast delivery) hides this — pressure needs slow delivery to appear.

## O197 experiment — limited-onion-routing feasibility (2026-08-01)

`OnionFeasibilityTest` (abstract, pure graph). Onion routing through known-contact
relays needs S↔R connected in the contact graph within a small hop budget (layers
are per-hop). % of random pairs reachable through contacts only:

| avg contact degree | ≤3 hops | ≤5 hops | any path | mean hops |
|--------------------|---------|---------|----------|-----------|
| 2  | 9%  | 32%  | 100% | 6.3 |
| 3  | 24% | 90%  | 100% | 4.2 |
| 4  | 46% | 100% | 100% | 3.5 |
| 6  | 86% | 100% | 100% | 2.8 |
| 10 | 100%| 100% | 100% | 2.4 |

**Conclusion → O197 is a PARTIAL, opportunistic feature.** A giant component means
"any path" is ~always there, but a *short* contact-path (the only kind onion
routing can afford) is degree-gated: viable within ≤3 hops only at contact degree
≥6. In the sparse contact graphs a privacy-conscious mesh likely has (few vetted
contacts), ≤3-hop onion serves 9–24% of pairs — a minority; the rest fall back to
normal gossip. Ship it as "use onion when a short contact-path exists, else gossip,"
and document that coverage scales with the user's contact density.

## O195 experiment — traffic-analysis resistance (mixing) (2026-08-01)

`TrafficAnalysisTest` (abstract). An observer links a relay's inbound↔outbound by
timing; lower link% = better unlinkability. Cost = added latency (arrival-intervals).

| policy | observer link accuracy | added latency |
|--------|------------------------|---------------|
| immediate | 100% | 0.0 |
| delay D=2 | 18% | 1.0 |
| delay D=8 | 10% | 4.0 |
| mix k=4  | 15% | 1.5 |
| mix k=16 | 5%  | 7.5 |

**Conclusion → O195 dial.** Immediate forward is a perfect timing fingerprint;
both mitigations break it. Mix batching gives the strongest unlinkability per
message (~1/k) but latency scales with `k / arrival-rate` — cheap on a busy relay,
brutal on a quiet one → **adaptive batch size scaling with traffic** is the lever.
None of it is free (O27/O55 latency budget). **Honest caveat:** the modeled
observer is a simple greedy timing-matcher, so these link% are an *upper bound* on
unlinkability (a rate/intersection-attack adversary does better); only the ordering
mix > delay > immediate is robust. Matches the O27 "no Tor-parity anonymity" stance.

## O194 experiment — eclipse resistance via trust-weighted peering (2026-08-01)

`EclipseResistanceTest` (abstract). Eclipse = all of R's connection slots filled
by non-relaying sybils. % of trials R stays reachable (≥1 honest relay neighbour):

| sybil fraction | random peering | trust-weighted |
|----------------|----------------|----------------|
| 50% | 98% | 100% |
| 80% | 72% | 100% |
| 90% | 44% | 100% |
| 95% | 23% | 100% |
| 99% | 5%  | 100% |

**Conclusion → confirms O194 (trust-weighted view, not radio fingerprinting).**
Random peering is eclipsed as sybil density → 1. Trust-weighted (fill R's slots
from known honest contacts first) stays reachable whenever ≥1 contact is online —
its residual risk is **contact duty-cycle** ((1−duty)^contacts, ~0.4% with 8
contacts @ 50%), NOT sybil count. Sybils become irrelevant once you prefer vetted
contacts. Note the weak point is again duty-cycle → loops back to O202. Ties
O135/O136 (friend/known allowlist is the same primitive).

## O102 experiment — forward delegation: coverage vs airtime (2026-08-01)

`ForwardDelegationTest` (abstract). Probabilistic forwarding: each receiver
rebroadcasts with prob p. Dense mesh (N=400, degree 8):

| forward p | coverage | airtime (tx/N) |
|-----------|----------|----------------|
| 1.00 | 100% | 1.00 |
| 0.70 | 100% | 0.70 |
| 0.50 | 99%  | 0.50 |
| 0.30 | 92%  | 0.28 |
| 0.15 | 36%  | 0.05 |

**Conclusion → O102: p≈0.5 halves broadcast airtime at ~full coverage** in a dense
mesh; coverage degrades below ~0.3 and collapses below ~0.2 (percolation). A
neighbour-aware scheme (adapt p to local density, or suppress after hearing the
message from k neighbours) beats a blind coin-flip — same coverage, less airtime,
because fixed-p wastes forwards in dense spots and under-covers sparse ones. Real
battery/airtime win (O33/O55 budget).

## Sender retry-budget experiment (2026-08-01)

`SenderRetryBudgetTest` (abstract). With **forward-then-forget carriers** (no
persistence), the sender's re-injection is the only delivery engine. Delivery vs
the sender's give-up budget (rounds it keeps re-offering an un-ACKed DM), duty=0.6:

| retry budget (rounds) | 1 | 5 | 15 | 30 | 60 | 120 | 160 |
|-----------------------|---|---|----|----|----|-----|-----|
| delivery% | 2 | 10 | 29 | 50 | 70 | 86 | 89 |

**Conclusion.** Sender retry pushes delivery up steeply (2%→89%) — answers the
user's O193 "how much do retries buy" — but **plateaus ~89%**: even maximal retry
can't overcome duty-cycle connectivity gaps when carriers forget. So the last ~10%
needs *some* carrier persistence (custody/TTL); retry alone isn't enough. on-ACK
makes a long budget cheap (stop re-offering once delivered). Synthesizes with the
O193 on-relay trap + O202 custody: retry + light persistence together, not either
alone.

## O76 experiment — size-bucket padding: bandwidth vs anonymity (2026-08-01)

`PaddingAnonymityTest` (abstract). 8000 msgs (70% short/25% med/5% long). Pad each
to a geometric size bucket; same padded size = size-anonymity set.

| buckets | overhead | min anonymity set |
|---------|----------|-------------------|
| 1  | 1337% | 8000 |
| 6 (O76) | 71% | 304 |
| 12 | 27% | 67 |
| 24 | 13% | 30 |
| none | 0% | 1 (25.6% of msgs size-identifiable) |

**Conclusion.** Padding clearly helps (no-pad leaves 25.6% of messages in a tiny
size-set). But **O76's 6 buckets costs 71% bandwidth overhead** — steep under the
O55 bandwidth-scarce model — while **12 buckets gives 27% overhead at still-strong
anonymity (≥67-msg sets)**. Suggest revisiting toward ~12 buckets, or making bucket
count **traffic-adaptive** (min-anon-set scales with volume, so finer buckets are
safe on busy meshes, coarser needed on quiet ones). Compression runs before padding
(shifts sizes smaller) but doesn't remove the need to pad.

## O202/O58 — on-receipt end-to-end delivery ACK (DIRECT_ACK) shipped (2026-08-02)

New protocol substrate: `MessageType.DIRECT_ACK`. When a node receives a DIRECT
DM addressed to **it** (recipientId == self, sig verified), it auto-composes a
tiny INFRASTRUCTURE-class ACK carrying the acked DM's messageId and routes it
back to the original sender exactly like a DIRECT (breadcrumb next-hop, dropped
when it reaches the sender). The sender surfaces the acked id on
`GossipEngine.deliveryReceipts` — an **end-to-end** "delivered" signal, distinct
from the pre-existing `deliveryEvents` (session-layer peer-hop `Ack`, which only
means "a direct peer accepted the frame", not "it reached the person").

Security/loop properties (all enforced in `processIncoming`):
- Gated on `recipientId == self` so a **relay never ACKs** a DM it's only
  forwarding (discrimination control in `DirectAckDeliveryTest`).
- `DIRECT_ACK` never itself triggers an ACK → no acknowledgement loop.
- Acked id rides `payload.content`, which (with `recipientId`) is in the signed
  transcript — a relay can neither redirect the ACK nor forge which DM it
  confirms.
- Bridged traffic skipped (synthetic senderId has no mesh return path).
- Suppressed from the inbox (control signal, not a user-visible message).
- Dedup means a DM re-delivered by multiple relay paths ACKs exactly once
  (`processIncoming` returns early on `!isNew`).

Validated: `simulator/.../engine/DirectAckDeliveryTest.kt` (real engine + real
`SimTransport`): (1) A→B DM ⇒ B auto-ACKs ⇒ ACK returns to A ⇒ A raises a
delivery receipt for the DM id; (2) relay-not-recipient does not ACK; (3)
assertThrows teeth guard.

**Why this is the enabling substrate for O193/O202:** custody/retry both need a
delivery-confirmation signal to know when to *stop*. Sender-side retry (O193)
can now cancel its budget on receipt; presence-flush (O202) can clear a DM from
custody once acked instead of holding it for the full TTL. Next: sender-side
retry/cancel loop keyed on `deliveryReceipts`, and app-side "delivered" UI +
delivery-state persistence (Room column — needs a schema bump, flagged for the
user).

## O160/O204 — is breadcrumb routing really better? (2026-08-03)

`SmartRoutingReachTest` (simulator). **Key methodology note:** `SimTransport`
cannot answer this — it offers a peer the raw `messageRepo.snapshot()`, so it
neither decrements `hopsToLive`, honours `intendedPeers`, nor applies the
`offerable` `hopsToLive>0` filter. So this test drives propagation through the
**real** offer path (`GossipEngine.messagesForExchange` + `deliverExchange`) AND
round-trips every offered message through `WireJson` (serialize→deserialize) so
`@Transient` fields — above all `intendedPeers`, the sender's LOCAL routing
decision — are stripped exactly as a socket wire would strip them. (Passing the
live object, as `SimTransport` does, leaks `intendedPeers` across the hop and
wedges routed delivery after 2 hops — a harness-fidelity trap, not a protocol
bug; cost me an hour, documented so it doesn't recur.)

**Finding 1 — routing does NOT extend reach; the hop budget doesn't bound reach
at all (O204).** On a plain 24-node line with no crumbs, a *flooded* DM reached
index 23 — far past the 15-hop `hopsToLive` budget. Cause: `MessageStore.ingest`
stores a message at its *as-received* `hopsToLive`, and the durable store-backfill
(`offerable`, re-offered every exchange by `messagesForExchange`) re-offers that
stored copy as long as `hopsToLive>0`. The per-hop decrement lives only on the
one-shot scheduler relay copy, which the backfill overrides. So reach = the whole
dedup-bounded connected component, for both flood and routed. Good for delivery
robustness; a concern for O27/O58 (a DM/broadcast floods every node) — filed
**O204**.

**Finding 2 — routing's real win is TARGETING (measured, clean).** Topology: a
10-node backbone line A…B with 2 leaf nodes hung off each of the 8 interior
backbone nodes (26 nodes total). Both arms deliver to B:

| arm | delivered to B | nodes touched |
|-----|----------------|---------------|
| routed (crumb trail A→B along the backbone) | yes | **10** (backbone only — all 16 leaves untouched) |
| flood (no crumbs) | yes | **26** (whole component) |

`messagesForExchange` offers a relayed DM only to breadcrumb candidates for its
recipient (flood fallback when no crumb). So a DM with a crumb trail travels a
narrow path; without one it sprays everyone. **2.6× fewer nodes see the ciphertext
for the same delivery** — the honest benefit of routing is bandwidth + privacy
(O27/O58), NOT reach. The backbone front advanced exactly 1 hop/round, confirming
clean directional forwarding.

**Note on further fidelity:** the sim already runs the real `GossipEngine` + real
`WireJson` framing + real offer path; the only remaining gap vs a live multi-`:node`
run (LanMeshHarness over real TCP) is socket/`GossipSession` framing + timing,
which don't touch the routing/targeting logic (all in `:core`, run verbatim). A
multi-node `:node` cross-check of the 10-backbone topology is a possible deeper
confirmation but is not expected to change the result.
