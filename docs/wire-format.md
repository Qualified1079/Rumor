# Rumor wire format v1

This document is the authoritative description of every byte that crosses a Rumor wire. Re-implementers (non-JVM relay nodes per O54, iOS port per O63, MCU relays per O75) target this spec. If the code in `core/` and this document disagree, **this document is what should be edited to match the code, not the other way around** — bumping a domain-tag version is what marks a real change.

Domain-tag inventory at the bottom; bookmark it. Any new signature site MUST add a new tag here before going on the wire.

Status: **DRAFT**, mostly extracted from the existing Kotlin sources. Reviewers wanted: someone who can compare against a second implementation (Bitchat, Briar, Meshtastic) for byte-level disagreements.

---

## 0. Two layers (O65)

The Rumor wire has two cleanly separated layers. **Do not collapse them.**

- **`RumorMessage`** — the signed content unit. What dedup keys on, what gets stored, what gets relayed across multiple hops. One stable schema; evolves additively via the reserved `_ext` map and new `MessageType` enumerants.
- **`GossipPacket`** — the session-control frame. What crosses a single hop between two peers during a gossip exchange. Subtypes for handshake (HELLO/HelloProof), summary (Bloom/IdList/NeighborDigest/Rbsr), exchange (Request/Message/Ack), and teardown (OnlineStatus/Bye). Some `GossipPacket` subtypes carry zero `RumorMessage`s (e.g. HELLO), some carry one (Message), some carry many (future batched MessageBatch — not yet implemented).

**The rule for which layer a new feature lives in:**
- Content evolution (new payload kind, new field that must survive multi-hop relay) → `RumorMessage._ext` and/or new `MessageType` enum value.
- Transport evolution (new session-control frame, new summary algorithm, new framing) → new `GossipPacket` subtype, capability-gated via HELLO `supportedFeatures`.

Inventing a `MessageType` for what's really a transport-control frame (or a `GossipPacket` subtype for what's really persistent content) is the trap. RBSR is the canonical example of the right pattern: it's a transport optimisation, so it became `GossipPacket.Rbsr` capability-gated on `supportedFeatures: ["rbsr-v1"]`, not a `MessageType.RBSR`.

---

## 1. Framing and serialisation

### 1.1 Transport framing

Each `GossipPacket` is serialised to JSON (UTF-8), prefixed by a **4-byte big-endian unsigned length**, and written to the underlying byte stream. The receiver reads 4 bytes, parses them as an int, reads exactly that many bytes, and deserialises as a `GossipPacket`. The sequence on the wire is:

```
[4B length][UTF-8 JSON][4B length][UTF-8 JSON]…[4B length][UTF-8 JSON for Bye]
```

The framing is the same regardless of the underlying transport (Wi-Fi Direct TCP, BLE L2CAP, mDNS TCP, future TransportPlugin tunnels). A re-implementer only needs to provide a byte-pair channel.

**Hard limits, not yet enforced:**
- Max frame size: TBD. A re-implementer SHOULD reject any frame larger than 1 MiB to bound memory. This needs a normative number recorded here; the JVM impl currently does not cap and relies on JSON parser bounds.
- Per-session frame count: no cap currently; sessions naturally bound to a few dozen frames by the protocol shape (HELLO, HelloProof, summary, request, message×N, ack, online_status, bye).

### 1.2 JSON config (`WireJson`)

All wire I/O routes through one configured `kotlinx.serialization.json.Json` instance:

```kotlin
Json {
    ignoreUnknownKeys = true       // forward-compat: v0.1 ignores v0.2 fields
    encodeDefaults = true           // back-compat: v0.1 always emits documented fields
    explicitNulls = false           // shrink: null fields are omitted
    classDiscriminator = "type"     // sealed-class discriminator field
}
```

