# O79 — Rooms wire-format spec

Spec for the Rooms feature at the wire-format and cryptographic-
operation level. Any implementation that wants to interoperate with
a Rumor Rooms-capable build (including a future iOS port, Linux
relay node, or alternative client) must produce and consume the
exact byte sequences described here.

Cross-references the implementation in `:core/commonMain`:
- `core/model/Room.kt` — room-creation + invite + action wire types
- `core/model/MultiRecipientEnvelope.kt` — encrypted-room envelope
- `core/protocol/MultiRecipientEnvelopeCodec.kt` — encrypt + decrypt
- `core/protocol/RoomRoutingTag.kt` — routing tag derivation
- `core/protocol/RoomTagMatcher.kt` — receiver-side tag matching
- `core/data/RoomSubscriptionRepository.kt` — local subscription
  persistence contract

This doc is the source-of-truth byte layout. Tests in
`core/commonTest/.../protocol/RoomEndToEndIntegrationTest.kt` exercise
the entire stack against itself; a second implementation should pass
those tests against THIS one to confirm interop.

---

## Room modes

Two modes, surfaced as `RoomMembershipPolicy.{OPEN, INVITE, PASSWORD,
CLOSED}` at the room-creation layer but reducing to two
cryptographic patterns:

- **OPEN** — publicly readable. Wire carries plaintext content
  signed by the sender's Ed25519. No content encryption. Routing
  tag derived from public roomId (deterministic; anyone who knows
  the roomId can compute the tag).

- **ENCRYPTED** (INVITE / CLOSED / PASSWORD) — content visible only
  to addressed recipients. Wire carries a multi-recipient envelope:
  one AES-GCM-encrypted body + N small per-recipient key wraps.
  Routing tag derived from a per-room routing key shared at
  invite/join time (observers without the key can't compute the
  tag).

PASSWORD rooms are ENCRYPTED with the routing key + initial
membership material derivable from `Argon2id(password,
roomId-as-salt)` — outside the scope of this doc; the per-message
crypto follows the same multi-recipient envelope.

---

## Wire format

### Routing tag

16-byte opaque value placed in the `RumorMessage._ext` map under
the key `rt` as a Base64-encoded string. Reserved forever per
`RENAMED_FIELDS_NEVER_REUSE.md`.

**OPEN room tag derivation** — `RoomRoutingTag.openRoomTag(roomId)`:

```
tag = SHA-256("rumor-room-route-v1:" || roomId)[0:16]
```

Domain tag `rumor-room-route-v1:` is reserved forever. Truncating
SHA-256 to 16 bytes gives 128 bits of collision resistance — more
than enough for any plausible room count.

**ENCRYPTED room tag derivation** — `RoomRoutingTag.encryptedRoomTag(routingKey, messageId)`:

```
tag = HMAC-SHA-256(routingKey, "rumor-room-msg-tag-v1:" || messageId)[0:16]
```

Domain tag `rumor-room-msg-tag-v1:` is reserved forever. The
routing key is **per-room** (32 bytes); the messageId varies per
message, so the tag is **per-message** — a relay holding old
traffic can't cluster messages from the same Room across time.

**Per-room routing key derivation** — `RoomRoutingTag.deriveEncryptedRoomRoutingKey(seed)`:

```
routingKey = HKDF-SHA-256(
  salt = empty,
  ikm = seed,
  info = "rumor-room-routing-key-v1",
  length = 32,
)
```

The `seed` is a per-room shared secret distributed to members at
invite/join time. The HKDF info string domain-separates the
routing key from any other key that might later be derived from
the same seed (e.g. a content-encryption key for some future
PASSWORD-room mode).

### Multi-recipient envelope (ENCRYPTED rooms only)

JSON-serialized into the carrying `RumorMessage.encryptedPayload`
field. Schema:

