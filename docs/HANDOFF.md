# Handoff note

> Per `docs/MULTI_INSTANCE_COORDINATION.md` async-handoff section.
> This snapshot covers an autonomous-overnight session (user asleep).

## Branch state

`claude/practical-archimedes-wmySm`. **Wall clock at write:** ~20:55 UTC
2026-06-05.

## Autonomous-session commits (chronological)

13 commits, all on `claude/practical-archimedes-wmySm`, pushed.
Each commit message includes an explicit **Rollback** note per the
overnight-instructions request that experiments be documented for
clean reversal.

| Commit | Row | Result |
|---|---|---|
| `af48fef` | CLAUDE.md decisions | Multi-recipient envelope chosen for ENCRYPTED rooms; O89 ratchet design problem collapsed; O90 (thread + mention `_ext` fields) filed. Names reserved in `docs/RENAMED_FIELDS_NEVER_REUSE.md`. |
| `39c636b` | **O90 → PART** | `_ext.replyTo` + `_ext.mentions` accessors with copy helpers (`withReplyTo`, `withMentions`). 11 tests pin field names + wire format + round-trip + tamper. |
| `d7b17fa` | **O79 wire substrate** | `MultiRecipientEnvelope` + `KeyWrap` data classes + signable bytes function. 10 tests pin sig scope (relay can't extend/trim/permute recipients). |
| `42c27a1` | **HKDF-SHA-256** | RFC 5869 implementation over the existing `HmacSha256` primitive. 10 tests pin RFC 5869 vectors (case 1, case 3) + length/edge guards + domain-separation property. |
| `ca77c28` | **O79 envelope codec** | `MultiRecipientEnvelopeCodec.encrypt` / `.decrypt` pure functions composing X25519 + HKDF + AES-256-GCM + Ed25519. 9 tests cover single + multi-recipient round-trips, non-recipient drops, tampered sig / appended slot / tampered ciphertext rejections, per-message FS (same input → different ciphertext). All key material zeroed in finally blocks. |
| `ab2abae` | **O79 routing tag derivation** | `RoomRoutingTag.openRoomTag` (SHA-256 prefix) and `.encryptedRoomTag` (per-message HMAC) + `.deriveEncryptedRoomRoutingKey` (HKDF). 11 tests pin behavior + 4 domain tag separations. |
| `b441b8a` | Reserve domain tags | 5 new entries in `RENAMED_FIELDS_NEVER_REUSE.md` (envelope sig scope, wrap-key HKDF info, OPEN routing prefix, ENCRYPTED routing HMAC prefix, routing-key HKDF info). |
| `44c6f44` | **O79 receiver-side matcher** | `RoomTagMatcher.match` identifies which subscribed room an inbound tag is for. OPEN check runs first (cheaper); ENCRYPTED on miss. 10 tests cover all paths. |
| `ae4e73f` | **O79 MessageType + relay** | `MessageType.ROOM_MESSAGE` enum entry. Traffic class follows BROADCAST/DIRECT/BRIDGE_VOUCHED content-driven branch. Relay alongside BROADCAST; clampTtl to MAX_BROADCAST_HOPS. 2 new traffic-class tests + exhaustiveness allowlist updated. |
| `bc04b79` | CLAUDE.md O79 sync | Row's "Done" / "Not done" split updated to reflect protocol-layer completeness. |
| `88d2bd0` | **O90 compose-side** | `GossipEngine.composeBroadcast` and `composeDirect` accept optional `replyTo: String?` and `mentions: List<String> = emptyList()` params. Default-empty fallback preserves existing call-site behavior; sim tests recompile cleanly. |
| `df64401` | CLAUDE.md O90 sync | Row updated; remaining open item narrows to UI consumption. |
| `6c9abbe` | **O79 RoomSubscriptionRepository** | Pure-interface contract + `RoomSubscription` validating data class + `RoomSubscriptionMode { OPEN, ENCRYPTED }`. 7 tests cover routing-key-length init guards + content-equality semantics. Impls not added; that's app/sim wiring for later. |

## What this autonomous session accomplished

**O79 protocol primitives are complete and end-to-end tested.**
The wire types, encrypt + decrypt codec, routing tag derivation
(both OPEN and ENCRYPTED), receiver-side tag matcher, MessageType
+ traffic class + relay + clampTtl, and the subscription repository
interface all landed.

**O90 (thread + mention metadata) is complete except UI.**
Substrate accessors, compose-side wiring, never-reuse name
reservation all done.

**O89 collapsed substantially.** The decision to use multi-
recipient envelopes for ENCRYPTED rooms means there's no shared
room key to ratchet, no rotation cascade, no catch-up-after-
missing-rotation flow. O89 reduces to just write-enforcement via
posting certificates.

**Apache 2.0 LICENSE + project signing key policy + Rooms threat
model** were committed in the prior chat session (`9950316`).

## What's left for O79

Bounded code work, no design decisions remaining:

- `GossipEngine.composeRoomMessage(roomId, plaintext, recipients,
   replyTo?, mentions?)` helper that:
   - Computes the routing tag via `RoomRoutingTag`
   - Encrypts via `MultiRecipientEnvelopeCodec.encrypt` (ENCRYPTED)
     or wraps as signed plaintext (OPEN)
   - Sets `_ext.rt` to the routing tag
   - Wraps in a `RumorMessage` with `type = ROOM_MESSAGE`
- Receive-side dispatch in `processIncoming` for ROOM_MESSAGE:
   - Pull `_ext.rt`; call `RoomTagMatcher.match`
   - On match: decrypt via codec (ENCRYPTED) or pass through (OPEN)
   - Emit plaintext to inbox
- App-layer `RoomSubscriptionRepositoryAdapter` (Room/SQLite) +
  `:simulator` in-memory stub + DI wiring
- Events-derived membership projection cache (the
  `RoomCreate + invites/joins/leaves/bans` replay)

UI work (member roster, "Alice joined" surfaces) is gated on the
above.

## What was rejected during the autonomous session

- **Implementing a Room app-layer repository adapter** — would
  require Room schema migration (current v8) + AppModule wiring
  changes. Scope creep beyond "pure-interface contract" for what
  is fundamentally a single-user-decision question (what fields
  to persist; the user is asleep). Repository INTERFACE landed;
  impl is the next session's pickup.

- **`GossipEngine.composeRoomMessage` helper** — needs
  membership-list enumeration which depends on the events-derived
  membership projection cache, which doesn't exist yet. Could be
  built as a free-standing helper that takes a recipient list
  directly, but felt premature without the membership story
  settled. Substrate primitives are in place; helper is a clean
  follow-up.

- **iOS / desktop platform actuals** — gated on Mac access per
  `docs/IOS_PORT_PLAN.md`. Not attempted.

- **UI work** — gated on visual verification; honest "cannot do
  without you driving" item.

## Backlog state at handoff

- **Counts:** still need a refresh; rows that moved this session:
  - O79: PART (substrate → "protocol primitives complete")
  - O89: tag unchanged but scope collapsed
  - O90: TODO/CODE → PART → PART (substrate + compose-side wired)

- **Completed gaps:** G1–G28.
- **Open rows touched this session:** O79, O89, O90.
- **New file count:** 11 production source files, 7 test source
  files, 3 doc files. ~3500 lines of net additions.

## Tooling status

- `:core:jvmTest` — green at HEAD (`6c9abbe`).
- `:app:testDebugUnitTest` — green.
- `:simulator:test` — green (required `:simulator:clean` once
  after the `composeBroadcast` signature change to refresh JVM
  bytecode; documented in commit `88d2bd0` message).
- Test count grew by ~60 across the session.

## What's NOT updated and may be stale

- The "Counts as of this writing" header in CLAUDE.md still shows
  pre-session numbers; should refresh when convenient. Probably
  ~16 PART · 14 DECISION · 39 TODO at the current head.
- iOS actuals still throw `NotImplementedError`.
- No `RoomSubscriptionRepository` impl wired into AppModule.

## Canary

"By Order Of The High Magnate" used on every commit message this
session.