**Implication for re-implementers:**
- Your parser MUST tolerate unknown top-level fields without erroring. Future versions add fields; older nodes ignore them.
- Your serialiser MUST emit every documented field even at default value. A v0.2 receiver shouldn't have to guess whether a missing field means "default" or "not supported."
- Optional fields with value `null` are omitted from output; presence implies a real value. Treat absent and `null` as equivalent at parse time.
- Sealed-class subtype dispatch uses the JSON field `"type"`, not `"kind"` or other names. Subtype tag values are documented per-subtype below.

### 1.3 Forward-compat: `_ext`

Every top-level wire object (`GossipPacket.*`, `RumorMessage`, `Blocklist`, `BlocklistDiff`, `BridgeVouchedPayload`, `SelfPresencePayload`, `RbsrFrameWire.*`, `TransferMetadata`, `Chunk`, `ChunkRequest`) reserves a field:

```jsonc
"_ext": { "<key>": <any JSON> } | null
```

- v0.1 nodes parse `_ext` as opaque (`Map<String, JsonElement>?`) and **preserve it bit-exact through any copy/relay**. The JVM impl preserves it because `data class.copy()` carries unknown map keys through unchanged.
- v0.2+ nodes write or read named keys inside `_ext` for additive fields without bumping `protocolVersion`.
- **`_ext` is NOT covered by any signature.** Anything placed inside it must either self-authenticate (carry its own signature) or be non-security-affecting routing/diagnostic data. Adding a security-relevant field to `_ext` is a bug.

Currently in use (see `RumorMessage.routedHops` / `floodedHops`): O32 split-TTL counters `routedHops` and `floodedHops`. These are non-security routing metadata.

---

## 2. `GossipPacket` — session-control frames

Session lifecycle (happy path, see `app/.../wifidirect/GossipSession.kt`):

```
HELLO        →  ←  HELLO
                  (each side computes peer's helloChallengeBytes)
HELLO_PROOF  →  ←  HELLO_PROOF
                  (verify outer signature; abort on mismatch)
NEIGHBOR_DIG →  ←  NEIGHBOR_DIGEST       (overlap computation)
RBSR×N       →  ←  RBSR×N                (if rbsr-v1 negotiated)
  OR
BLOOM/IDLIST →  ←  BLOOM/IDLIST          (legacy summary)
REQUEST      →  ←  REQUEST
MESSAGE×N    →  ←  MESSAGE×N
ACK          →  ←  ACK
ONLINE_STATUS→  ←  ONLINE_STATUS
BYE          →  ←  BYE
```

Either side may send `BYE` at any time to terminate. A frame received after the local side has sent `BYE` is ignored, not errored.

### 2.1 HELLO (`type: "hello"`)

```json
{
  "type": "hello",
  "userId": "<hex>",
  "publicKey": "<base64 32-byte Ed25519 public key>",
  "nonce": "<base64 random bytes, ≥16>",
  "protocolVersion": 1,
  "maxProtocolVersion": 1,
  "supportedFeatures": ["rbsr-v1", "compression-v1", …],
  "_ext": null
}
```

- `userId` = `SHA-256(publicKey).toHex()`. Receiver MUST recompute and verify on receipt; mismatch ⇒ drop session. **This is the only userId↔key binding; never trust an asserted userId without recomputing.**
- `nonce` is random per-session; the peer signs it (plus the version bits, see below) in their HELLO_PROOF.
- Session protocol version = `min(local.maxProtocolVersion, remote.maxProtocolVersion)`.
- `supportedFeatures` is a string list of capability tags. **Order is not significant on the wire, but the canonical form for signing sorts ASCII.** Re-implementers MUST sort before signing the HELLO_PROOF challenge.

### 2.2 HELLO_PROOF (`type: "hello_proof"`)

```json
{
  "type": "hello_proof",
  "signature": "<base64 Ed25519 signature>",
  "_ext": null
}
```

