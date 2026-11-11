# Sybil-resistance research for Rumor

Notes on three prior-art bodies of work — **SybilGuard/SybilLimit**, **Secure
Scuttlebutt (SSB)**, **Advogato max-flow / PGP Web of Trust** — plus a few
adjacent designs that came up while reading (Freenet WoT, Ostra, SybilInfer,
GateKeeper, DTN/MANET-specific work). Framed toward Rumor's threat model
(O27/O51/O55/O60 floor: minted Ed25519 identities are free, wall-time is
untrustworthy post-collapse, the mesh IS the distribution channel) and toward
the concrete rows O135/O136 already file for the design.

Bottom line up front: the literature converges hard on **"cannot prevent
sybils, must bound their damage"**, which is exactly Rumor's O135 framing.
The specific formal result that backs the "block-the-signer severs the
subtree" intuition is SybilLimit's `O(log n) accepted sybils PER attack
edge`; the specific working deployment closest to Rumor is SSB's
`hops`-limited replication (a shipping degrees-of-separation slider); and
the specific mechanism worth stealing from Freenet WoT is the
puzzle-solve-to-obtain-an-address introduction gate, though its economics
don't fit Rumor's power envelope.

---

## 1. SybilGuard / SybilLimit (Yu et al., 2006 / 2008)

### The formal result

Both protocols assume a **social graph** where an edge is a human-established
trust relationship. A malicious user can mint arbitrarily many identities
cheaply but must persuade an honest user to form each **attack edge** — an
edge from an honest node to any node in the sybil region. The graph
therefore has a small `cut` between the honest region and the sybil region
regardless of how large the sybil region is.

- **SybilGuard (2006):** bounds the number of accepted sybils to
  `O(√n · log n)` per attack edge, where `n` is the honest population.
- **SybilLimit (2008):** tightens this to **`O(log n)` accepted sybils per
  attack edge** — a `Θ(√n)` improvement, ~200× fewer accepted sybils on a
  million-node graph in the authors' experiments.

The critical framing: the bound is **per attack edge, NOT per minted
identity**. This is the theoretical grounding for Rumor's "sybils are
harmless when trust admission is graph-based; the attacker's cost is
persuading a real user to friend one of them, not spinning up keys."

### The assumption that bites

Both proofs assume the honest sub-graph is **fast-mixing** — random walks
reach the stationary distribution in `O(log n)` steps. This holds
empirically for large web-scale social graphs (Facebook, LiveJournal)
studied in the papers.

**Rumor's honest graph is unlikely to be fast-mixing.** A sparse
geographically-clustered mesh (a neighborhood, a rural county, a building
after infrastructure collapse) is closer to a grid than to a small-world
graph. Alvisi et al.'s 2013 SoK ("The Evolution of Sybil Defense via Social
Networks", Oakland) is the standard critique here: on real deployed graphs
these bounds are directional but not guaranteed, and the false-positive
rate on legitimately-sparsely-connected honest nodes (isolated newcomers,
elderly, people at the geographic edge of the mesh) is a real cost.

### Actionable for Rumor

1. **The `per attack edge` framing goes into user-facing copy** — replace
   any "sybil-proof" or "spam-resistant" claim with "each real person who
   friends a bad actor admits ~log(n) fake identities into your view;
   blocking that person severs the entire subtree." This is honest,
   defensible, and matches the O27/O60 honest-docs posture.
2. **Do NOT use SybilLimit/SybilGuard as-is** — the random-walk machinery
   is heavy, requires a stable membership view Rumor doesn't have, and the
   fast-mixing assumption fails in the physical mesh case. Steal the
   *bound*, not the algorithm.
3. **The bound tells you what to instrument.** If you ever wire O135(4)
   vouching, count `attack edges accepted per session` as a
   telemetry-shaped local metric — it's the quantity the theory bounds and
   the quantity a user cares about ("how many sketchy vouches have I
   accepted this month").

### Papers

- Yu, Kaminsky, Gibbons, Flaxman — *SybilGuard: defending against sybil
  attacks via social networks* (SIGCOMM 2006).
- Yu, Gibbons, Kaminsky, Xiao — *SybilLimit: A Near-Optimal Social Network
  Defense against Sybil Attacks* (S&P 2008 / TON 2010,
  <https://ieeexplore.ieee.org/document/4531141/>).
- Alvisi, Clement, Epasto, Lattanzi, Panconesi — *SoK: The Evolution of
  Sybil Defense via Social Networks* (S&P 2013,
  <https://oaklandsok.github.io/papers/alvisi2013.pdf>). **Read this before
  the primary papers** — it's the compressed synthesis and the honest
  post-mortem on where these algorithms actually work.
- Mohaisen, Hopper, Kim — *Keep your friends close: Incorporating trust
  into social network-based Sybil defenses* (INFOCOM 2011). Practical
  mitigations for the fast-mixing failure.

---

## 2. Secure Scuttlebutt (SSB)

The closest **shipping** system to Rumor. Signed append-only per-identity
logs, gossip replication, offline-first, no servers. The pieces relevant
to sybil-resistance:

### The `hops` setting — a working degrees-of-separation slider

- Client replicates: (a) feeds the local identity follows directly, (b)
  feeds followed by those, transitively, up to **N hops** — this is a
  user-set integer.
- The `ssb-friends` module defaults to **2 hops** (friends-of-friends);
  older docs and the design-challenge write-up cite "up to 3 hops by
  default." The default has drifted; the design invariant is "user-tunable,
  small integer."
- **Blocks propagate: a follow past a block is *not* honored.** If B has
  blocked C, A (who follows B) will not replicate C via B, even if some
  other 2-hop path exists via B. This is the "block-the-signer severs the
  subtree" primitive in shipping form.
- Blocks are themselves signed and part of the replicated feed, so a
  transitive-block signal is auditable.

This is the **direct reference implementation** for Rumor's O135(4) vouching
slider. The wire shape ("follows" and "blocks" as signed feed entries with
transitive semantics) maps almost 1:1 onto Rumor's signed-broadcast + Room
subscription model — an SSB `follow` is a signed statement "I include this
key in my replication frontier"; a Rumor equivalent is a signed
`FriendVouch(userId, publicKey)` message with the same append-only,
audit-able semantics.

### What SSB does NOT claim

The SSB handbook's own "design challenge: sybil attacks" page is candid:
the mechanism is **"humans are pretty good at detecting frauds"**. There is
no cryptographic sybil defense — the defense is that spammers aren't in
your friend graph, and if they get in, you (or your friends) will notice
and block. This matches Rumor's O27/O51/O60 honest-docs posture exactly.

### Known limitations to inherit as caveats

- **Newcomer bootstrap is hard** — a fresh key with zero incoming follows
  is invisible to the network. SSB has "pub" servers that
  broadcast-follow new users; Rumor's equivalent is Rooms and the physical
  in-range broadcast tier (nothing to bootstrap through, but nothing to
  attack either).
- **The follow-graph itself is a metadata leak.** SSB replicates
  follow-lists in cleartext (they're signed feed entries). O27/O51 already
  says Rumor cannot beat this floor while having a relay mesh; the SSB
  experience is the closest confirmation that this leak is tolerable in
  practice.
- **Whale accounts (SSB "pubs")** de-facto centralize trust; in a mesh with
  no pubs, the bootstrap problem is worse but the centralization risk is
  gone. Note as a Rumor advantage.

### Actionable for Rumor

1. **O135(4)'s "hops" slider is not novel work — it is porting SSB's
   `ssb-friends` semantics into Rumor's message model.** Copy the
   invariants: transitive follow, transitive block dominates transitive
   follow, small default (2 hops), user-tunable.
2. The **KnownSenders inbox filter (O135(1))** is the SSB `hops=1` case as
   a UI toggle. Ship it independently and first — it doesn't need the
   vouch primitive.
3. **Signed follow/block feeds as broadcast content** — don't invent a new
   wire type; a `FriendVouch` / `BlockPublish` is a signed broadcast that
   the local client interprets. This aligns with O36 (append-only signed
   broadcasts are the primitive) and O78 (block reasons already have this
   shape).
4. **Read the actual `ssb-friends` implementation before writing O135(4).**
   The transitive-block-dominates-transitive-follow rule has subtle edge
   cases (loops, self-block, block-then-unblock) that are already solved
   there.

### Sources

- Kermarrec et al. — *Gossiping with Append-Only Logs in Secure-Scuttlebutt*
  (DICG 2020, <https://dicg2020.github.io/papers/kermarrec.pdf>). The
  academic write-up.
- SSB handbook design-challenge page:
  <https://github.com/ssbc/handbook.scuttlebutt.nz/blob/master/stories/design-challenge-sybil-attacks.md>.
- `ssb-friends` module (the actual hops+block-transitive code) — read
  before implementing Rumor's equivalent.

---

## 3. Advogato max-flow / PGP Web of Trust

### Advogato's contribution

Raph Levien's thesis (*Attack-Resistant Trust Metrics*,
<https://levien.com/thesis/thesis.pdf>) formalized what PGP had gestured at:
trust as **network flow from a trusted seed** through a signed edge graph,
capacity-limited so a bounded set of attack edges admits a bounded set of
bad nodes. Ford-Fulkerson max-flow computes the certified set; extensible
to multiple trust levels by re-running with different capacities.

The key structural property Rumor cares about: **the number of accepted
bad nodes scales linearly with the attacker's cost** (measured as the
capacity of certificates from valid accounts into the bad region), NOT
catastrophically. This is the formal cousin of SybilLimit's `O(log n)
per attack edge` — different model, same shape of guarantee.

### The known break

Jesse Ruderman (2005,
<https://www.squarefree.com/2005/05/26/advogato/>) showed Advogato's
security proof bounds trust by the confused nodes' **post-attack**
capacity rather than pre-attack. The attack: confuse one high-capacity node
into trusting many capacity-1 nodes, and now you have many
"pre-approved" high-capacity confused nodes. Attacker's gain scales as the
**square** of attack cost.

**Rumor lesson:** if you build a vouch-capacity system, the capacity of a
node must be **fixed at admission** (or a function of its own
depth-from-seed, computed independently of its outgoing vouches), never
inherited from who vouched for it. Otherwise you rebuild the Advogato
bug.

### PGP Web of Trust

The classical deployment. Signed key-to-key certifications, no central
authority, user-configurable trust levels (marginally trusted, fully
trusted, ultimately trusted) with local thresholds ("I accept a key if it
has N marginally-trusted signatures or 1 fully-trusted signature").

**What PGP got right that Rumor should copy:**
- Trust is **local and subjective** — no global "trust score", just what
  YOUR key ring says.
- Signatures are **attributable and revocable** by the signer, not by
  quorum.
- The mechanism is inspectable (`gpg --list-sigs`) — a user can trace WHY
  a key is trusted.

**What PGP got wrong that Rumor should skip:**
- **Key-signing parties as the primary onboarding path.** Never scaled.
  Rumor's equivalent (in-person QR handshake) has the same problem: only
  works for people who already meet in person. This is why O135's
  transitive vouching matters — it's the only path that scales trust
  admission beyond people you'll shake hands with.
- **Trust semantics conflated with identity binding.** In PGP, "I signed
  this key" means both "I attest this key belongs to this person" AND "I
  trust this person's certifications." Rumor should keep these separate:
  `VouchIdentity(userId, name)` ≠ `VouchAsIntroducer(userId, hops=N)`.
  Otherwise you leak trust semantics through the identity primitive.

### Actionable for Rumor

1. **Fix vouch capacity at admission, not by inheritance.** Explicit
   inoculation against the Advogato break. Cheap: a node's capacity is
   `max(0, seed_capacity - depth_from_seed)`, computed on the receiver's
   view, never adopted from the wire.
2. **Separate "this is Bob's key" from "Bob's vouches count in my
   graph."** Two different signed statements, two different UI actions
   (the O21 fingerprint-verify vs the O136 friend action).
3. **Local trust root only — never a global aggregate.** No project-wide
   "trust score", no attempt to compute a network-wide sybil probability.
   Every user's view is computed from their own seed set. This is also
   what makes the system incoercible: there is no central number an
   attacker can attack.

### Sources

- Levien — *Attack-Resistant Trust Metrics* (thesis,
  <https://levien.com/thesis/thesis.pdf>).
- Ruderman critique: <https://www.squarefree.com/2005/05/26/advogato/>.
- Levien's HOWTO: <http://www.levien.com/free/tmetric-HOWTO.html>.

---

## 4. Adjacent designs worth knowing

### Freenet Web of Trust (WoT plugin)

- Trust values `−100..+100` between identity pairs; score `≥ 0` → download
  content, `< 0` → drop.
- **Puzzle-based introduction:** a new identity B discovers A's puzzles,
  solves them locally, and the *solution* is used to derive the Freenet
  address at which B publishes. Only A (puzzle-creator) knows the target
  address, so A implicitly attests "B exists" by pointing others at the
  address. Clever — the puzzle is not a proof-of-work in the CPU-cost
  sense; it's a **channel** the introducer controls.
- Fits well against Rumor's O55 low-power envelope (no per-message PoW),
  but the introduction primitive requires two-way asynchronous
  communication that's awkward on a mesh. **Worth a design read; not an
  obvious port.**
- Source: <https://github.com/hyphanet/plugin-WebOfTrust/blob/next/README.md>.

### Ostra (Mislove et al., NSDI 2008)

- Tolerance-based spam mitigation: every friend edge carries a **credit
  balance**. Marking a message as spam debits the credit along the path
  from sender to receiver. When credit hits zero on any edge, that path
  can no longer be used for delivery.
- Doesn't identify sybils; **prices** their reach. The attacker's edge
  into the honest region is a finite resource that spam consumes.
- Interesting fit for Rumor's O135(2) aggregate-caps thinking — a
  per-edge budget is finer-grained than a global bucket and localizes the
  damage. **Worth prototyping** as an alternative to (or refinement of)
  the LRU-collapse pool in O134.

### SybilInfer (Danezis & Mittal, NDSS 2009)

- Bayesian: assigns each node a probability of being sybil via
  Metropolis-Hastings sampling over random walks.
- Low false-negative rate; **high compute cost.** Not compatible with
  Rumor's power envelope, and centralized-computation-shaped in ways that
  don't fit a peer-to-peer node. Skip as an active mechanism; useful as a
  benchmark if you ever build a simulator scenario testing detection
  quality.

### GateKeeper (Tran et al., 2011)

- Ticket-distribution scheme from a trusted seed; extends SybilLimit's
  ideas.
- **Assumes social graphs are random expanders** — an even stronger
  assumption than fast-mixing, and one Alvisi et al. showed empirically
  fails on real graphs. Cited here mainly so future contributors don't
  re-propose it without knowing the deployment record.

### DTN / MANET-specific work

- Mason Test (Gilbert et al., <https://arxiv.org/pdf/1403.5871>): uses
  radio-layer signals (RSSI, packet timing) to test whether two claimed
  identities share a physical device. Interesting because it uses
  physical properties Rumor has (radio proximity) rather than abstract
  graph properties. **But** Android denies apps access to per-packet RSSI
  in the general case, and any bridge/relay tier defeats the test — the
  test only works for direct-radio peers.
- The 2018 survey (Rajan et al., Computer Networks Reviews) enumerates
  the MANET/mesh-specific mechanisms: symmetric-crypto-with-central-CA
  (doesn't fit Rumor), key pre-distribution (doesn't fit), RSSI/TDoA
  (partial fit — see Mason Test caveat above), radio resource testing
  (interesting: force each claimed identity to actually key up the radio
  simultaneously, a sybil-on-one-device can't). Radio resource testing
  might be worth a follow-on research row.

---

## 5. Synthesized guidance for O135 / O136

The literature converges enough to bound the design choices. Ordered by
impact vs implementation cost, cheapest-highest-impact first:

1. **KnownSenders inbox filter, opt-in (O135(1)+(3), SSB `hops=1` case).**
   Zero new protocol; a filter on the existing feed. Directly maps to
   SSB's design philosophy ("aren't friends with spammers"). Ship first.
2. **Explicit friend action (O136).** Prerequisite for everything downstream
   — no formal system in the literature works without a distinction
   between "seen this key" and "user affirmatively trusts this key."
   SSB's `follow`, PGP's `sign`, Advogato's `certify` are the same
   primitive under different names.
3. **Aggregate resource partition by tier (O135(1)+(2)).** The
   Douceur-impossibility floor: caps that are identity-independent are the
   only caps a sybil-farmer cannot bypass. Ostra's edge-credit refinement
   is worth prototyping if the coarse LRU-per-tier pool proves
   insufficient in simulation.
4. **Transitive vouching with a small hops default (O135(4), SSB port).**
   Ship the mechanism straight from `ssb-friends`; do NOT invent a new
   graph algorithm. Default `hops=2`. Blocks dominate follows
   transitively. Store the vouch chain locally so "block the signer" can
   actually enumerate and drop the subtree.
5. **Fix vouch capacity at admission, not by inheritance (Advogato
   inoculation).** Cheap invariant, defeats a known break class before it
   ever appears.
6. **Never a global trust score.** Every user's view is local, seeded from
   their own friend set. This is what makes the system incoercible and
   removes the ~single most attackable surface in every centralized
   system reviewed above.

### Explicit non-goals (backed by the literature)

- **Do not build proof-of-work admission.** Impossibility of getting the
  cost curve right on Rumor's power envelope (O55): a cost cheap enough
  for a solar/handcrank phone is trivially cheap for an attacker with a
  desktop or a botnet. This is the standard critique of PoW-based sybil
  defense (see Douceur 2002 and the SybilControl paper's honesty about
  its own limits).
- **Do not build a global reputation score.** Attacked by every system in
  §1–4 that tried; and it recreates the centralization Rumor exists to
  avoid.
- **Do not attempt to *prevent* sybils.** Impossibility (Douceur). Bound
  their damage instead. This is not a compromise — it is the state of the
  art.

### Suggested new open-items follow-ups

Filing shape (do not edit CLAUDE.md as part of this research task; these
are proposals for the next backlog pass):

- **`[TODO/CODE]` KnownSenders inbox filter** — split from O135(1). One
  filter class, one Settings toggle. Highest-impact-per-line-of-code item
  in this whole area.
- **`[TODO/SIM]` Ostra-style per-edge vouch credit** — cheap simulator
  scenario comparing (a) O135(2) global bucket vs (b) per-edge credit
  budgets under a sybil-friending-a-real-user attack.
- **`[TODO/CODE]` Advogato-inoculation invariant** — document + enforce
  that any future capacity/weight function computes capacity from local
  depth-from-seed, never from the vouching edge's asserted capacity.
- **`[TODO/HW]` Radio-resource-test feasibility check** — one afternoon
  on the fleet: does Android surface enough radio API on RSSI/TDoA to
  make the Mason Test viable at the direct-peer tier? Probably no on
  API 34+, worth confirming.

---

## Sources (consolidated)

- Douceur — *The Sybil Attack* (IPTPS 2002). The impossibility result.
- Yu, Kaminsky, Gibbons, Flaxman — *SybilGuard* (SIGCOMM 2006).
- Yu, Gibbons, Kaminsky, Xiao — *SybilLimit* (S&P 2008 /
  <https://ieeexplore.ieee.org/document/4531141/>).
- Alvisi et al. — *SoK: The Evolution of Sybil Defense via Social
  Networks* (S&P 2013,
  <https://oaklandsok.github.io/papers/alvisi2013.pdf>).
- Kermarrec et al. — *Gossiping with Append-Only Logs in
  Secure-Scuttlebutt* (DICG 2020,
  <https://dicg2020.github.io/papers/kermarrec.pdf>).
- SSB handbook — *design-challenge-sybil-attacks*:
  <https://github.com/ssbc/handbook.scuttlebutt.nz/blob/master/stories/design-challenge-sybil-attacks.md>.
- Levien — *Attack-Resistant Trust Metrics* (thesis):
  <https://levien.com/thesis/thesis.pdf>.
- Ruderman — Advogato critique:
  <https://www.squarefree.com/2005/05/26/advogato/>.
- Freenet WoT: <https://github.com/hyphanet/plugin-WebOfTrust/blob/next/README.md>.
- Mislove et al. — *Ostra: Leveraging Trust to Thwart Unwanted
  Communication* (NSDI 2008).
- Danezis & Mittal — *SybilInfer* (NDSS 2009).
- Gilbert et al. — *The Mason Test* (<https://arxiv.org/pdf/1403.5871>).
- Rajan et al. — *Survey on sybil attack defense mechanisms in wireless
  ad hoc networks* (Computer Networks Reviews, 2018).
