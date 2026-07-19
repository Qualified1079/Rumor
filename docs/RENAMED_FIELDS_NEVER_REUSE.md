# Renamed and removed wire-format field names — never reuse

Once Rumor ships a wire-format version to phones that may never update (rural
users, disaster-cached APKs), every field name in that version is reserved
forever. Reusing a retired name for a new purpose silently corrupts data
between versions: a v0.1 client sees the new payload, deserializes it under
the old type, and acts on garbage. There is no error surface — the wire
parser succeeds and the bug lives downstream.

This file lists every JSON key name that has appeared in a published Rumor
wire format and has since been renamed or removed. **Adding new fields is
always fine; renaming or repurposing is not.** When a field is retired, add
its name here with the version that removed it and a one-line note on what
replaced it.

Policy summary (see `CLAUDE.md` O37 invariant 6):

1. **Additive only.** New fields go inside the reserved `_ext` map on each
   wire object until the next `protocolVersion` bump. Do not add new
   top-level fields between version bumps.
2. **Append-only field order in canonical-byte signature scopes.** Reordering
   fields inside `signableBytes` / `helloChallengeBytes` /
   `blocklistSignableBytes` / `blocklistDiffSignableBytes` breaks every
   pre-existing signature. Adding fields at the end is OK *only* if the new
   field gets its own domain tag bump (`rumor-msg-v2:` etc.).
3. **Type changes are not renames — they are removals.** If `foo: Int`
   becomes `foo: String`, the old `foo` is retired and a new key (`fooStr`
   or similar) takes its place. List the retired key here.
4. **Deprecation window is at least two years** of parse-and-relay support
   for any retired version, because the design target population is exactly
   the users least likely to update.

---

## Reserved-forward names

Names reserved before a feature ships, so a future contributor can't
accidentally claim them for a different purpose. When the feature lands,
the reservation here is what guarantees the field name matches the
backlog row that justified it.