- `signature = Ed25519(peer.publicKey, peer_private_key)` over the bytes:

  ```
  "rumor-hello-v1:" || nonceBase64 || "|" || protocolVersion || "|" || maxProtocolVersion || "|" || sortedSupportedFeatures.join(",")
  ```

  where `nonceBase64` is the receiver's own HELLO `nonce`, `protocolVersion` and `maxProtocolVersion` are the receiver's own values, and `sortedSupportedFeatures` is the receiver's own `supportedFeatures` ASCII-sorted and comma-joined (no surrounding whitespace).

- The version bits and feature list are inside the signed payload specifically so a downgrade-MITM cannot strip features or downgrade versions without invalidating the proof (TLS 1.3 lesson).
- Receiver verifies; on failure, session ends.

### 2.3 BLOOM (`type: "bloom"`)

Legacy summary phase, replaced by RBSR when both peers advertise `rbsr-v1`.

```json
{
  "type": "bloom",
  "filter": "<base64-encoded BloomFilterData bytes>",
  "expectedItems": 1500,
  "_ext": null
}
```

- `filter` is the serialised `BloomFilterData` (see `core/.../transport/wifidirect/BloomFilterData.kt`). Encoding is bit-array + count + parameters, hash family is double-hashing with SHA-256 base.
- `expectedItems` is the configured `n` used to size the filter. Receiver SHOULD bound this (O13: a malicious peer can send a huge `expectedItems` and force a multi-GB allocation; the right fix is graceful degradation — see backlog).
- **Re-implementer note:** the bloom format is an implementation detail of the legacy path; RBSR is the path going forward. If your implementation doesn't carry legacy, advertise only `rbsr-v1` and refuse sessions where the peer doesn't.

### 2.4 ID_LIST (`type: "id_list"`)

```json
{
  "type": "id_list",
  "ids": ["<id>", "<id>", …],
  "_ext": null
}
```

Used in place of `BLOOM` when the known-set is small enough (configurable threshold ~50 ids) that exact membership beats bloom on byte count. IDs are the same hex strings as `RumorMessage.id`. Receiver handles either form.

### 2.5 REQUEST (`type: "request"`)

```json
{
  "type": "request",
  "messageIds": ["<id>", …],
  "_ext": null
}
```

The sender of `REQUEST` is asking the peer for the specific listed messages. In practice the receiver also sends back messages they think the asker is missing based on the bloom/RBSR exchange — `messageIds` is a single-direction explicit ask used after the summary phase resolved.

### 2.6 MESSAGE (`type: "message"`)

```json
{
  "type": "message",
  "message": { /* RumorMessage — see §3 */ },
  "_ext": null
}
```

One `MESSAGE` frame per `RumorMessage`. The MESSAGE phase emits many of these in a row.

### 2.7 ACK (`type: "ack"`)

```json
{
  "type": "ack",
  "acceptedIds": ["<id>", …],
  "_ext": null
}
```

Confirms peer-hop delivery only. `acceptedIds` lists the IDs the receiver actually ingested in this session — messages they passed signature verification and weren't already known. Used for routing-quality stats (`bytesRelayed`, success/failure scoring per O3), not for end-to-end delivery semantics.

### 2.8 NEIGHBOR_DIGEST (`type: "neighbor_digest"`)

```json
{
  "type": "neighbor_digest",
  "filter": "<base64 bloom bytes>",
  "expectedItems": 800,
  "_ext": null
}
```

A separate bloom from the summary-phase one. Carries this node's "known message IDs in the recent window" so the peer can compute overlap (what fraction of my known messages does the peer also know). Drives peer-aware batch shaping per P2 (well-informed peers get freshest-only batches; novel peers get the full set).

Same format and OOM caveat as `BLOOM`.

### 2.9 RBSR (`type: "rbsr"`)

```json
{
  "type": "rbsr",
  "frames": [
    { "type": "skip", "loTs": 0, "loId": "", "hiTs": 1700000000000, "hiId": "abc…", "_ext": null },
    { "type": "fp",   "loTs": 1700000000000, "loId": "abc…", "hiTs": 1700050000000, "hiId": "def…",
      "fp": "<base64 32-byte XOR fingerprint>", "_ext": null },
    { "type": "ids",  "loTs": 1700050000000, "loId": "def…", "hiTs": 9223372036854775807, "hiId": "",
      "ids": ["<id>", …], "_ext": null }
  ],
  "_ext": null
}
```

