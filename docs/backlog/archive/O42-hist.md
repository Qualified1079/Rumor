# O42-hist (archive: full tombstone record)

*(history of the closed row)*

Current gossip uses a bloom filter sized for expected message-set size;
on long partition heal the actual symmetric difference can blow past the parameters, causing false-positive collapse (receiver thinks peer has things it doesn't, peer holds back, messages don't flow).
Existing graceful-degradation fallback (O13) handles this safely but inefficiently. Port Negentropy (Meijers 2023, arXiv 2212.13567;
reference impl github.com/hoytech/negentropy, MIT licensed, ~500-line Kotlin port):
O(d log N) bandwidth, deterministic, no parameter tuning, no decode-fail mode, deployed in Nostr's strfry at tens-of-millions-of-elements scale via NIP-77.
Operates on `(timestamp, id)` tuples — matches existing message model. Composes with peer-aware batch shaping (P2) — the overlap heuristic stays;
only the wire format changes. Solves O13's BloomFilter OOM problem as a side effect. Don't roll your own;
don't pick CPISync (cubic compute), classic IBLT (parameter tuning fragility), or rateless IBLT (too new, no battle-tested impl).
**Status (as of 5e84fa0):** algorithm landed in `core/sync/Rbsr.kt`; wire types in `core/sync/RbsrWire.kt`;
capability-gated branch in `GossipSession` (`rbsr-v1` in HELLO `supportedFeatures`); `SimTransport.useRbsr` toggle for scenario comparison.
**Ready for simulator validation, not ready for the wire** — `LOCAL_SUPPORTED_FEATURES` stays empty in production, opt-in is per-session for now.
Before promoting to default-on, review against the `hoytech/negentropy` reference implementation for bound-edge fingerprint stability (the subtle case where range boundaries fall on item keys and bisection rounding produces non-identical fingerprints across peers).
Wire format gets locked only after that review passes and after simulator runs demonstrate the bandwidth win against bloom under realistic partition/heal scenarios.
**Reference review done (this session):** fingerprint switched from XOR to **additive mod 2^256 + bound item count** in `Rbsr.fingerprint` — XOR is GF(2)-linear and a peer choosing message ids could forge a colliding range fingerprint (→ wrongly `Skip`ped, messages silently fail to reconcile);
addition matches negentropy and makes forgery a 256-bit subset-sum. Bound-edge concern resolved:
`Long` bounds survive the wire exactly (kotlinx integer literal, no `Double`), range membership is a pure `(ts,id)` comparison applied identically both sides, and session integration acts on both diff directions.
**Simulator validation done (`simulator/…/engine/RbsrBandwidthScenarioTest.kt`):
the bandwidth win does NOT hold at small/medium set sizes.** Measured summary-phase bytes: N=1000 → bloom 3224 / RBSR 13784 (RBSR 4× *worse*);
N=8000 → bloom 25584 / RBSR 20398 (RBSR ~20% better). Crossover ≈ 5–6k messages. Root cause: Rumor's RBSR frames are verbose **JSON** (~130 B each:
field names + base64 fp), so a 16-way bisection sends heavy frames — negentropy's `d log N` win assumes compact binary framing.
RBSR's *unconditional* win is **exactness** (no bloom false-positive message loss — separately asserted and holds at every size).
**Implication for promote-to-default-on (O42 next step): do NOT flip `LOCAL_SUPPORTED_FEATURES` on blindly** — below the crossover it regresses the common case.
**Decision (this session): size-gated adaptive selection, NOT blanket default-on.** Rationale:
for Rumor the summary phase is usually dwarfed by message payloads, so RBSR's bandwidth win is irrelevant EXCEPT in one pattern that is exactly where the app is headed — persistent links (O2/G19) between static/free anchors with large accumulated stores (O55) re-syncing every ~10s.
There, at d→0 (already synced) RBSR sends ~500 B while bloom re-ships the whole filter (~3.2 B × N → 160 KB at N=50k):
an *order-of-magnitude* win, not the 20% the crossover test showed. So:
use **bloom below a set-size threshold** (cheap, and made near-exact by a lower FP rate — see below) and **RBSR above it** (its actual sweet spot).
The selection must be **symmetric+deterministic** — both peers advertise `rbsr-v1` AND exchange set size (in HELLO), both compute `useRbsr = bothSupport && max(sizeA,sizeB) > THRESHOLD`, so they never split modes.
Compact framing (option a) becomes a "nice later" since RBSR only runs where it wins even with JSON frames.
**Bloom FP rate must drop for chat UX:** the default 1% means ~1 in 100 messages skipped per exchange on a link, and on a quiet mesh (static blooms) that skip persists → a noticeably late/undelivered chat message.
Because adaptive keeps bloom on *small* sets only, a much lower FP is nearly free there (a fat bloom over a few hundred items is still tiny).
Target set by simulation (`RbsrBandwidthScenarioTest`) — measure empirical FP (the custom murmur mix + `hashCount` cap at 10 deviate from theory) and pick the knee.
FP rate is an implicit wire constant (`BloomFilterData.deserialize` rebuilds at the default rate) so it changes via the single default; peers must match.
Framing to users: Rumor is not a four-nines email client, but delivery UX should still be good.
