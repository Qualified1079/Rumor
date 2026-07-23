# Simulator & scenario-testing conventions

Reference for writing `:simulator` scenario tests and `:core` logic-scenario
tests (`SybilFloodScenarioTest`, `TrustGraph*Test`, `MeshViewConvergenceTest`,
`RoomMessageScenarioTest`, …). These are the hard-won rules; follow them or
re-learn them the hard way.

## 1. Drive bad input through the real path — never assert on a bare object

The single most important rule. A test that constructs a "malformed" or
"adversarial" message as a **local in-memory object** and asserts on it directly
proves *nothing about the receive path* — the drop/validation logic it claims to
test is never reached.

- **Bad:** `val tampered = msg.withRoomRoutingTag("%%%"); assertNothingCrashes(tampered)`
  — the malformed message never touches `gossipEngine`/`SimTransport`, so the
  real base64-decode guard is never exercised. (This was a live vacuous test —
  `RoomMessageMalformedTagTest`, tracked as O129.)
- **Good:** deliver the tampered message through `SimTransport(a, b).exchange(...)`
  (or `node --sybil` on the headless side) so it flows through
  `processIncoming` → the actual guard, then assert on the *observable effect*
  (dropped from store / dropped from inbox / relayed-anyway).

If a "bad message" test never routes the message through the transport, it is
testing nothing.

## 2. Await the FINAL observable effect; absence needs a settle delay

`GossipEngine` handlers run async on each node's scope. Assert against the *last*
effect in the causal chain, never an earlier one.

- `awaitUntil { … }` on the terminal signal (message in the store / id in the
  mesh view / inbox recorder populated) — not on an intermediate one (e.g. the
  dedup id) that lands a moment before the effect you care about.
- **Absence assertions** ("X never arrived") need a settle delay *after* the last
  awaited positive effect — otherwise you're asserting absence during a window
  where it simply hasn't arrived yet.

(Origin: O114/G41 — a real CI flake from awaiting the wrong effect.)

## 3. Negative controls — every scenario proves its own teeth

A green scenario is worthless if it would *also* be green when the mechanism is
broken. Pair every "the defense works" assertion with controls that fail if the
harness is vacuous:

- **Baseline control:** the same scenario with the defense OFF must show the bad
  outcome (e.g. `SybilFloodScenarioTest` "baseline permissive inbox lets every
  sybil through"). Proves the measurement can see the thing it's suppressing.
- **Discrimination control:** the defense must *let the good case through*, not
  blackhole everything (e.g. "a FRIENDED sender DOES reach the inbox"). Proves
  the filter discriminates and the recorder actually fires.
- **`assertThrows` sanity checks:** wrap a deliberately-WRONG assertion in
  `assertThrows(AssertionError::class.java) { … }` so the test passes precisely
  because the false claim fails. This proves the assertion has teeth. See
  `TrustGraphSanityTest` — e.g. "claiming sybils are absent WITHOUT a block MUST
  fail", "claiming an unreachable node is admitted MUST fail".
- **Not-vacuous check:** assert the "before" state actually contains what the
  "after" removes (e.g. "without a block the sybils really are present"), and
  that the measured quantity can be genuinely empty (frontier at hops=0), so it's
  not a constant that only ever looks populated.

## 4. Tear down scopes; keep node counts modest

Each `SimNode` is a full engine graph (scopes, flows). The whole sim suite runs
concurrently, so leaked nodes accrete across tests and OOM the Gradle worker
(seen: `OutOfMemoryError` under the triple-suite run, not a logic failure).

- `try { … } finally { scope.cancel() }` around scenario bodies that spin many
  nodes.
- Keep counts to what proves the property — 12 sybils is plainly a "flood" for a
  0-leak assertion; you don't need 200 live engines. (Pure-logic scenarios like
  `TrustGraph*Test` have no engine and can use hundreds cheaply.)
- Prefer a `:core` pure-logic scenario over a live-engine `:simulator` one when
  the property is algorithmic (the trust-graph tests are pure functions — fast,
  deterministic, hundreds of nodes free). Use `:simulator` when you need the
  real GossipEngine/transport/ingest path.

## 5. Determinism

Per-node handler order is pinned (`SimNode` wraps its scope with
`limitedParallelism(1)`; O114/G41). Seed any `Random` explicitly
(`SimTransport(a, b).exchange(Random(1))`). Sort snapshots by id where iteration
order would otherwise seed downstream RNG differently across replays.

## Division of labor: sim vs headless node

- **`:simulator` / `:core` scenarios** — scale, statistics, determinism; pure
  protocol. The only place you can run enough nodes to see a bound (e.g. the
  SybilLimit per-attack-edge behavior across hundreds of identities).
- **`:node` headless (`--sybil` etc.)** — the *real Android/JVM path* end-to-end
  over the LAN transport; catches cross-layer wiring bugs the sim's in-memory
  repos structurally miss (the class of the `_ext`/Room round-trip bug). Use it
  to validate that the real inbox filter / contact table / relay actually
  enforce what the sim proved.

Prove the bound at scale in the sim; prove the real path enforces it on the node.