- Capability-gated: both peers MUST advertise `"rbsr-v1"` in HELLO `supportedFeatures` for RBSR to be used. Otherwise the session falls back to BLOOM/IDLIST.
- Each frame is one range. Modes:
  - `"skip"` — peers agree on this range (or one peer is empty), no action needed.
  - `"fp"` — fingerprint comparison. If recipient's local fingerprint over the same range matches `fp`, recipient marks the range converged and emits a `skip` next round. If it doesn't match, recipient bisects the range and emits 16 smaller `fp` or `ids` frames next round.
  - `"ids"` — terminal mode; sender is asserting the full ID list for this range. Recipient diffs against own IDs in that range and notes missing-here / missing-there.
- Round limit: `MAX_RBSR_ROUNDS = 12` per `core/sync/Rbsr.kt`. With `bisectionFactor = 16` that's 16^12 leaf ranges — the cap is a recursion safety, not a budget. Receivers MUST bound iterations regardless.
- **Bound encoding sentinels:**
  - Lower bound MIN: `loTs = Long.MIN_VALUE, loId = ""`
  - Upper bound MAX (open-ended): `hiTs = Long.MAX_VALUE, hiId = ""`
  - Otherwise both fields are real values from a `RumorMessage`.
- **Fingerprint formula:**
  ```
  fingerprint(range) = SHA-256( "rumor-rbsr-v1:" || msg.timestamp || ":" || msg.id ) XOR-reduced over msg ∈ range, then truncated to 32 bytes
  ```
  See `core/sync/Rbsr.kt`. **Audit needed (O42 promote-to-default gate):** confirm this matches the hoytech reference, which uses `SHA-256( sum_mod_2^256(IDs) || varint(count) )` — these are NOT the same construction. The Rumor variant uses domain-tagged per-item SHA-256 then XOR; the hoytech reference sums integers mod 2^256 then hashes. Both are commutative and associative, but they're different formulas and will not interop with a Negentropy/NIP-77 implementation. Decision needed: align with NIP-77 (gives interop with strfry / Nostr ecosystem) or keep our variant (no behavioral difference at our scale). **See `docs/RESEARCH_NOTES.md` §1.**

### 2.10 ONLINE_STATUS (`type: "online_status"`)

```json
{
  "type": "online_status",
  "recentUsers": {"<userId>": 1700000000000, "<userId>": 1700000005000},
  "_ext": null
}
```

Map of userId → wall-clock epoch ms when last seen. Granularity is "online within last N minutes." Devices use their own RTC; the receiver clamps stale entries.

`sentAtMs` audit applies (O64): receivers should not gate any protocol behaviour on these timestamps; they are a UX hint only.

### 2.11 BYE (`type: "bye"`)

```json
{
  "type": "bye",
  "reason": "",
  "_ext": null
}
```

Graceful disconnect. After sending BYE, sender SHOULD close the underlying transport. Receiver of BYE drops the session cleanly.

---

## 3. `RumorMessage` — content frames

The unit that gets signed, stored, and relayed across hops.

```json
{
  "id": "<hex 128-bit random>",
  "senderId": "<hex SHA-256 of senderPublicKey>",
  "senderPublicKey": "<base64 32-byte Ed25519 public key>",
  "sequenceNumber": 12345,
  "sentAtMs": 1700000000000,
  "type": "broadcast",
  "hopsToLive": 5,
  "payload": { "contentType": "text", "content": "hello", "filename": null, "mimeType": null, "sizeBytes": 5 },
  "encryptedPayload": null,
  "recipientId": null,
  "signature": "<base64 Ed25519 signature>",
  "_ext": null
}
```

### 3.1 Required fields

