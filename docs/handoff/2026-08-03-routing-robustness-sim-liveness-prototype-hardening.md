# Handoff — routing-robustness sim + liveness prototype + hardening pins (2026-08-03, away)

*(Away mode was ON for this session; that flag is historical — check whether it
still exists before assuming it applies.)* All pure
`:core`/`:simulator` + one dashboard change; everything committed + pushed to `main`, all
suites green. Build with `JAVA_HOME=/home/user/jdk17`.

## The headline result — routing robustness vs flooding, quantified
Answered the honest flip-side of O204 ("routing targets tighter → fewer redundant copies →
is it MORE fragile to a mid-path relay dropout?"). Full write-up: `docs/SIM_HARNESS_RESULTS.md`
"Mid-path dropout" section. This is **dynamic next-hop routing (O59)** — message carries no
path, `intendedPeers` is `@Transient`, each relay consults its OWN breadcrumbs.
- `SmartRoutingDropoutTest` (real GossipEngine anchor) + `RoutingBandwidthSweepTest`
  (abstract model, **gated to reproduce the real engine's diamond numbers 9/2/5 exactly**).
- **The fragility is real:** naive straight-line routing's fallback is "flood iff I hold NO
  crumb" — a *stale* crumb (points at a now-dead node) is not "none", so the relay offers
  into the void and never floods → strands. ~25% delivery loss under one mid-path dropout.
- **Reliability lines up on an information axis:** stale-hop NAIVE (75.1%) → local-neighbourhood
  DIVERSE/LIVE (~97.7%, a residual a single-hop view can't close — downstream cuts) →
  end-to-end ACK (exactly 100%). **LIVE+ACK is the winner:** 100.0000% at 68% of flood's
  bandwidth, lower latency than NAIVE+ACK (liveness makes ACK-escalation rare).
- **Precision fix the user asked for:** the "~2.3% flood loss" was NOT loss — it was graph
  **partitions** (killed relay = articulation point). Conditioning delivery on B-reachability
  (BFS) + 20k trials → flood ceiling is **exactly 100.0000%**. Same lesson applies to other
  delivery sims (**RelayEvictionModelTest** is the top candidate — its abs numbers drive O193;
  it also has an RNG-perturbation confound: ACK-loss draws shift the meeting sequence). Left
  for USER REVIEW — changing that decision sim's reported conclusions shouldn't be unattended.
- Learned-crumb regime: crumb *diversity* needs repeated traffic to accumulate; fresh
  post-dropout traffic (relearn) lifts even NAIVE to 100% — heuristics matter most in the
  dropout→relearn window.

## Liveness prototype — SHIPPED, and since flipped default-ON (O198)
`GossipEngine.livenessRouting` (`@Volatile`, default false, no DI churn): when ON, a routed
DM floods when NONE of its crumb candidates are currently ONLINE (`OnlineStatusTracker` 5-min
window) instead of stranding at a dead next-hop. `SmartRoutingLivenessTest` proves the A/B on
the REAL engine (off=strands, on=delivers via bypass). **Decision since made (O198, `50b2ec9`): default-ON**, after validation at all
three fidelity tiers including real-wire `LanLivenessValidationTest`. The signal
also changed — it no longer reads `OnlineStatusTracker`'s 5-minute *presence-
display* window (wrong threshold for routing) but a first-hand
`directContactMs` map on a routing-cadence window (`DEFAULT_LIVENESS_WINDOW_MS`
= 30s ≈ 3 LAN rounds), bounded and pruned so a sybil id-flood can't grow it.
Mode-aware tuning of that window is **O205**. The two-phone A/B this entry
wanted was never run — the flip rests on sim + LAN evidence only.

## Device test (2 phones, away-mode autonomous) — discovery works, group-formation doesn't
Moto G Play 2024 (`ZY22KP7F59`) + OnePlus CPH2515 (`ec5b0707`), both API 34. Fresh current-main
debug build installs + runs clean, auto-unlocks (`passphrase1`), **zero crashes**; the two
**discover each other over Wi-Fi Direct**. BUT group formation **churns**
(`connect → connected=false → Disconnected` loop), no `GossipSession`/HELLO, **no delivery** —
the documented O98/O99/O94 limitation, not a regression. No WPS dialog pending (framework-level
connect fail). No shared Wi-Fi (saved AP auto-join off, no password) so the reliable LAN path
was unavailable. **Cross-phone delivery is blocked on user-only input:** shared Wi-Fi creds, or
on-screen involvement for the O98 Wi-Fi Direct tuning. Base routing *logic* is thoroughly
`:core`/sim-validated; the unverified-on-device piece is specifically the Wi-Fi Direct transport.
Phones left running.

## Foundational hardening pins shipped (all green, all pushed)
- **`:core` fuzz crash-masking fix (O170 sibling)** — `WireParserFuzzers`/`SeedCorpusTest`
  wrapped parsers in `runCatching{}` (catches Throwable → swallowed the OOM/SOE they exist to
  find). New `fuzzParse{}` fence catches only Exception, lets Error propagate. Fixed a
  factually-wrong comment in SeedCorpusTest + added a deterministic fence-has-teeth assertion.
- **RBSR range-query O(n)→O(log n) (O121(d))** — `SortedListRbsrStorage.items()` now binary-
  searches the sorted slice instead of a per-query full filter (hot path on large anchor
  stores). `SortedListRbsrStorageTest` pins equivalence to the old filter over 400 random cases.
- **MessageStore eviction coverage (O121(e), eviction-over-cap half)** — `MessageStoreEvictionTest`.
- **Dashboard: reproducible Randomize-all seed** — `POST /api/randomize?seed=N` replays; blank
  mints+returns a seed (shown in UI). The button already existed; reproducibility was the gap.

## Next-session candidates — three of four are now DONE (scrubbed 2026-10-10)
- ~~Flip liveness default-on~~ → **O198 shipped it** (`50b2ec9`); the two-phone
  A/B this asked for was never run, so it rests on sim + LAN evidence.
- ~~RelayEvictionModelTest precision/confound rework~~ → **done** (`835833a`).
- ~~O159: TransferAssembler needs a testability refactor before unit tests~~ →
  **done 2026-10-10 as G52** (injected scope + `internal` entry points; the
  60 s NACK delay is bypassed by driving ingest directly).
- **Still open:** O121(e) remainder — GossipEngine-level eviction coverage. And
  the O202 ACK-escalation layer (DIRECT_ACK substrate shipped; the escalation
  wiring is the remainder).
