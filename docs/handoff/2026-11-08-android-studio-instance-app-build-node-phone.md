# Handoff — Android Studio instance: `:app` build verified, PC node ↔ phone (2026-11-08)

## Done

- **`:app` compiles and its tests pass**: `assembleDebug` + `testDebugUnitTest`,
  17 classes, 128 tests, 0 skipped/failed, no zero-test class. That covers the
  two never-compiled lines from the previous brief: the O249 `TransferDao` prune
  query (Room's KSP validates the SQL at compile time) and AppModule's
  `modeState = get<ModeState>()`.
- JVM suites: `:core` 516/0, `:node` 4 (1 skipped, pre-existing), `:simulator`
  165 with 2 red. `LivenessWindowModeAwarenessTest` passes alone (a load flake).
  `LanLivenessValidationTest`'s liveness-ON arm is **red even alone, at every commit
  tried, on this Windows box**. Filed as **O251**; it is not a regression from this
  week and not the known load flake.
- Moto `ZY22KP7F59` reflashed with the `3e0318b` debug APK (`install -r`, data kept).
- **PC `:node` ↔ phone over LAN works both ways.** The user joined the Moto to
  `TMOBILE-FA82`, which is the PC's Ethernet subnet (PC `192.168.12.105`, Moto
  `.211`). mDNS found the phone, sessions complete, and broadcasts went both ways
  (node → phone Feed, phone → node status page).
- `scripts/rumor-node.cmd` is a Windows launcher (runs `node/build/libs/node.jar`,
  opens `http://127.0.0.1:8180/`). The user's Desktop has `Rumor Node.cmd`
  calling it. Creating a `.lnk` via PowerShell COM was denied by the permission
  classifier, so it's a `.cmd` instead.
- `scripts/backlog.py` crashed on Windows (it decoded `git show` output as cp1252).
  It now passes `encoding="utf-8"`.

## Machine facts (add to §1 if they hold)

- The PC has **no Wi-Fi adapter** (Ethernet only), so the phone must be on
  `TMOBILE-FA82` for the LAN path. Wi-Fi Direct to the PC is impossible.
- Earlier Temurin 17 scratch copies are gone across sessions; re-fetch it (the
  Adoptium API `latest/17/ga/windows/x64` zip). Use JDK 21 (Adoptium) as `JAVA_HOME`.
- The Moto auto-unlocks after a reinstall (O138). The Feed's compose box sits
  where the unlock field was, so typing the passphrase "blind" puts it in the
  draft. Dump the UI first.
- A ResourcesManager stack-trace flood rotates the logcat ring fast, so `Rumor/*`
  lines from the service start are gone within minutes.

## Not done

- G68 transfer check (files phone↔phone reaching COMPLETE) needs a second phone;
  only the Moto was connected. The node page has no file-send.
- The phone backlog from the previous brief (O187 → O185 → O216, O235/O237) is untouched.