| Field | Type | Notes |
|---|---|---|
| `id` | hex string | 128-bit random, hex-encoded, lowercase. Dedup key. |
| `senderId` | hex string | `SHA-256(senderPublicKey).toHex()`. Receiver MUST recompute. |
| `senderPublicKey` | base64 | Raw 32-byte Ed25519 public key. |
| `sequenceNumber` | int64 | Per-sender monotonic. Ordering within same sender. |
| `sentAtMs` | int64 | **Untrusted, sender-asserted, see O64.** Receivers MUST NOT gate protocol behaviour on this; use `receivedAtMs` (derived locally) for ordering and `displayTimeMs = min(sentAtMs, receivedAtMs)` for UI. |
| `type` | enum | See §3.3. |
| `hopsToLive` | int | Decremented at each relay. Hard ceiling `MAX_TOTAL_HOPS = 30` (O32). |
| `signature` | base64 | Outer Ed25519 signature, see §3.2. |

### 3.2 Signature (canonical bytes)

```
"rumor-msg-v1:" || id || senderId || senderPublicKey || sequenceNumber || sentAtMs || type.name || (payload?.content ?: "") || (encryptedPayload ?: "") || (recipientId ?: "")
```

All values appended as UTF-8 string representations (numbers as decimal, types as the enum constant name e.g. `"BROADCAST"`).

Signed bytes deliberately exclude:
- `hopsToLive` — relays decrement it, so if it were signed the message would invalidate after one hop.
- `signature` — chicken-and-egg.
- `_ext` — forward-compat carrier, intentionally unsigned (anything inside it must self-authenticate).

**Bump the `v1` only when the canonical byte layout itself changes** (field order, encoding, new field that becomes mandatory). Adding fields inside `_ext` does NOT require a bump.

### 3.3 `MessageType` enum

| Wire value | Semantics |
|---|---|
| `"broadcast"` | Flood-routed content. `payload` is plaintext; `recipientId` absent. |
| `"direct"` | DM. `encryptedPayload` present; `recipientId` present; `payload` absent. AES-256-GCM ciphertext under X25519-derived key. |
| `"ping"` | Liveness probe. |
| `"pong"` | Liveness response. |
| `"transfer_metadata"` | Chunked-transfer header (sizes, hashes, chunk count). |
| `"chunk"` | One chunk of a chunked transfer. |
| `"chunk_request"` | Retransmit-request for missing chunks. Routed as DM. |
| `"blocklist_publish"` | Signed full blocklist snapshot. Broadcast through mesh. |
| `"blocklist_diff"` | Signed incremental blocklist diff. Broadcast through mesh. |
| `"priority_link_request"` | Request a persistent-priority connection. Routed as DM. |
| `"priority_link_accept"` | Accept a priority-link request. Routed as DM. |
| `"self_presence"` | O30/O57 self-presence beacon. See §4.2. |
| `"bridge_vouched"` | O17 bridge-vouched cross-network content. See §4.3. |
| `"transfer_cancel"` | O18 receiver-cancel for in-flight chunked transfer. Routed as DM. |
| `"message_delete"` | O40 signed delete-on-ACK request. See §4.5. |
| `"keyword_filter_publish"` | O67 signed keyword-filter list snapshot. See §4.6. |
| `"prekey_publish"` | O38 signed short-lived recipient prekey broadcast. See §4.7. |
| `"room_message"` | O79 room-addressed message. Routing tag in `_ext.rt`; payload is plaintext (OPEN) or a JSON-serialised `MultiRecipientEnvelope` in `encryptedPayload` (ENCRYPTED). See §4.8. |

### 3.4 `payload` vs `encryptedPayload`

- `BROADCAST`: `payload` set, `encryptedPayload = null`.
- `DIRECT`: `encryptedPayload` set (base64 AES-256-GCM ciphertext), `payload = null`, `recipientId` set.
- All other types: payload is type-specific (often a JSON-encoded `MessagePayload` with `contentType = CONTROL`).

### 3.5 `TrustLevel` (NOT on the wire)