```json
{
  "$type": "multi_recipient_envelope",
  "roomRoutingTag": "<base64 of the 16-byte tag>",
  "senderId": "<sender userId — SHA-256(senderPub).hex>",
  "senderPublicKey": "<base64 of sender's 32-byte Ed25519 pub>",
  "senderEphemeralPublic": "<base64 of fresh X25519 ephemeral pub>",
  "contentCiphertext": "<base64 of AES-256-GCM(body, contentKey, contentIv)>",
  "contentIv": "<base64 of 12-byte IV used above>",
  "keyWraps": [
    {
      "recipientId": "<recipient's userId>",
      "wrappedKey": "<base64 of AES-256-GCM(contentKey, wrapKey, wrapIv)>",
      "wrapIv": "<base64 of 12-byte IV used in the wrap>"
    },
    ...
  ],
  "signature": "<base64 of Ed25519 sig over signableBytes — see below>",
  "_ext": { ... optional forward-compat fields ... }
}
```

### Carrying RumorMessage

The room message rides as a regular `RumorMessage` with:

```
type = "room_message"
senderId, senderPublicKey, sequenceNumber, sentAtMs, hopsToLive,
signature (outer Ed25519 over standard RumorMessage signableBytes — the
  inner envelope sig is additionally there to bind the recipient list)
_ext.rt = "<base64 of 16-byte routing tag>"

For OPEN rooms:
  payload = MessagePayload(contentType=TEXT, content=<plaintext>)
  encryptedPayload = null

For ENCRYPTED rooms:
  payload = null
  encryptedPayload = "<JSON-serialized MultiRecipientEnvelope>"
```

Traffic class follows the standard content-driven rule
(BROADCAST/DIRECT/BRIDGE_VOUCHED branch): TEXT → REALTIME, media
contentTypes → BULK, CONTROL → INFRASTRUCTURE.

---

## Cryptographic operations

### ENCRYPTED room: sender (encrypt)

1. Generate a fresh 32-byte content key `K_content` from a
   cryptographically-strong PRNG.

2. Generate a fresh X25519 ephemeral keypair `(eph_priv, eph_pub)`
   — used once per envelope, then zeroed.

3. AES-256-GCM encrypt the plaintext body under `K_content` with
   a fresh 12-byte IV. `contentCiphertext` is `body || tag`
   (16-byte tag appended, the JCE / standard convention).

4. **For each authorized recipient `r`**, in any order (the
   resulting keyWraps list will be sorted by `recipientId` for
   signing — see step 5):

   ```
   shared = X25519(eph_priv, r.staticPublic)
   wrapKey = HKDF-SHA-256(
     salt = empty,
     ikm = shared,
     info = "rumor-room-wrap-v1:" || r.userId,
     length = 32,
   )
   wrappedKey = AES-256-GCM(K_content, wrapKey, freshWrapIv)
   ```

   Append `KeyWrap(r.userId, base64(wrappedKey), base64(wrapIv))` to
   the list. Zero `shared` and `wrapKey` immediately after use.

   Domain tag `rumor-room-wrap-v1:` is reserved forever.

