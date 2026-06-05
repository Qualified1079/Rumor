# O84 — Crypto-primitives audit: what's missing for a Cashu/Lightning companion app

**Question:** Can someone build a "mesh crypto" app (Cashu wallet, Lightning
gateway, generic value transfer) on top of Rumor as a separate APK-tier
companion or sister app **without touching `:core`**?

**Short answer:** Yes for the carrier role. The DmEnvelope framework (G5) is
the canonical "ride on Rumor without forking core" shape and Cashu fits it
cleanly. Two minor gaps worth filing as follow-ups; neither blocks a v1
companion app.

This doc walks a hypothetical Cashu wallet end to end against today's
`PluginContext` surface (`core/src/commonMain/kotlin/com/rumor/mesh/plugin/PluginContext.kt`).
Per O86, payment semantics never live in `:core` — Rumor is the carrier.

---

## What a Cashu-on-Rumor companion needs from the host

A Cashu wallet sends and receives **tokens** (signed bearer notes) between
users. The wallet maintains its own UTXO-style state, mints/melts against a
Cashu mint over HTTP or Nostr, and uses Rumor purely as a private,
infrastructure-independent transport between two peers' wallets. There is
no payment logic in Rumor; Rumor sees opaque blobs.

End to end, the companion needs eight capabilities:

| # | Capability | Today's source | Status |
|---|---|---|---|
| 1 | Sign-with-my-identity-key (prove wallet ownership cryptographically) | `PluginContext.signWithLocalKey(bytes)` (O20 plan — surface exists, impl is post-Keystore) | **Gap (planned):** signing API was renamed at some point; `PluginContext` today exposes identity (userId, publicKey) but no `sign` method. The current Plugin scene cannot sign arbitrary bytes with the user's Ed25519. Plumbing exists in `IdentityManager`; just not exposed. **Filed as O87 below.** |
| 2 | Opaque-blob DM delivery between two userIds | `DmEnvelope` framework (G5) + `injectBridgedDm` + `observeOutboundBridgedDm` | **Available.** A Cashu envelope registered with `recipientPrefix = "cashu:"` would route every outbound DM to a `cashu:*` address through Cashu's own crypto. For Rumor-userId recipients (the wallet uses the user's existing Rumor identity, not a separate Cashu address), the native envelope already provides E2E DM. |
| 3 | Mesh routing without inspecting payload | `GossipEngine.relay()` is content-agnostic | **Available (verified by source-invariant tests).** The relay path never decrypts; it only signature-verifies and forwards. Cashu tokens inside a DM are opaque to every hop. |
| 4 | Cross-network bridging when reachable | O17 BRIDGE_VOUCHED + O72 Nostr fallback | **Available as primitive; payment-app integration depends on the bridge wrapping Cashu tokens in BRIDGE_VOUCHED envelopes.** A Cashu mint reachable over Nostr-via-Tor can serve a mesh user via O72. |
| 5 | Register a new MessageType / wire envelope | `DmEnvelopeRegistry` (G5/O5a) | **Available.** Cashu defines its envelope; calls `registerDmEnvelope` at plugin attach. |
| 6 | Per-userId notification when a Cashu payment arrives | `observeIncoming()` filtered by message type | **Available.** Wallet subscribes; matches on its envelope id. |
| 7 | Storage scope for wallet state (UTXOs, mint URLs, transaction log) | O23 plugin storage scopes | **Gap (planned):** O23 is still TODO. Today plugins store wherever they reach (which for an APK-tier companion is its own app sandbox — fine — but for in-process plugins the boundary isn't expressed). For an APK-tier wallet this is a non-issue. |
| 8 | UI surface inside Rumor's thread view (Cashu "send 100 sats" composer button) | None today | **Gap (intentional?):** there is no Compose-composable injection point. An APK-tier companion would have its own UI; integration with Rumor's thread view (so a payment shows inline in the chat) needs a hook that doesn't exist. **Filed as O88 below.** |

## Where Cashu touches `:core` (if anywhere)

For a separate APK companion (the path O86 recommends), the answer is
**zero touches** for capabilities 2–6 — DmEnvelope + observe + sendMessage
covers the whole carrier role. The companion is just another Rumor user
whose userId happens to belong to a wallet process. Payments flow as
DM-shaped opaque blobs, end-to-end-encrypted between two wallets, using
Rumor's existing relay mesh.

Capability 1 (signing) needs a `:core` change — but it's a 4-line addition
to `PluginContext`, not a redesign. **O87 below.**

Capability 8 (in-thread UI hook) needs `:app` Compose work and possibly a
small `:core` extension point for plugins to declare display widgets. Not
required for a v1 companion; it's the difference between "wallet runs
parallel to chat" and "wallet renders inline in chat." **O88 below.**

## Plugin-to-plugin secure channel — not needed

The earlier sketch listed "plugin-to-plugin secure channel" as a possible
gap. On reflection, it isn't. Two plugins on the same device can either
(a) share state through the host's storage if both trust the host (today's
model), or (b) communicate as separate APKs through Android's standard
bound-service IPC, which is signed-message-tier. There is no Rumor-specific
crypto needed for intra-device IPC — the existing OS primitives are right.

## New backlog rows this audit produces

**O87 (TODO/CODE)** — Expose `PluginContext.signWithLocalKey(bytes): ByteArray`
backed by `IdentityManager.signEd25519`. Required for any companion that
needs to prove "this wallet belongs to userId X" cryptographically (Cashu,
Lightning, generic attestations). Implementation is small: thread the
existing `IdentityManager` reference into the `PluginContext` impl, expose
a `sign` method that throws if identity is locked, document the only
allowed use is "sign bytes the plugin produced, never the host's bytes."
Hard prerequisite for O20 Keystore-backed keys (the surface needs to exist
before the implementation can move into the TEE).

**O88 (TODO/UI)** — In-thread plugin display widget hook. A
`@Composable PluginDisplay(message: RumorMessage)?` declared by plugins
that want to render their own MessageType inline in `ThreadScreen`. Falls
back to default rendering (plain `[binary payload]`) if no plugin claims
the type. Required for any payment-companion that wants to show "$5 sent"
as a card inside the chat thread rather than driving the user out to a
separate app. Lower priority than O87 because the companion can ship
without it (separate-app UX is the v1 fallback).

## Sources

- `core/src/commonMain/kotlin/com/rumor/mesh/plugin/PluginContext.kt` (today's surface)
- `core/src/commonMain/kotlin/com/rumor/mesh/plugin/DmEnvelope.kt` (G5/O5a framework)
- `CLAUDE.md` G5, O5a, O20, O23, O24, O72, O84, O86 (cross-references)