`TrustLevel` is a per-hop receiver-decided value:
- `VERIFIED` — outer signature checked against `senderPublicKey`.
- `BRIDGE_VOUCHED` — outer signature is by a bridge node's key; payload is foreign-network content.
- `BRIDGED` — message came from a local bridge plugin via `MessageSource.LOCAL_BRIDGE`. **Never re-relayed.**

It is intentionally not `@Serializable` and never crosses the wire. A peer cannot assert its own trust level.

---

## 4. Sub-payload types

These are JSON-serialised into the `MessagePayload.content` string of a `RumorMessage` with `contentType = CONTROL`. Receivers parse the content as the matching sub-type after dispatching on `MessageType`.

### 4.1 Blocklist

`MessageType.BLOCKLIST_PUBLISH`:

```json
{
  "publisherId": "<userId>",
  "version": 42,
  "entries": ["<blockedUserId>", …],
  "signature": "<base64 Ed25519 by publisher>",
  "_ext": null
}
```

Signature bytes:

```
"rumor-blocklist-v1:" || publisherId || "|" || version || "|" || entries.sortedAscii.joinWith(',', trailingComma=true)
```

`MessageType.BLOCKLIST_DIFF`:

```json
{
  "publisherId": "<userId>",
  "fromVersion": 42,
  "toVersion": 43,
  "added": ["<userId>", …],
  "removed": ["<userId>", …],
  "signature": "<base64 Ed25519 by publisher>",
  "_ext": null
}
```

Signature bytes:

```
"rumor-blocklist-diff-v1:" || publisherId || "|" || fromVersion || "->" || toVersion || "|+" || added.sortedAscii.joinWith(',', trailingComma=true) || "|-" || removed.sortedAscii.joinWith(',', trailingComma=true)
```

Subscribers MUST verify the signature using `publisherId`'s known public key (TOFU at first subscribe). Diffs are only applied when `fromVersion` matches the subscriber's currently-applied version.

### 4.2 Self-presence (O30/O57)

`MessageType.SELF_PRESENCE`:

```json
{
  "mode": "MOBILE" | "STATIC" | "FREE",
  "authorizedAtMs": 1700000000000,
  "recentlyExchangedWith": ["<userId>", …],
  "_ext": null
}
```

- Outer `RumorMessage.signature` provides authenticity (sender vouches for own mode).
- `recentlyExchangedWith` is the O31 route-advertisement list. Limit ~20 entries; sender's choice to opt-in per contact.
- INFRASTRUCTURE traffic class, short TTL (≤ `MAX_BROADCAST_HOPS`).

### 4.3 Bridge-vouched (O17)

`MessageType.BRIDGE_VOUCHED`:

```json
{
  "originNetwork": "meshtastic" | "meshcore" | "<other>",
  "originSenderId": "<network-specific>",
  "originSignatureIfAny": "<base64> | null",
  "payload": "<opaque base64 bytes from origin network>",
  "_ext": null
}
```

Outer `RumorMessage.signature` is by the **bridge's** Rumor Ed25519 key. The bridge vouches for delivery only, NOT for content authenticity. Trust level on receipt is `BRIDGE_VOUCHED` (not `VERIFIED`); display layer renders distinctly (O47).

Per-bridge trust toggles (receivers decide which bridges they accept vouching from) are a receiver-local policy, not on the wire.

### 4.4 Chunked transfer (O16, O18)

`TransferMetadata` (sent first, then chunks):

```json
{
  "transferId": "<uuid>",
  "filename": "name.bin",
  "mimeType": "application/octet-stream",
  "sizeBytes": 1048576,
  "chunkCount": 64,
  "chunkSizeBytes": 16384,
  "contentHash": "<hex SHA-256 of original bytes>",
  "_ext": null
}
```

`Chunk` (one per `MessageType.CHUNK`):

```json
{
  "transferId": "<uuid>",
  "index": 0,
  "data": "<base64 chunk bytes>",
  "_ext": null
}
```

`ChunkRequest` (NACK):