| Name | Reserved for | Notes |
|------|---------------|-------|
| `_ext.replyTo` (RumorMessage `_ext` field, `String`) | O90 thread metadata | Carries the parent messageId for thread-tree reconstruction in the UI. Unsigned (in `_ext`); local-display impact only. |
| `_ext.mentions` (RumorMessage `_ext` field, `List<String>`) | O90 mention metadata | Carries userIds explicitly mentioned by the sender for notification-feed + cross-room mention aggregators. Unsigned. |
| `_ext.rt` (RumorMessage `_ext` field, `String`) | O79 room routing tag | Carries the Base64-encoded 16-byte routing tag (`RoomRoutingTag.openRoomTag` for OPEN rooms, `RoomRoutingTag.encryptedRoomTag` for ENCRYPTED). Receivers consume via `RoomTagMatcher`. Unsigned — relay tampering only causes the message to be dropped at every honest peer, no useful attack. |
| `rumor-project-default-lists-v1` (project signing key kind-id) | O67 default-lists publisher | See `docs/PROJECT_KEYS.md` policy — key not generated yet. |
| `rumor-room-envelope-v1:` (signable-bytes domain tag) | O79 multi-recipient envelope sig scope | Set in `multiRecipientEnvelopeSignableBytes`. Bumping requires `-v2:` and forces every envelope signature scheme to be regenerated. |
| `rumor-room-wrap-v1:` (HKDF info prefix for per-recipient wrap keys) | O79 multi-recipient envelope wrap-key derivation | Used in `MultiRecipientEnvelopeCodec` as `info = "rumor-room-wrap-v1:" \|\| recipientId`. Domain-separates wrap keys from any other key derived from the same X25519 shared secret. |
| `rumor-room-route-v1:` (SHA-256 prefix for OPEN room routing tags) | O79 OPEN room routing | Used in `RoomRoutingTag.openRoomTag`. |
| `rumor-room-msg-tag-v1:` (HMAC prefix for ENCRYPTED room per-message routing tags) | O79 ENCRYPTED room routing | Used in `RoomRoutingTag.encryptedRoomTag`. |
| `rumor-room-routing-key-v1` (HKDF info string for per-room routing key derivation) | O79 ENCRYPTED room routing-key derivation | Used in `RoomRoutingTag.deriveEncryptedRoomRoutingKey`. Domain-separates the routing key from any future content-encryption key derived from the same room shared secret. |
| `rumor-dm-recipient-tag-v1:` (HKDF info prefix for per-contact tag key) | O53 sealed-sender per-contact tag-key derivation | Used in `SealedSenderKey.derive` with sorted userIds appended (`info = prefix + lo + "\|" + hi`) so Alice→Bob and Bob→Alice derive the same key. Bumping requires `-v2:` and re-derives every contact's cached tag key. |
| `rumor-room-posting-cert-v1:` (signable-bytes domain tag) | O89 posting cert sig scope | Set in `roomPostingCertSignableBytes`. Bumping requires `-v2:` and forces every issued cert to be re-signed. |
| `"room_posting_cert"` (SerialName for `RoomPostingCert`) | O89 | Wire payload type name. Reserved forever. |
| `_ext.t` (sealed-sender tag carrier on RumorMessage) | O53 | Base64-encoded 32-byte HMAC tag from `SealedSenderTag.tagFor`. Reserved forever — do NOT reuse this key for any other `_ext` payload. |
| `rumor-o98-net-v1:` (SHA-256 prefix for backbone group networkName) | O98 Phase 3b group credentials | Used in `GroupCredentials.forHost` — `networkName = "DIRECT-" + first 12 hex of SHA-256(tag + hostUserId)`. Both endpoints derive independently; changing the derivation partitions old/new builds' backbone joins. |
| `_ext.hlc` (RumorMessage `_ext` field, `String` `"<wallMs>:<counter>"`) | O95 Hybrid Logical Clock stamp | Stamped at compose (`HlcClock.tick`), folded on verified receive (`HlcClock.update`; state-poisoning bound 10y ahead of local + counter sanity ceiling — deliberately enormous so human-scale clock damage passes). Display sort is `(hlc, id)` with `displayTimeMs` fallback and the hlc wall clamped at `receivedAtMs + 48h` (the tight future-pinning defense lives at display, against local ground truth). Unsigned (in `_ext`) — relay tampering is display-order-only, same class as `_ext.replyTo`. Reserved forever. |
| `rumor-o98-psk-v2:` (SHA-256 prefix for backbone group passphrase) | O98 Phase 3b group credentials | Used in `GroupCredentials.passphraseFor` — `passphrase = first 16 hex of SHA-256(tag + networkName)`. Derived from the NETWORK NAME (not the host userId) so any node seeing the SSID in a Wi-Fi scan can join prompt-free — the bootstrap case has no beacons and no host userId. Deliberately derivable by any radio-range observer; NOT a secret — trust stays with HELLO Ed25519 (O51 posture). |

## Retired names

*(Empty as of v0.1. Add entries below as `| name | retired in | replaced by | notes |`.)*

| Name | Retired in | Replaced by | Notes |
|------|------------|-------------|-------|
| `"identity_rotation"` (MessageType serial name) | pre-v0.1 (no wire-format release) | (nothing) | Originally O41 — a signed announcement that the sender's userId/Ed25519 key migrated, with contacts auto-rebinding on receipt. Removed before any release because auto-rebind turns a stolen old key into permanent impersonation (attacker rotates to their own key, all contacts auto-rebind). Wire type name and the `rumor-identity-rotation-v1:` domain tag are reserved forever. Deletion-announcement use case moved to O69 (`IDENTITY_RETIRED`, no successor key, safe). |
| `rumor-identity-rotation-v1:` (domain tag) | pre-v0.1 | (nothing) | See `"identity_rotation"` above. |
| `ContactRepository.rebindIdentity()` (not on the wire but reserved for symmetry) | pre-v0.1 | (nothing) | Same — never re-add this method under this name. |
| `rumor-o98-psk-v1:` (SHA-256 prefix, passphrase from hostUserId) | 2026-07-17 (same day it shipped; test phones only) | `rumor-o98-psk-v2:` | v1 derived the passphrase from the host userId, which bootstrap nodes don't know (no beacons yet) — they fell back to plain connect() into the formed group, which is exactly the WPS invitation prompt the credentials exist to avoid. v2 derives from the networkName. Tag reserved forever. |
