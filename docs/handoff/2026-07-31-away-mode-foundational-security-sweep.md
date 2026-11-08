# Handoff — away-mode foundational security sweep (2026-07-31 → 08-01)

Away mode ON (user: "do whichever is most foundational first" + "put yourself in
away mode, flag things and keep building"). Directive: sim/desktop for code
validation, phones only for phone-specific checks — nothing here needed a phone
(all pure-JVM, validated by unit/sim tests, which is the correct surface for the
`_ext`/persistence bug class that in-memory repos can't catch). Build with
`JAVA_HOME=/home/user/jdk17` (Temurin 17 matches the jvmTarget; Gradle 8.13 won't
run on the JBR 25 that `JAVA_HOME` points at by default).

## What shipped (all pushed to main, all suites green)
- **O162** (`4906826`) — `RumorMessage.trustLevel` now persists (schema **v11→v12**,
  `12.json` committed). It's derived-at-receive + `@Transient` on the wire, so a
  missing `MessageEntity` column silently reverted every reload to VERIFIED. Also
  closed the *latent laundering path* the gap masked: `manualRelay()` (Feed relay
  button) + `messagesForExchange()` (store backfill serve) didn't re-check the
  BRIDGED gate that `relay()` has — now they do. `BridgedNotServedTest` (sim).
  Latent today (bridged broadcasts aren't persisted — O161), goes live with O161/O17.
  Incidentally fixed the O156-stale `BridgeVouchedTest -v1:→-v2:` assertion that had
  left main's `:app` suite **red** since `0d7d89b`.
- **O157** (`3002e40`) — OPEN-room routing tag now bound into the signed transcript.
  `signableBytes` appends the framed `_ext.rt` for `ROOM_MESSAGE` only (gated on
  type → broadcast/DM transcripts byte-identical, no wire bump); `buildMessage`
  stamps the tag pre-sign. Keyless retag (roomA→roomB, no key needed) now breaks
  the sig → dropped at verify. `RoomRetagForgeryTest` (sim); updated
  `RoomMessageMalformedTagTest` (tampered tag is now dropped-not-stored).
- **O182** (`dc6ab72`) — `:node` got its first tests (`NodeIdentityProviderTest`,
  4): identity well-formedness + userId binding, seed persistence across restart,
  owner-only perms, `FileHlcStore` round-trip.
- **O180** (`db47091` + TOCTOU follow-up `6ad55d9`) — `NodeIdentityProvider` seed
  file is owner-only from creation (atomic `CREATE_NEW` + 0600 attr, no
  world-readable window) AND the key is written straight into the create handle —
  no close-then-reopen-by-path. A **background security review flagged the
  reopen as a TOCTOU** (path could be swapped for a symlink between close and
  reopen); fixed + fails-closed on a lost create-race.
- **O166** (`038ad64`) — `LanTransport` accept loop caps pre-HELLO
  concurrency: `MAX_INBOUND_INFLIGHT=32` + `MAX_INBOUND_PER_SOURCE=4`, admitted /
  rolled-back atomically before `GossipSession`, excess closed immediately (retry
  next 10s round). Bounds unauth Ed25519-verification cost on the O104 "laptop IS
  the AP" node. `LanTransportInboundCapTest`.
- **O170** (`6d42119`) — `:app` bridge fuzzers no longer swallow `Error`.
  Contract-specific (probed the real decoder behaviour first): Meshtastic *throws*
  on bad input + prod wraps it → catch `Exception`, let `Error` reach Jazzer;
  MeshCore is total + prod-unwrapped → catch nothing, seed test uses
  `assertDoesNotThrow`. Both seed `@Test`s gained real assertions (were zero).

## Needs your attention (flagged, not blocking)
- **O163 is BLOCKED on token scope.** The one-line fix (add `:node:test` to
  `ci.yml`'s unit-test step) can't be pushed — the push PAT lacks `workflow`
  scope, so GitHub rejects any `.github/workflows/*` change. **Apply by hand:**
  in `.github/workflows/ci.yml` line 23, append ` :node:test` to the gradle
  command. Verified green locally. Until then `:node` (now tested via O182) is
  still CI-invisible. Marked BLOCKED in CLAUDE.md + OPEN_BACKLOG.

## Next-session pointers
- Remaining pure-`[TODO/CODE]` Tier-2 security rows I did NOT reach:
  **O48** (bridge-asserted-pubkey constraint on synthetic userIds), **O112**
  (broad hostile-input sweep — larger/vaguer, wants care). ~~Note: the `:core` fuzz files still use the
  `runCatching{Throwable}` pattern O170 fixed in `:app`~~ → **done**
  (`4686325`): a `fuzzParse{}` fence catches only `Exception` and lets `Error`
  reach Jazzer, plus a deterministic fence-has-teeth assertion. O166's sibling: the O104 product node will want a
  time-based per-source throttle too, but concurrent caps were the audit's ask.
- Foundational ordering held: closed both `:node`-CI Tier-1 code rows (O182 done,
  O163 blocked-on-token) and the Tier-2 HIGH forgery/persistence rows.