```json
{
  "transferId": "<uuid>",
  "missingIndices": [3, 7, 12],
  "_ext": null
}
```

`TransferCancel` (O18):

```json
{
  "transferId": "<uuid>",
  "_ext": null
}
```

All four are wrapped in `MessagePayload(contentType = CONTROL, content = WireJson.encodeToString(…))`. The `RumorMessage.signature` covers the JSON-encoded content string verbatim.

### 4.5 Message delete (O40)

Signed delete-on-ACK request. `payload` is `MessagePayload(CONTROL, content = WireJson.encodeToString(MessageDeletePayload(messageId, issuerPublicKey, signature)))`.

```json
{
  "messageId": "<id of the target message>",
  "issuerPublicKey": "<base64 of issuer's Ed25519 pub>",
  "signature": "<base64 of Ed25519 sig over messageDeleteSignableBytes>"
}
```

Domain-tagged signable bytes prefix: `"rumor-message-delete-v1:"`. Receivers verify (a) `issuerPublicKey` hashes to either the target message's `senderId` or `recipientId`, (b) signature verifies over the canonical bytes. Honoring relays purge the targeted message; deleted id stays in the dedup set to prevent re-ingestion.

### 4.6 Keyword filter publish (O67)

Signed keyword-filter list snapshot. `payload` is `MessagePayload(CONTROL, content = WireJson.encodeToString(KeywordFilterList(…)))`.

Domain-tagged signable bytes prefix: `"rumor-keyword-filter-v1:"`. Subscribers verify the publisher's signature, apply monotonic-version check, store. Display-layer filter only — relay path is unaffected (see "relay never sees blocklist" architectural rule).

See `core/model/KeywordFilter.kt` for the full data shape (`FilterEntry`, `FilterAction.{WARN,BLOCK}`, `MatchKind.{SUBSTRING_CI,SUBSTRING_CS,WORD_CI}`, optional `userIdAllowlist`).

### 4.7 Prekey publish (O38)

Signed short-lived recipient prekey broadcast. Sender publishes prekeys for receiver-side forward secrecy; future DM senders DH against the freshest valid prekey instead of the long-term static.

```json
{
  "publisherId": "<userId — should hash from publisherPublicKey>",
  "publisherPublicKey": "<base64 of publisher's Ed25519 pub>",
  "prekeyPublic": "<base64 of fresh X25519 pubkey>",
  "validFromMs": <epoch ms>,
  "validToMs": <epoch ms>,
  "signature": "<base64 of Ed25519 sig over prekeyPublishSignableBytes>"
}
```

Domain-tagged signable bytes prefix: `"rumor-prekey-v1:"`. Field order in the canonical signable bytes binds publisherId + publisherPublicKey + prekeyPublic + validity window — a relay extending the window, swapping the prekey, or substituting another publisher's identity all break the sig.

### 4.8 Room message (O79)

Room-addressed message. Two modes:

**OPEN room:** `payload = MessagePayload(TEXT, plaintext)`. `encryptedPayload = null`. No content encryption. `_ext.rt = base64(RoomRoutingTag.openRoomTag(roomId))` — a 16-byte tag derived from the public roomId. Observers who don't enumerate roomIds see only the tag.

**ENCRYPTED room (INVITE / CLOSED / PASSWORD):** `payload = null`. `encryptedPayload` is `WireJson.encodeToString(MultiRecipientEnvelope(...))`. `_ext.rt = base64(RoomRoutingTag.encryptedRoomTag(routingKey, messageId))` — a per-message HMAC, derived from a per-room routing key shared at invite/join time. Observers without the routing key can't compute the tag at all.

The full byte-level cryptographic operations (multi-recipient envelope construction + decryption; HKDF-derived per-recipient wrap keys; domain tags for routing tag derivation, wrap-key info, envelope signature scope) are specified in `docs/O79_PROTOCOL_SPEC.md`. The threat-model context (what each layer defends + what it cannot) is in `docs/ROOMS_THREAT_MODEL.md`.

