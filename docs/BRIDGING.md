# Encrypted bridging — architectures A/B + the DmEnvelope (O5a) framework

> Extracted verbatim from `CLAUDE.md` "Design decisions recorded" (2026-07-18 size
> pare-down). This is the full design record for DM bridging (O5). The compact
> invariants + the six O5a security constraints remain in `CLAUDE.md`; read this
> file before designing any bridge DM work.

**Two architectures, pick one before designing O5.**

**Architecture A — decrypt-and-re-encrypt (bridge is an endpoint).** The bridge holds both crypto sessions. Sender E2E-encrypts to the bridge's Rumor pubkey; bridge decrypts; bridge re-encrypts to the LoRa contact's PKC key. Pure-relay DMs (Rumor → Rumor that happen to traverse a bridge node) stay E2E because the bridge has no key for messages it isn't the addressed recipient of. **Crypto is sound; trust model changes — the bridge operator can read bridged DMs.** Works for any LoRa stack regardless of envelope format. Required UI before ship (HARD blockers, not nice-to-have):

1. Bridged contacts must render with a distinct "via <bridge>" label everywhere they appear (compose, contact list, thread header). Never as if they were direct Rumor contacts.
2. The compose screen for a bridged contact must display a persistent banner: "Messages to this contact pass through <bridge owner>'s device and are readable there." No dismiss.
3. SAS / safety-number verification must include a three-leg fingerprint (you ↔ bridge ↔ them) instead of the two-leg flow used for native Rumor contacts.
4. Identity rotation: if the bridge node's Rumor key changes, every bridged contact must be re-verified — bridge-key change ≠ peer-key change but UI must treat it as either.

**Architecture B — envelope passthrough (bridge sees ciphertext only).** Rumor's DM envelope for bridged contacts becomes byte-compatible with the target network's DM envelope. The bridge forwards opaque ciphertext both ways and never holds plaintext. Preserves real E2E across the bridge. Curve alignment exists for **both** bridges — Meshtastic PKI uses X25519 directly; MeshCore uses Ed25519 for identity and ECDH from the X25519-converted key, same as Rumor. The differences are only in the AEAD wrapper, not in the curve:

- Rumor native: X25519 ECDH + AES-GCM
- Meshtastic envelope: X25519 ECDH + AES-CCM + Meshtastic framing
- MeshCore envelope: ECDH (Ed25519→X25519) + AES-128-ECB + HMAC-SHA256 (Encrypt-then-MAC) + MeshCore framing

Requires per-bridge:

1. A second DM-encryption code path in `GossipEngine.composeDirect` selected by recipient userId prefix (`meshtastic:*` → Meshtastic envelope; `meshcore:*` → MeshCore envelope; native → existing X25519+AES-GCM).
2. Bridge advertises discovered remote NodeInfo / contacts as synthetic Rumor contacts whose `publicKey` field holds the remote pubkey (32 bytes for both — Meshtastic X25519 directly, MeshCore Ed25519 which we convert at ECDH time).
3. Rumor's Ed25519 signature is dropped for bridged DMs — neither target envelope carries an outer signature, only an AEAD/MAC tag. Acceptable because authenticity is enforced by the AEAD/MAC plus the recipient's knowledge of which key sent it.

Default recommendation: **Architecture B for both bridges.** Worth the per-bridge envelope code to preserve real E2E. Fall back to Architecture A only if a future bridge has no usable ECDH path. Both architectures still require the bridged-contact UI labelling from A1 above — even passthrough has a bridge in the path, and users need to know which contacts are bridged so a compromised bridge can't quietly substitute its own key for a peer's.

## Framework: pluggable `DmEnvelope` (O5a)

To avoid hardcoding "if recipient starts with meshtastic: do X, if meshcore: do Y" into core, route DM crypto through a registered envelope. Each bridge plugin owns its envelope; core only knows the interface.

```kotlin
// core/plugin/DmEnvelope.kt
interface DmEnvelope {
    val recipientPrefix: String                  // e.g. "meshtastic:"
    val envelopeId: String                       // stable wire-format id, persisted on the message
    val selfAuthenticating: Boolean              // true → engine skips outer Ed25519 sig
    fun encrypt(recipientUserId: String, recipientPubKey: ByteArray, plaintext: ByteArray): ByteArray
    fun decrypt(senderUserId: String, senderPubKey: ByteArray, ciphertext: ByteArray): ByteArray?
}

// additions to PluginContext
fun registerDmEnvelope(envelope: DmEnvelope)
fun injectBridgedDm(
    recipientUserId: String,
    senderUserId: String,
    senderPubKey: ByteArray,
    ciphertext: ByteArray,
    envelopeId: String,
)
```

Compose path: `GossipEngine.composeDirect` consults the registry by recipient prefix; falls back to the existing native envelope when none matches. Inbound path: bridge plugin calls `injectBridgedDm` with an opaque blob plus the envelope id; engine routes to the recipient's inbox without ever holding plaintext; the recipient's UI looks up the envelope by id and decrypts at read time.

This is a prerequisite for O5 if we want more than two bridges without forking core every time. Even with just Meshtastic + MeshCore today it is the right shape — two hardcoded branches in `composeDirect` would be the same wrong direction as the original Hilt → Koin "just rewire it where you need it" pattern we already abandoned. Implement O5a first, then O5 for each bridge is a small, isolated addition. (Status: O5a shipped — see G5 in `docs/COMPLETED_GAPS.md`.)

## Security constraints for O5a (non-negotiable — without these, the framework is a regression)

These are duplicated as a compact list in `CLAUDE.md` (load-bearing review criteria); the full rationale lives here.

1. **Source-gated `selfAuthenticating`.** Honored **only** for `MessageSource.LOCAL_BRIDGE` (messages arriving via `injectBridgedDm`). Over peer transport, every message must pass Ed25519 verification regardless of any envelope-id claim. This is the same rule that gates `BRIDGE_UNSIGNED` today (commit f898874); the lesson generalises directly. A peer transport claiming `selfAuthenticating` is treated as a corrupt frame and dropped.
2. **Envelope id is derived, not asserted on the wire.** Recipient looks up the envelope by recipient userId prefix locally. An envelope-id field on the message exists only as a sanity assertion at the bridge boundary — never as the decryption selector. Prevents downgrade attacks where an attacker injects a weak envelope id to coerce decryption with attacker-controlled assumptions.
3. **One envelope per prefix; registry is append-only per plugin lifecycle.** `registerDmEnvelope` throws on prefix collision. Plugin teardown unregisters atomically. Prevents prefix-squatting plugins from intercepting another bridge's DMs.
4. **Bridged DMs inherit BRIDGED trust → never re-relayed.** Same invariant as broadcast bridge traffic today.
5. **Replay protection is the envelope's responsibility.** Document this as a requirement. Native Rumor envelope uses dedup + sequence; bridged envelopes inherit the target network's mechanism (Meshtastic packet id + timestamp; MeshCore sender_timestamp tracking). A reviewer adding a new envelope must explicitly state how replay is prevented.
6. **Trust model unchanged from existing plugins.** A user-enabled plugin already has full access to broadcast plaintext via `observeIncoming`. Adding DM handling raises the leakage surface only for users who enable a malicious bridge plugin — same gate (PluginCatalog toggle) as today. The framework does NOT widen the trust boundary; it just gives well-behaved bridges a way to preserve E2E that they previously didn't have.

If any of these six constraints can't be honored by a proposed envelope, fall back to Architecture A for that bridge.