5. Compute the signable bytes — see [signableBytes derivation](#signable-bytes-derivation)
   below. Sign with the sender's long-term Ed25519 → `signature`.

6. Zero `K_content`, `eph_priv`, and any retained intermediate key
   material.

### ENCRYPTED room: receiver (decrypt)

1. Pull the routing tag from `_ext.rt`; Base64-decode; match
   against subscribed rooms (see `RoomTagMatcher`).

2. On no match, **drop the receive-side dispatch but still relay**.

3. On match, verify the outer envelope signature FIRST so we never
   touch a wrap with un-authenticated wire bytes.

4. Find a `KeyWrap` with `recipientId == localUserId`. None → drop
   (not addressed despite the tag match).

5. Compute:

   ```
   shared = X25519(localX25519StaticPrivate, envelope.senderEphemeralPublic)
   wrapKey = HKDF-SHA-256(
     salt = empty,
     ikm = shared,
     info = "rumor-room-wrap-v1:" || localUserId,
     length = 32,
   )
   K_content = AES-256-GCM-decrypt(wrappedKey, wrapKey, wrapIv)
   plaintext = AES-256-GCM-decrypt(contentCiphertext, K_content, contentIv)
   ```

6. Zero `shared`, `wrapKey`, `K_content` after use.

### signableBytes derivation

The outer Ed25519 signature on a `MultiRecipientEnvelope` covers:

```
"rumor-room-envelope-v1:"
|| roomRoutingTag || "|"
|| senderId || "|"
|| senderPublicKey || "|"
|| senderEphemeralPublic || "|"
|| contentCiphertext || "|"
|| contentIv || "|"
|| (for each keyWrap in sorted-by-recipientId order:
     recipientId || ":" || wrappedKey || ":" || wrapIv || ",")
```

UTF-8 byte encoding. Domain tag `rumor-room-envelope-v1:` is
reserved forever.

**Key invariant:** the keyWraps are sorted by `recipientId`
before inclusion. A relay permuting the list without re-signing
would produce identical signable bytes (sorted order is
order-independent), so permutation is benign — but **extending,
trimming, or modifying** the list changes the bytes and breaks
the signature. A relay cannot append or remove a recipient slot
without invalidating the envelope.

---

## Forward secrecy

- Each envelope uses a fresh `K_content` (32 random bytes) and a
  fresh X25519 ephemeral keypair. Compromising a recipient's
  long-term X25519 static **months later** lets the attacker
  decrypt envelopes addressed to that specific recipient — but
  **nothing else**: not other recipients' slots (different wrap
  keys), not other envelopes (different ephemerals + content
  keys), not future envelopes from the same sender (fresh
  ephemerals).

- Member kick is **omit from the next envelope's recipient list**.
  The kicked member has no slot in the new envelope to decrypt;
  their old-envelope plaintext is still on their device (no
  protocol can unsend) but the new envelope is inaccessible.

- No shared room key. No rotation cascade. No "catch up after
  missed rotation" flow.

---

## Subscription state (receiver side)

Implementations persist subscription material via
`RoomSubscriptionRepository`:

```kotlin
RoomSubscription(
  roomId: String,
  mode: RoomSubscriptionMode { OPEN, ENCRYPTED },
  routingKey: ByteArray,  // 32 bytes for ENCRYPTED; empty for OPEN
  joinedAtMs: Long,
)
```

The user's X25519 static private (for ENCRYPTED-room decrypt) lives
elsewhere (identity layer), NOT in this repository.

The receive engine reads the subscription snapshot on every
inbound `MessageType.ROOM_MESSAGE`, derives the tag set, matches,
decrypts on hit. Order of operations is recorded in
`RoomTagMatcher.match()`.

---

## Known gaps in the reference implementation

- **Ed25519 → X25519 derivation not yet wired** (see CLAUDE.md O91).
  Today Rumor identities are Ed25519-only and there's no standard
  conversion to X25519 happening at the encrypt/decrypt path. A
  compliant Rumor client cannot exchange ENCRYPTED room messages
  with another Rumor client until O91 is closed. The OPEN room
  path works fully (no per-recipient crypto involved).

- **Receiver-side `localX25519StaticPrivate()` returns null** in
  both `:app` and `:simulator` wirings. ENCRYPTED room messages
  are matched at the tag layer but the decrypt step is skipped;
  the envelope is stored as opaque ciphertext, decryptable once
  O91 lands.

- **Sender-side membership enumeration is the caller's job.** The
  `composeRoomMessage` API takes the recipient list directly; an
  events-derived membership projection (RoomCreate +
  invites/joins/leaves/bans replayed) is the planned source but
  hasn't been wired yet. UI / test code provides the list today.

An alternative implementation does NOT need to fix these to be
spec-compatible with the reference today. When the reference
closes them, byte-compatibility holds because the on-wire format
is unchanged — only the local key-derivation step gets wired.
