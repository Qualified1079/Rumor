# O48 audit — bridge synthetic userId from pubkey

CLAUDE.md O48 requires bridge synthetic userIds to be derived from `<bridgePrefix>:<hash-of-radio-asserted-pubkey>` so that key-substitution attacks on bridged contacts can be detected by pubkey churn (vs the current name-collision-loud failure mode).

## Current state

**MeshCore (`MeshCoreBridge.kt:147`):**
```kotlin
val syntheticUserId = "meshcore:" + nameHash(msg.senderName)
```
where `nameHash` is FNV-1a 32-bit of the UTF-8 display name. **Spoofable** — anyone naming themselves "Bob" on a MeshCore channel becomes the same Rumor userId.

**Meshtastic (`MeshtasticBridge.kt:125`):**
```kotlin
val syntheticUserId = "meshtastic:" + (packet.from.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
```
where `packet.from` is the 32-bit Meshtastic node number. **Better but not pubkey-bound** — Meshtastic node numbers are historically derived from the lower bytes of the radio's BLE MAC, not from the PKC pubkey; multiple nodes can share a node number (it's a 32-bit space with no enforcement).

## What O48 actually requires per-bridge

### MeshCore

The MeshCore channel-message frame layout per CLAUDE.md S2 is:
```
[opcode][snr][res×2][channel][path_len][txt_type][ts×4][sender ": " text]
```
**No pubkey field on the channel-message wire.** Sender pubkeys live in the `CONTACTS_START` sequence — separate opcode, a contact directory the radio publishes.

**Implementation needed for O48 on MeshCore:**
1. Subscribe to `CONTACTS_START` / contact-record frames in the bridge plugin.
2. Maintain an in-memory keyring `Map<senderName, pubkey>` populated from those frames.
3. On channel-message receive, look up the pubkey for `msg.senderName` in the keyring.
4. Synthetic userId becomes `"meshcore:" + sha256(pubkey).take(16).toHex()` (16 hex chars = 64 bits — enough collision-resistance for the bridge namespace, short enough for display).
5. **If a name has no keyring entry**, fall back to either (a) drop the message until contacts have been received, or (b) use the current `nameHash` fallback with a warning. Lean (b) — dropping breaks bridges that haven't yet received a `CONTACTS_START` snapshot.
6. Detect & surface key churn: if a previously-seen `senderName` arrives in a future `CONTACTS_START` with a different pubkey, log + surface a UI "key changed" event (analogous to O21 display-name pinning, generalised to pubkey pinning under the synthetic prefix).

**I cannot implement step 1-2 without seeing the contact-frame format.** CLAUDE.md S2 documents the channel-message frame but not contacts. Need to either (a) read meshcore-open BLE_PROTOCOL.md fresh, or (b) read the existing `MeshCoreFrames.kt` to see what's already decoded.

### Meshtastic

Meshtastic's wire format is protobuf. The `User` message (broadcast as `NODEINFO_APP` port 4) carries `public_key bytes` since around v2.5 of the protobufs. We already decode `MeshPacket` per CLAUDE.md S1; need to also decode the periodic NODEINFO broadcasts and maintain a `Map<nodeNum, pubkey>`.

**Implementation needed for O48 on Meshtastic:**
1. Add NODEINFO_APP handler in `MeshtasticBridge.processInbound` that decodes the `User` message and pulls `public_key`.
2. Maintain `Map<nodeNum, pubkey>` in the bridge.
3. On text-message receive, look up `packet.from` → pubkey → derive userId as `"meshtastic:" + sha256(pubkey).take(16).toHex()`.
4. Fallback for un-keyringed senders: keep current `nodenum-hex` form.
5. Same key-churn detection as MeshCore.

**Key encoding question:** Meshtastic's `public_key` field is the X25519 PKC key, not Ed25519 — bridges per the design table use X25519-derived ECDH for content. We hash the bytes regardless; encryption layer concerns are orthogonal.

## Status quo risk surface

The current state is **vulnerable to silent identity substitution** for both bridges:
- A malicious MeshCore device adopting another user's display name becomes that user's Rumor userId. Receiver-side blocklist entries for "meshcore:<oldHash>" no longer match.
- A Meshtastic node spoofing `packet.from` (trivial — `from` is in the cleartext header) becomes any other node's Rumor userId.

The current note in `MeshCoreBridge.kt:147-152` acknowledges this: "Doesn't need to be cryptographic — collisions just mean two MeshCore users with similar names share a Rumor sender ID." That comment is wrong about the threat model — it's not just collisions, it's deliberate impersonation.

## Decisions needed from user before implementation

1. **OK to read the meshcore-open BLE protocol doc fresh** to get the contact-frame format? (Yes, almost certainly — but recording the question because I'm about to commit code based on docs I haven't verified.)
2. **OK to drop channel messages from un-keyringed senders, vs fall back to name-hash?** Lean: fall-back-with-warning. Doesn't change behaviour for users who don't have contacts yet; tightens it gradually as keyrings populate.
3. **Userid byte length: 16 hex chars (64 bits) sufficient?** Rumor's native userIds are 64 hex chars (256-bit SHA-256). 16 hex chars in the bridge namespace gives a 1-in-2^64 collision per bridge — easily good enough; matches the design pattern Signal uses for "safety numbers" cut to a displayable length. Could go larger.
4. **Migration: existing bridged contacts have name-hash userIds.** When O48 lands, those become orphan contacts (the new pubkey-hash userId won't match). Options:
   - **Hard cutover** — old bridged contacts disappear; users re-add. Cheap, breaks history.
   - **Migration mapping** — on first sight of a `CONTACTS_START` snapshot, compute both old (name-hash) and new (pubkey-hash) userIds for each contact; if the user has the old one as a contact, atomically rebind to the new (similar to O41 identity rotation). Expensive but preserves history.
   - **Pre-O5 (DM bridging) ship, hard cutover is fine** — no existing bridged contacts have DM history; they're all broadcast contacts.

Lean: hard cutover, ship before O5 DM bridging.

## Implementation effort

- **MeshCore**: ~50 lines in `MeshCoreBridge.kt` + new keyring state + contact-frame decoder additions to `MeshCoreFrames.kt`. 2–3 hours.
- **Meshtastic**: ~30 lines in `MeshtasticBridge.kt` + NODEINFO_APP handler + keyring state. 1–2 hours.
- **Both**: pubkey-hash helper extracted to `core/.../identity/Identity.kt` since this is the same SHA-256-prefix pattern used for native Rumor userIds.

**Decision-blocked**: O48 is small but lands real protocol decisions about bridge identity. Held back from this session per the "needs user input" rule. Concrete decisions in §"Decisions needed" above.
