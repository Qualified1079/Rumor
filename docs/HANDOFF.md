# Handoff note

> Per `docs/MULTI_INSTANCE_COORDINATION.md` async-handoff section.

## Branch state

`claude/practical-archimedes-wmySm`. **Wall clock at write:** 2026-06-05 20:09 UTC.

## This session's commits (chronological)

| Commit | Row | Result |
|---|---|---|
| `9251f95` | **O40 → G28** | Signed delete-on-ACK end-to-end (model + verifier + GossipEngine compose/relay/ingest + 7 tests). |
| `067baaf` | **O31 → PART**, O48 doc | Hello.recentlyExchangedWith + helloChallengeBytesV2; O48 row noted as blocked on O5. |
| `19cf42c` | **O42 v2 alignment** | SortedListRbsrStorage(formula) + RBSR_V2_FEATURE + GossipSession session-negotiation matrix. |
| `641c634` | O28 fuzz | KeywordFilterList + MessageDeletePayload parsers. |
| `d8079c9` | **O53 → PART** | HMAC-SHA-256 (RFC 4231 vectors) + SealedSenderTag.tagFor + 11 tests. |
| `fefe688` | **O38 → PART** | PrekeyPublish wire shape + PrekeyVerifier + 7 tests + fuzz harness. |
| `a2a3d80` | **O79 → PART** | Room/Invite/Action wire types + 3 domain tags + 5 tests + 3 fuzz harnesses. |

7 commits, 8 backlog rows advanced (1 closed, 5 to PART, 1 doc fix, 1 fuzz coverage extension). 30+ new tests.

## What I considered and rejected

- **Pushing O76 compose-side flip without your design input on the
  peer-feature cache location.** See questions below.
- **O38 rotation scheduler / sender prekey cache / composeDirect
  prekey selection.** These are local-state and scheduler plumbing,
  not protocol design — bounded but multi-commit work. Substrate
  shipped; integration is the next focused commit on this row.
- **O53 wire integration (compose-path stamping `_ext.t`, relay
  matching against pre-computed tag set).** The cryptographic
  substrate is now bounded; the routing changes need design
  decisions about coexistence-with-plaintext-recipientId vs
  wire-break, plus the per-contact shared-key derivation (HKDF
  helper would need adding to PlatformCrypto).
- **O48 bridge-asserted-pubkey migration.** Confirmed blocked on
  O5 (DM bridging) — MeshCore channel-broadcast wire format
  carries only `senderName`, no pubkey to hash. Row text updated
  to flag the dependency.
- **O77 massive 500-node 24-hour scenario.** Needs ≥32 GB RAM
  (probably won't fit in the dev environment available); skipped.
- **O79 routing integration + MessageType.ROOM_* enum entries.**
  Depends on whether non-subscribers see roomId in clear or via
  O53-style tags — design question. Substrate shipped; routing
  follows when that's decided.

## Suggested next moves

1. **O38 rotation scheduler + sender cache + composeDirect
   selection.** Substrate is in place; the remaining work is
   per-contact prekey cache (in-memory or Room-backed), a
   scheduler that fires on a cadence to broadcast fresh prekeys
   and delete expired private keys, and modifying composeDirect
   to DH against the cached prekey when one is valid.

2. **O76 compose-side flip.** Requires the peer-feature-cache
   decision (question below).

3. **O67 default-list seeding + UI.** Backlog row notes content
   sourcing for slurs/NSFW/gore lists is TBD — needs editorial
   judgment.

4. **O79 routing decisions.** See questions below.

5. **iOS PlatformCrypto / Compression actuals.** Same xtool/Mac
   gate as before.

## Backlog state at handoff

- **Counts:** 15 PART · 14 DECISION · 39 TODO (CODE 17 · SIM 2 · UI 9 · EMU 4 · HW 7). Total 68 open rows.
- **Completed gaps:** G1–G28.
- **Rows touched this session:** O31 (PART), O38 (PART), O40 (closed → G28), O42 (v2 wired through, row tag stays PART), O48 (doc fix), O53 (PART), O79 (PART). Plus G24 lint allowlist additions for new test fixtures.

## What's NOT updated and may be stale

- `docs/FDROID_BUILD.md` still names `LICENSE` as missing.
- iOS `PlatformCrypto.aesGcm*` and `Compression` actuals still
  throw `NotImplementedError`. Gated on the Swift bridge.
- `LOCAL_SUPPORTED_FEATURES` in `GossipSession` is still empty
  in production. By design until per-feature integration is
  complete (compression-v1, rbsr-v1, rbsr-v2, route-adv-v1).

## Tooling status

- `:core:jvmTest` — green at HEAD (`a2a3d80`).
- `:app:testDebugUnitTest` — green.
- `:simulator:test` — green.

## Canary

"By Order Of The High Magnate" used on every commit message this
session.

## Questions for the user (waiting on these before unblocking specific rows)

### Compose-side O76 (compression-v1) flip

The receive path is live (commit `59995d2` from the previous
session); the compose path needs to know whether a recipient
supports compression-v1. Two reasonable architectures:

  **A. Additive `Contact.lastKnownSupportedFeatures: List<String>`
  field, populated by a successful HELLO via the
  WifiDirectTransport / GossipSession callback path.**
  - Survives reboots (Room-backed).
  - Touches Room schema (v7 → v8) + adapter + simulator stub.
  - Slight staleness window: a contact's HELLO features get
    cached on first contact in a given build; if they upgrade
    capability, we don't notice until the next live HELLO.

  **B. Session-tier `recipientFeatureCache: Map<UserId, List<String>>`
  inside GossipEngine.**
  - Simpler — no Room migration.
  - Resets on process restart; first-message-after-restart
    falls back to the uncompressed path until we've handshaken.

Which do you prefer? Either works correctly; the trade-off is
persistence-and-staleness vs simplicity.

### O67 default keyword filter lists

Backlog row says ship default lists for slurs (default BLOCK),
NSFW text (default WARN), and graphic-violence text (default
WARN). Content sourcing is the open question:

  1. Are you OK with English-only at first launch (with the
     l10n gap flagged honestly in onboarding copy)?
  2. Should the slur list draw from an existing curated list
     (e.g. Wiktionary's English profanity category, or a more
     considered source you have in mind) or do you want to
     curate it yourself?
  3. Should there be a default user-authored publisher
     associated with the bundled lists (signed by a Rumor
     project key), or are the defaults shipped unsigned as a
     special case?

### O79 Rooms routing

Two architectural questions before wiring MessageType.ROOM_*:

  1. **roomId visibility on the wire.** When a Room message
     is composed and broadcast, do non-subscribers see the
     roomId in clear (simplifies routing — relays know which
     subset of peers might care) or via an O53-style tag
     derived from the room key (better privacy — observers
     can't enumerate roomIds; relays must trial-match against
     per-room keys they hold)?

  2. **Membership state source-of-truth.** Is current
     membership derivable from the gossiped events stream
     (RoomCreate + cumulative invites/joins/leaves/bans) or
     does it need a separate persisted membership table per
     Room? The derivable approach is simpler but reconciling
     a long-offline node's view is harder; the persisted
     approach is more straightforward to query but introduces
     a state-divergence risk across peers.

### LICENSE

Still pending. GPL-3.0-or-later vs AGPL-3.0 — your call.
F-Droid submission checklist is otherwise green.
