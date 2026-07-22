# :node kickoff — headless test node first (2026-07-19)

> **STATUS 2026-07-22: DONE.** Both steps shipped — `core/runtime/MeshRuntime.kt`
> (MeshService hosts it, behavior-preserving, all suites green) and the `:node`
> module (LAN transport + in-memory repos + file identity/HLC + localhost status
> page). Two-node loopback exchange verified end-to-end; phone-on-LAN field check
> is the remaining verification. See the O106 row for the durable record.

Self-contained starter so a fresh instance can begin the `:node` work (O106) without
inheriting a long conversation. Read this + the O106 row + `CLAUDE.md`.

## Why now, and why only the headless slice
The phone app is message-working (send / receive / connect are solid; the backlog still
has polish + features). Decision this session: start `:node` now, but **only the headless
test node** — a directly-runnable, fully-inspectable second peer that accelerates the
remaining *protocol* debugging. On the phone I'm half-blind (adb + logcat, human does the
on-screen steps); a desktop node I can run, restart, log, and drive directly. The open
protocol items (e.g. the HLC SharedPreferences upgrade-crash, O124 presence-gate behavior)
are exactly the class that a runnable node reproduces in seconds vs a phone-flash loop.

A headless node is a debugging *aid*, not a "confounder" — it shares `:core`, so a protocol
bug is the same bug on both ends, and the node end is glass. The confound risk is real only
for a GUI *product* node, which is explicitly out of scope here.

**Gating check (passed):** send/receive/connect is stable → the behavior-preserving refactor
below has a stable base.

## Do, in order
1. **`MeshRuntime` extraction — pure, behavior-preserving refactor.** Pull the engine↔transport
   wiring out of `MeshService.startMesh()` (engine/transport config, reseed, flow collectors)
   into a `MeshRuntime` in `:core`, so `MeshService` (Android), a desktop `main()`, and later a
   systemd unit each just *host* it. **Phone behavior must be byte-identical.** This also cleans
   the `:core`/`:app` seam (the O107 transport-hosting boundary) — it benefits the phone
   regardless of `:node`.
2. **Headless CLI node.** Boot the real `MeshRuntime` over the **O93/G40 LAN transport**
   (`core/transport/lan/LanTransport.kt` — already pure JVM, field-working), with **in-memory
   repos** to start (defer the SQLDelight-vs-JDBC storage decision — O1). Runnable on the dev
   desktop; joinable by a phone on the same Wi-Fi. Verbose logging — this is an instrument.

## Do NOT build yet (this is the *product* node — after the phone is done)
GUI / kiosk dashboard, USB-boot packaging, persistence partition, hostapd / laptop-as-AP, the
Compose-desktop status app. All O106 (d)/(e). They would steal focus and confound. **Headless
test harness only.**

## Verify
- `:core` + `:app` build unchanged; **simulator suite green** (proves the refactor preserved
  behavior).
- Boot the headless node; a phone on the same LAN exchanges with it (`source=LAN` in logs) —
  a real second peer, fully inspectable.
- Then use it to repro/verify open protocol items far faster than the phone-flash loop.

## Workflow
Per user preference (memory `check-in-before-coding`): **announce the plan and check in before
writing/committing code**; report in practical terms (what the node does), not file lists.
Commit + push **directly to `main`, no feature branch** (per `CLAUDE.md` git directive).

## Cross-refs
O106 (parent), O107 (transport SPI), O93/G40 (LAN transport), O1 (storage choice), O44
(identity-at-rest platform fork). Priority framing (memory `rumor-priority-framing`):
**phone-P2P is #1** — this node is a protocol *test harness*, not a product pivot.