Domain tags reserved (forever):
- `"rumor-room-route-v1:"` — OPEN routing tag SHA-256 prefix
- `"rumor-room-msg-tag-v1:"` — ENCRYPTED routing tag HMAC prefix
- `"rumor-room-routing-key-v1"` — HKDF info for per-room routing-key derivation
- `"rumor-room-envelope-v1:"` — MultiRecipientEnvelope outer Ed25519 sig scope
- `"rumor-room-wrap-v1:"` — per-recipient wrap-key HKDF info prefix

---

## 5. Capability negotiation

The HELLO `supportedFeatures` list is the additive-feature-detection channel. Currently allocated tags:

| Tag | Meaning | Status |
|---|---|---|
| `"rbsr-v1"` | Negentropy-style RBSR summary phase (G10/O42). | **Opt-in per session** (`LOCAL_SUPPORTED_FEATURES = emptyList()` in production until O42 promote-to-default gate passes — see CLAUDE.md G17). |

**Reserved (planned, not yet on the wire):**

- `"compression-v1"` — O76 6-bucket compression + padding for text payloads.
- `"sealed-sender-v1"` — O53 recipient-derivable delivery tag.
- `"prekey-v1"` — O38 receiver-side forward secrecy via rotating prekeys.

A new capability tag MUST be added to this table before it goes on the wire. Old nodes seeing an unknown tag MUST ignore it silently.

---

## 6. Domain tag inventory

Every signature site has a unique domain-tagged prefix so a signature produced in one context cannot be replayed in another. Re-implementers MUST use the exact byte-string prefix shown.

| Tag | Signs what | Site |
|---|---|---|
| `"rumor-hello-v1:"` | HELLO challenge: nonce + version bits + sorted features | `helloChallengeBytes` |
| `"rumor-msg-v1:"` | Outer `RumorMessage` signature | `MessageStore.signableBytes` |
| `"rumor-blocklist-v1:"` | Full blocklist snapshot | `blocklistSignableBytes` |
| `"rumor-blocklist-diff-v1:"` | Blocklist diff | `blocklistDiffSignableBytes` |
| `"rumor-identity-rotation-v1:"` | Continuity-signature on key rotation | `identityRotationSignableBytes` |
| `"rumor-bridge-vouched-v1:"` | Bridge-vouched outer (currently same key as outer msg sig — verify) | `BridgeVouched.kt` |
| `"rumor-rbsr-v1:"` | Per-item input to the RBSR XOR fingerprint | `Rbsr.kt` |

**Bumping a tag is the canonical marker that a real wire-format change occurred.** Bumps require:
1. Recording the old tag in `docs/RENAMED_FIELDS_NEVER_REUSE.md` so it is never re-used.
2. A new capability tag in HELLO `supportedFeatures` so peers can negotiate.
3. A transition window where both old and new are honoured by receivers.

---

## 7. Open audit items

Tracked so this doc is honest about what hasn't been verified.

- **RBSR fingerprint formula vs hoytech reference** — see §2.9 and `docs/RESEARCH_NOTES.md` §1. Likely a real divergence; decision needed before O42 promote-to-default-on.
- **Max frame size** — no normative cap. Suggest 1 MiB for re-implementers; record this in the next revision once a number is agreed.
- **BloomFilterData exact byte layout** — currently an implementation detail. If anyone reimplements the legacy summary path, the byte layout needs documenting here. Alternative: deprecate the legacy path entirely once `rbsr-v1` is default-on.
- **`sentAtMs` skew tolerance** — O64 calls for ingest-time clamp at `now + SKEW_TOLERANCE` (~5 min). Not yet implemented; record the value here once decided.
- **Sub-payload `_ext` interaction with signing** — the `RumorMessage.signature` covers the verbatim JSON string of the inner payload. If a sub-payload has its own `_ext`, that gets included in the signed bytes (because it's part of `payload?.content`). Worth a re-read pass to confirm this is intended for every sub-payload type.
