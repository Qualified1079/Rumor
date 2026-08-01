# Security research — practices, standards, and open directions

> **Created 2026-07-30** (user priority: "do more research to improve security;
> make a research note on security practices and standards — this is important,
> high on the handoff list"). This is the standing home for Rumor's security
> *program* — the systematic review that should run before more feature work,
> plus the open research directions the privacy/security workshop surfaced.
> Backlog rows track discrete *work*; this doc tracks the *review discipline and
> the questions still open*. Update it as items are investigated or closed.

## Why this exists

Rumor makes strong-sounding privacy claims against a serious threat model (O55:
prolonged infrastructure collapse; a state-or-warlord-class adversary with radio
swarms and forensic tooling). The honesty conventions (O27, O51, O60) already
fence off what *can't* be defended. But the parts that *can* be — the crypto
core, the wire canonicalization, the DoS surface, the secret hygiene — have never
had a **systematic, standards-driven** review, only ad-hoc audits. Two full
audits already found real breaks in the core auth scheme (O144 signature-splice;
O156/O157 the same class *again*). That recurrence is the signal: point-fixes
aren't enough; the codebase needs a review *method*, and a structural guard so a
whole *class* of bug can't come back a third time.

## DO FIRST — the review passes, highest value first

Ordered by (severity of what they'd catch) × (cheapness to run):

1. **Signature-transcript canonicalization — sweep + a structural guard. ✅ DONE 2026-07-31.**
   O144 fixed `MessageStore.signableBytes`; O156/O157 then found the identical
   bare-delimiter splice class in `keywordFilterListSignableBytes`, the Room
   `*SignableBytes` helpers, and the OPEN-room routing tag. **This class has
   shipped, been "fixed," and recurred** — so it was killed structurally, not
   whack-a-moled:
   - One canonicalization primitive, `core/protocol/SignableFraming.kt`
     (`framed()`/`framedList()`, netstring `<charLen>:<value>`), now used by
     every free-text-carrying transcript incl. `MessageStore.signableBytes`.
   - The six vulnerable transcripts re-framed under fresh `-v2:` domain tags
     (hard cutover, no shipped users; all six `-v1:` tags recorded retired in
     `RENAMED_FIELDS_NEVER_REUSE.md`).
   - **`SignableFramingGuardTest`** source-scans every `*SignableBytes` /
     `*ChallengeBytes` builder and **FAILS the build on any new bare-delimiter
     join** unless the function is on a documented splice-safe allowlist.
   - `SignableFramingInjectivityTest` demonstrates each fixed transcript's
     former v1 collision and proves v2 no longer collides.

   **Classification (all `*SignableBytes` / challenge builders, 2026-07-31):**

   | Transcript | Free-text / attacker-var field | Disposition |
   |---|---|---|
   | `MessageStore.signableBytes` | content / encryptedPayload / recipientId | **framed** (O144, `rumor-msg-v2:`) |
   | `keywordFilterListSignableBytes` | `name`, entry `pattern` | **framed** (`-v2:`) |
   | `messageDeleteSignableBytes` | sender-chosen `messageId` | **framed** (`-v2:`) |
   | `bridgeVouchedSignableBytes` | `originNetwork`/`originSenderId`/`payload` | **framed** (`-v2:`) |
   | `roomCreateSignableBytes` | room `name` | **framed** (`-v2:`) |
   | `roomActionSignableBytes` | moderator `reason` | **framed** (`-v2:`) |
   | `roomPostingCertSignableBytes` | `channel` | **framed** (`-v2:`) |
   | `blocklistSignableBytes` / `blocklistDiffSignableBytes` | none (ids hex/bridge, no `\|`/`,`) | splice-safe, allowlisted |
   | `prekeyPublishSignableBytes` | none (hex + base64 + decimal) | splice-safe, allowlisted |
   | `multiRecipientEnvelopeSignableBytes` | none (routing tag + base64 + hex ids) | splice-safe, allowlisted |
   | `roomInviteSignableBytes` | none (hex + base64 + decimal) | splice-safe, allowlisted |
   | `helloChallengeBytes` / `helloChallengeBytesV2` | none; self-signed → no cross-identity splice | splice-safe, allowlisted |

   Also eyed: `RoomRoutingTag` and the RBSR fingerprint are SHA-256/HMAC over
   domain-tagged inputs, not signature transcripts — O157's routing-tag concern
   is a separate finding (the tag isn't in the signed transcript at all), tracked
   under O157, not this canonicalization pass. Rows: O144/O156 done; O157's
   routing-tag-not-in-transcript half remains open.

2. **Formal privacy + security threat model (LINDDUN + STRIDE).** Rumor is a
   *privacy* system with no formal threat-model artifact. Run **LINDDUN**
   (Linkability, Identifiability, Non-repudiation, Detectability, Disclosure,
   Unawareness, Non-compliance) over every data flow — it's purpose-built for
   exactly Rumor's concerns and will systematize what O27/O51/O58/O60 record
   ad-hoc. Run **STRIDE** over the trust boundaries (transport, plugin, bridge,
   Room membership). Output: a threat-model doc that each new feature is checked
   against. Note: Non-repudiation is a LINDDUN *privacy threat* — which is
   exactly the O191 repudiable-DM insight arriving from the standards side.

3. **Crypto-primitive audit (concrete, high-value, JVM-inspectable). ⏳ IN PROGRESS — RNG + GCM + constant-time compares done 2026-07-31.**
   - **AES-GCM nonce uniqueness** — GCM nonce reuse is catastrophic (key/auth
     recovery). Audit every AES-GCM call site for how the nonce is generated;
     prove uniqueness-per-key discipline; prefer random-96-bit with a documented
     birthday-bound budget or an explicit counter.
     **✅ Reviewed 2026-07-31:** `CryptoManager.encrypt` generates a fresh random
     12-byte IV per call from `PlatformRandom` (JVM `actual` = a shared
     `java.security.SecureRandom`, CSPRNG). DM/room content keys are per-message
     ephemeral (O38 prekeys, per-envelope content keys), so the same key never
     approaches the random-96-bit birthday bound. No fixed/counter nonce or
     nonce-from-plaintext anywhere. Clean.
   - **Constant-time comparisons** — every MAC / auth-tag / sealed-sender-tag
     (O53) / HMAC compare must be constant-time; a byte-wise early-return leaks
     via timing. Grep for `==`/`.contentEquals` on secret-derived bytes.
     **✅ Fixed 2026-07-31:** the one live secret-derived compare was
     `RoomTagMatcher.match`'s ENCRYPTED branch — `contentEquals` on
     `HMAC(routingKey, messageId)`, an early-returning compare that was a
     byte-at-a-time routing-tag forgery oracle. Added `crypto/ConstantTime.equals`
     (XOR-accumulate, branch only on final result) and routed both matcher
     branches through it. Set the convention on `SealedSenderTag` (its
     still-unwired receiver-side match MUST use `ConstantTime.equals`).
     `ConstantTimeTest` pins correctness incl. high-bit/sign-extension. Other
     `contentEquals` sites are over public data (pinned pubkeys, content hashes,
     userIds) — not secrets, no oracle. Honest scope: JVM constant-time is
     best-effort (JIT/GC), documented in the helper (O27 ethos).
   - **HKDF usage** (RFC 5869) — confirm extract-vs-expand used correctly, salt
     and `info` domain-separated (O121(c) started the domain-tag guard;
     generalize it — `DomainTagInvariantTest`).
   - **Ed25519 canonicalization** — reject non-canonical S / small-order points
     where it matters; confirm the library does.
   - **Secret zeroization** — O115 started (private-key + KDF-material wipe);
     finish threading `CharArray` from UI so passphrases never ride a JVM
     `String` (immutable, GC-copied, heap-dumpable). Ties O190 (ephemeral),
     O20/O44 (Keystore).

4. **DoS / resource-bound pass on every untrusted-input path.** The audits keep
   finding unbounded loops/collections/parsers fed by the wire: O166 (LAN accept
   loop), O172 (RBSR frame count), O185 (per-message blocking DB reads), O176
   (breadcrumb map), O191's skipped-key cap. Do ONE systematic sweep: every
   parser, every `while(isActive)`, every collection keyed on attacker-supplied
   data gets a cap + a cancellation checkpoint. Anchor nodes (O55/O98) are the
   ones an attacker most wants to exhaust.

5. **Plan an external audit.** Before any real deployment, a Cure53 / Trail of
   Bits-class review of the crypto + wire. This doc + the LINDDUN artifact + the
   `docs/*THREAT_MODEL*` docs are the scoping input. Reproducible builds (O49) are
   a prerequisite for anyone to verify what they reviewed.

## Standards & vetted constructions to evaluate (vs hand-rolled)

Per the prebuilt-first policy (crypto → always audited library, never hand-roll):

- **Noise Protocol Framework** for the handshake. HELLO is a hand-rolled
  Ed25519 challenge-response (O31/O65). Noise (XX / IK patterns) is a vetted,
  widely-analyzed framework for exactly this — mutual auth + FS handshake. Worth
  evaluating whether HELLO should become a Noise pattern rather than growing more
  hand-rolled negotiation (O31 v2 sig-domain is already accreting).
- **Signal Protocol (X3DH + Double Ratchet)** for DMs — this is O191, arriving
  from the standards side. Use `libsignal` primitives as reference; do NOT
  hand-roll the ratchet.
- **MLS (RFC 9420, Messaging Layer Security)** for Rooms (O79). Rumor's
  multi-recipient envelope + posting-cert scheme (O79/O89) is hand-rolled group
  crypto — the exact thing MLS was standardized to replace, with efficient
  membership changes and forward/post-compromise security for groups. Evaluate
  MLS vs the current design *before* Rooms is built out (O187 says Rooms is still
  unbuilt — good timing to adopt a standard instead of hand-rolling).
- **HPKE (RFC 9180)** for the sealed-sender / prekey envelope (O38/O53) — a
  standardized hybrid public-key encryption construction; check whether the
  current ephemeral-X25519 + AES-GCM scheme should just *be* HPKE.
- **Post-quantum hybrid** (O201) — X25519 + ML-KEM/Kyber (FIPS 203) for the DM
  and prekey path, given harvest-now-decrypt-later under a decades horizon.

## Open research directions (from the 2026-07-30 workshop + this note)

Traffic-analysis / metadata (backlog rows filed):
- O195 — length padding (all payloads) + randomized relay delay + mix
  batch-shuffle; exploit the DTN latency Tor can't.
- O196 [DECISION] — reject handheld cover traffic + adaptive fill (joules, not
  bits); cover traffic is infrastructure-node-only.
- O197 — limited onion routing through *known contact-relays* for high-sensitivity
  DMs (sidesteps the key-directory problem).
- O198 — **breadcrumb minimization: the info-vs-utility curve.** Cheapest early
  win — pure `:core`/sim, no wire/crypto change. Measure delivery vs breadcrumb
  precision (exact → bloom → coarse → next-hop-only → none); the knee is the
  privacy-optimal design. Result informs O53 + O31.

Identity / key lifecycle:
- O192 — key-retirement / compromise beacon (blunt "distrust this key"; can't
  cryptographically date a theft).
- O45/O46 — backup (BIP-39) + M-of-N social recovery.
- O201 — post-quantum hybrid.

Device / at-rest:
- O189 — three-mode duress (crypto-shred KEKs, hidden-vault deniability).
- O190 — ephemeral / RAM-only mode (tiered forgetting; anti-forensics by
  nonexistence).
- O193 — relay-ephemeral DM caching (combinable eviction triggers).
- O199 — lock & device-access hardening (passphrase-only lockdown, panic action,
  wipe-on-N, FLAG_SECURE).
- O200 — notification privacy controls.
- O20/O44 — Keystore-wrapped identity.
- O165 — FLAG_SECURE (currently absent everywhere — audit finding).

DM path redesign (the big one):
- O191 — ratcheted DM path. **NOT committed — de-risk in simulator first.** Would
  unify per-message FS + post-compromise security + sealed-sender + repudiable
  auth, but forks DMs off the signed-`RumorMessage` wire (O65) and breaks
  per-sender rate limiting (O16). Four mesh-hard points to measure before commit.

Sybil / eclipse:
- O194 [DECISION] — can't fingerprint a radio on commodity hardware (no PHY/IQ
  access, either direction); defense is trust-weighted view construction (O136),
  never node-count.
- O135 — make sybils *harmless*, not impossible.

Supply chain / distribution (the mesh IS the app store post-collapse — O26/O55):
- O49 — reproducible builds (prerequisite for O26 peer review + any external
  audit).
- O50 — N-of-M co-signed updates for high-capability plugins.
- O139 — tampered-build resistance.
- SLSA framework + dependency pinning + checksum verification — evaluate.

Fuzzing / property testing:
- O28 / O170 — coverage-guided (Jazzer) fuzzing on all wire parsers; the audit
  found harnesses that catch `Throwable` and mask crashes (O170) — fix so Jazzer
  can actually report. Extend the O112 hostile-strings corpus. Consider
  *differential* fuzzing (two decoder impls, diff outputs).

## Standing rules (don't re-derive)

- Crypto/mnemonics/secret-sharing → always an audited library (prebuilt-first).
- Every signed transcript is length-prefixed under a domain tag — no bare
  delimiters, ever (O144 class). A test enforces this.
- Honesty conventions (O27/O51/O60) bound the *claims*; this doc bounds the
  *engineering*. Never let the engineering review quietly expand a claim past
  what O27/O60 permit.

## O193 relay-ephemeral DM caching — sim findings (2026-08-01)

Harness: `simulator/…/engine/RelayEvictionModelTest.kt` — a pure-logic
discrete-event store-and-forward model (carry/evict as set-algebra over a
meeting schedule; a faithful abstraction of the real gossip serve). Sender S
originates one DM→R, keeps a persistent outbox copy, eviction applies only to
*carriers'* cached copies. Metrics: delivery rate, latency, and normalized
storage (% of carrier·rounds a copy occupies — scale-comparable). ACK model =
BREADCRUMB (walks the reverse delivery path via `BreadcrumbCache` crumbs — the
deployed mechanism; only on-path nodes get it, off-path carriers rely on TTL).

**Conclusions (drive the O193 build):**
1. **Recommended trigger bundle = spray-k + on-ack + TTL** (`k=4`, `X≈24`). Best
   delivery-safe policy in every environment tested: matches baseline delivery
   (95–100%) at 2.3–3.5× less storage. Each component covers a different gap —
   spray caps dense over-replication (breadcrumb-ACK can't reach those copies),
   on-ack clears the delivery path fast, TTL sweeps off-path stragglers the ACK
   never reaches. Confirmed scale-robust to N=256.
2. **on-relay (forward-then-forget, k=1) is a scale-amplified trap.** 56%
   delivery at N=32 in the sender-offline/sparse stressor, degrading to 13% at
   N=256 (a single forwarded copy is a smaller fraction of a bigger mesh). Keep
   only as an explicit "may not arrive" high-sensitivity extreme, never default.
3. **on-ack alone is delivery-safe by construction** (a carrier only evicts
   *after* the DM already reached R — that's what produced the ACK). The
   deployed breadcrumb ACK is ~44% weaker at cleanup than an optimistic flood,
   which is *why* the TTL backstop earns its place — as a hard storage ceiling,
   not a delivery rescue (delivery is never at risk from a weak ACK).
4. **TTL alone (after-X) is a weak lever** — preserves delivery but saves little,
   because copies rarely outlive X except in long-carry cases, exactly where you
   want the bound. Useful only combined with on-ack/spray.

**Fidelity caveats (both intentionally preserved as two datasets):** the model
is an abstraction — the *trigger dynamics* are modeled, not real code. on-ack's
automatic trigger does NOT exist yet (no delivery-receipt message type; the
closest real substrate is O40 `MESSAGE_DELETE`, a manual sender/recipient-
authorized purge). Small-N/accurate and large-N/coarser runs bracket the truth;
they agree on the winner. The real store-and-forward carry + O40 purge
propagation should be validated on the `:node` real path before building on-ack.

### O193 accuracy note — the recurring "95%" (2026-08-01)

The ~95% baseline delivery in the `ferry S:early R~` stressor is NOT a delivery
rate — it is a structural artifact. Failure decomposition (`RelayEvictionModel
Test.stressorAccuracyAndSenderRetry`, E1): of the ~5% undelivered, **5.0% is
"never-left-sender"** (the DM never got handed off during the sender's 15 online
rounds under a 4-meetings/round ferry) and **0.0% is recipient/relay loss**.
That is why every eviction policy reported the identical 95.0% — the ceiling is
sender-escape, independent of caching. Every other environment is already 100%.

Sender-reconnection ("retry until ACK") is the lever (E2): sender online ~20% →
98.8%, ~30% → 100%. 99%+ is achievable; retry-on-reconnect is the mechanism, and
it needs a delivery-ACK to know when to stop — the same missing primitive as
on-ack. Non-monotone at very low uptime (10% scattered < an early burst) because
early presence maximizes carrier-spread time — an outbox-scheduling insight.
