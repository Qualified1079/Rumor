# :simulator — rules that apply when you work here

JVM CLI and Ktor dashboard over `:core`, with in-memory repos and `SimTransport`. `SimNode` builds a `MeshGraph` and nothing below it.

## Scenario tests — read `docs/SIMULATOR_TESTING.md` before writing one

1. Drive bad or adversarial input through the **real transport** (`SimTransport.exchange`, or `node --sybil`), never assert on a bare in-memory object. That is the O129 vacuous-test trap.
2. `awaitUntil` the **final observable effect**; proving absence needs a settle delay (O114/G41).
3. **Every scenario carries negative controls:** a baseline (defense off, bad outcome visible), a discrimination control (the defense lets the good case through, it is not a blackhole), and `assertThrows(AssertionError)`-wrapped wrong assertions proving the checks have teeth (`TrustGraphSanityTest`).
4. `finally { scope.cancel() }` and modest node counts; live engines OOM under the parallel suite. Prefer a pure `:core` scenario when the property is algorithmic.
5. When the security boundary is on receive, the test must bypass compose. A hostile client never calls our compose path.

Sim proves the bound at scale; the headless `:node` proves the real path enforces it.

## Reading results

- A green suite can be vacuous. Run `python scripts/junit-summary.py` and check skipped is 0 and no class ran zero tests, then teeth-check: disable the guard, confirm exactly the claiming test fails, restore. Confirm you disabled the line you meant to.
- **Load-flaky under the full parallel suite** (red in the full run, green alone): `LanLivenessValidationTest`, `LanMeshHarnessTest`, `LivenessWindowModeAwarenessTest`, once `TransferAssemblerHardeningTest`. These use real sockets, 12–52 s each (O208).
- `LanLivenessValidationTest`'s liveness-ON arm fails deterministically on Windows, even alone (O251).
- Sim-only knobs on `MeshGraph` are named for why they exist (relay windows, `breadcrumbRouting` for O29 A/B runs); don't add one without that reason.
