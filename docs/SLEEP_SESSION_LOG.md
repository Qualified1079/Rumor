# Sleep session log

Started while user was asleep. Each entry: what I did, why, files touched, rollback hint, and a `STATUS:` tag — `DONE`, `DEFERRED` (with reason), `NEEDS REVIEW` (your call before merging further), or `ABANDONED`.

User instruction recap: highest-alpha work, fuckups can be rolled back, document everything, pivot when blocked, don't go idle. Branch: `claude/practical-archimedes-wmySm` (per in-conversation directive overriding the boilerplate branch).

Read this top-down; latest entries at the bottom.

---

## Plan I started with

1. Audit CLAUDE.md for explicit research-required / need-to-verify items, run web searches
2. `docs/wire-format.md` first draft from reading `core/wire/`
3. Phase 1c shim surface notes (what each `java.*` import needs as `expect/actual`)
4. O64 sentAtMs audit (find every reference, document, then implement)
5. O13 BloomFilterData OOM graceful degradation (small, isolated)
6. O48 MeshCore synthetic-userId from pubkey hash (small, isolated)
7. Phase 1b: lift ~40 pure-Kotlin files from `jvmMain/` to `commonMain/` (mechanical, batched)

Will pivot freely as things surface.

---

## Entry 1 — Research notes for open questions (`docs/RESEARCH_NOTES.md`)

**STATUS: NEEDS REVIEW** (read the doc, yay/nay each section)

Compiled web-research findings for seven open questions flagged in CLAUDE.md:
1. **O42/O66 Negentropy** — fingerprint formula confirmed (`SHA-256(sum mod 2^256 || varint(count))`), bound-edge concern is mitigated by shortest-prefix encoding (no separate failure mode), gotcha around duplicates with frame-size limits noted. Need to audit our `Rbsr.kt` against the spec.
2. **O68 App Store 1.2** — local-only report queue is necessary but probably not sufficient; concrete recommendation is "report → auto-block + signed-bundle export" so reviewer sees a visible outcome. Inconclusive without submission attempt.
3. **O33 battery** — `SCAN_MODE_LOW_POWER` ~37 mA on a stock phone; estimated per-mode drain (Mobile ~5–8%/hr, Static ~10–15%/hr, Free ~20%/hr). Hard numbers need O33 measurement campaign; placeholders for now.
4. **O74 alt stores** — the real high-leverage non-Play targets reduce to **F-Droid + IzzyOnDroid + GitHub-releases-for-Obtainium**; the rest is exploratory. Aurora handles Play indirectly.
5. **O50 N-of-M signed updates** — adopt Bitcoin Guix model directly; O49 reproducible builds is a hard prerequisite.
6. **O72 Nostr transport** — use NIP-59 gift-wrap (not raw kind:1), need a Rumor↔Nostr key mapping (curve mismatch), Tor underneath non-negotiable.
7. **O73 mDNS Android↔iOS** — viable, opt-in per network recommended, NSNetService deprecated on iOS in favor of Network framework.

Doc also records four questions that did NOT resolve from web sources — flagged for your eyes.

Files: `docs/RESEARCH_NOTES.md` (new).
Rollback: `rm docs/RESEARCH_NOTES.md`.

---

## Entry 2 — Wire-format spec (`docs/wire-format.md`)

**STATUS: NEEDS REVIEW** (this is the O75 prerequisite — without it, no non-JVM relay can interop)

Drafted the v1 wire-format spec from reading `core/wire/`, `core/model/`, `core/sync/`, and the JVM `GossipSession.kt`. Sections:

1. The two-layer model (O65): GossipPacket vs RumorMessage, when each evolves.
2. Framing: 4-byte BE length prefix + UTF-8 JSON. `WireJson` config (ignoreUnknownKeys, encodeDefaults, explicitNulls=false, classDiscriminator="type"). `_ext` forward-compat policy.
3. Every `GossipPacket` subtype with field-level docs and on-wire JSON examples.
4. `RumorMessage` shape, canonical signable-bytes formula, all `MessageType` enum values.
5. Sub-payloads: Blocklist, IdentityRotation, SelfPresence, BridgeVouched, TransferMetadata, Chunk, ChunkRequest, TransferCancel — each with its domain-tagged signature recipe.
6. Capability negotiation via HELLO `supportedFeatures`.
7. Domain-tag inventory (the canonical "what signs what" table).
8. **Open audit items.** Most important: I found that **our RBSR XOR fingerprint formula is NOT the same as the hoytech/NIP-77 reference.** Ours is `XOR-reduce(SHA-256("rumor-rbsr-v1:" || ts || ":" || id))`; theirs is `SHA-256(sum_mod_2^256(IDs) || varint(count))`. Both are commutative/associative so the protocol works internally, but **we will NOT interop with strfry / a Nostr Negentropy relay.** Decision needed (deferred to you):
   - Align with NIP-77 → free interop with Nostr ecosystem, but breaks our `rumor-rbsr-v1` domain tag (needs a `rumor-rbsr-v2` bump).
   - Keep ours → no behavioral difference at our scale, but O54 transport plugins / O72 Nostr fallback can't reuse the RBSR machinery.
   - I lean **align with NIP-77** as a one-time bump now (capability-gated via `rbsr-v2` feature tag), since `rbsr-v1` is opt-in only in production. Costs nothing to fix now, costs a wire-format break later. This is also the kind of decision the doc forced into the open — exactly why writing the spec down is valuable.

Files: `docs/wire-format.md` (new).
Rollback: `rm docs/wire-format.md`.

This unblocks O75 (MCU relay), O54 (TransportPlugin / native relays), and O63 (iOS port) — all three were blocked on "wire is implicit in core/wire/". They are no longer blocked on documentation, only on implementation.

---

## Entry 3 — Phase 1c shim surface (`docs/PHASE_1C_SHIM_SURFACE.md`)

**STATUS: NEEDS REVIEW** (decisions deferred at end of doc; pure planning, no code)

Read all 13 files in `:core/jvmMain/` that import `java.*` and inventoried the platform shims Phase 1c will need:

- 9 distinct java types across the 13 files
- Demand ranking: Base64 (5) > ConcurrentMap (4) > Sha256/UUID/AtomicLong (3 each) > others
- One straight migration (no shim needed): `java.util.ArrayDeque` → `kotlin.collections.ArrayDeque`
- Proposed `expect/actual` declarations sketched per shim, with JVM and iOS actuals named (CryptoKit for iOS crypto; `atomicfu` for concurrent primitives; etc.)
- Implementation order proposed: Base64 first (highest fan-in, trivial), CryptoManager (AES-GCM + PBKDF2) last (biggest single lift)
- Decisions deferred to user: atomicfu vs alternatives, BouncyCastle KMP vs split per-platform, linuxMain-now vs iosMain-first

Files: `docs/PHASE_1C_SHIM_SURFACE.md` (new).
Rollback: `rm docs/PHASE_1C_SHIM_SURFACE.md`.

This unblocks O63 Phase 1c from "needs research." Phase 1c can start the moment Phase 1b is green.

---

## Entry 4 — O64 sentAtMs audit (`docs/O64_SENTATMS_AUDIT.md`)

**STATUS: NEEDS REVIEW** (documents 5 confirmed UI bugs but does NOT fix them)

Grep'd every `sentAtMs` reference in the codebase (18 files). Categorised each:

- **Category A** (correct, no change): 11 sites — compose-time stamps, signature inclusion, Room columns, sim fixtures.
- **Category B** (already mitigated): `MessageDao.kt`'s `ORDER BY MIN(sentAtMs, receivedAtMs)` — the original 9cb2ad9 fix. Should be refactored to use the new `displayTimeMs` once added.
- **Category C** (confirmed bugs): **5 UI sites** use sender-asserted `sentAtMs` directly:
  - `FeedScreen.kt:88`, `ThreadScreen.kt:145`, `MessagesScreen.kt:84` — "X ago" labels (use `receivedAtMs` instead).
  - `MessagesViewModel.kt:72,81` — thread-list ordering by `sentAtMs` (pinning attack; use `displayTimeMs`).
- **Category D** (verification pass needed): simulator + RBSR docstring.

Implementation order specified; decisions deferred:
1. Explicit ingest-time clamp vs `displayTimeMs = min(sentAtMs, receivedAtMs)` doing the job implicitly. Lean: rely on `min` — cheaper, signature stays valid on relay.
2. SKEW_TOLERANCE_MS value if explicit clamp goes in. Lean: generous (1 hr) for post-collapse drift.
3. **Whether to ship the Category C UI fixes now in a small PR** vs wait until after Phase 1b lands. The fixes are real security bugs but the framing copy for "X ago" UI is yours to choose, so I held them back. **Suggest you yay/nay this on wake — they're a 30-min self-contained PR.**

Files: `docs/O64_SENTATMS_AUDIT.md` (new).
Rollback: `rm docs/O64_SENTATMS_AUDIT.md`.

---

## Entry 5 — O13 BloomFilterData OOM: already done

**STATUS: DONE (closed via audit, no code needed)**

Read `core/.../transport/wifidirect/BloomFilterData.kt`. The `deserializeOrNull` graceful-fallback companion already exists (catches `OutOfMemoryError`, `IllegalArgumentException`, `NegativeArraySizeException`). Grep'd call sites:

- `GossipSession.kt:239` — uses `deserializeOrNull` ✅
- Other call sites (`GossipSession.kt:346,361`, `SimTransport.kt:183,188`) use the raw constructor on *locally-trusted* sizes (`knownMessageIds.size`, sim-controlled `dstKnownIds.size`) — no attack surface.

**O13 is effectively done.** The backlog entry can be moved into "Completed gaps" when you next edit CLAUDE.md, or repurposed to track the followups in the body of the entry (per-sender ingest token bucket).

No files touched.

---

## Entry 6 — O48 bridge synthetic userId from pubkey (`docs/O48_BRIDGE_USERID_AUDIT.md`)

**STATUS: NEEDS REVIEW + USER INPUT** (documented thoroughly, NOT implemented)

Read both bridge implementations. Confirmed the current state matches O48's concern:

- **MeshCoreBridge.kt:147**: `meshcore:" + FNV1a(senderName)` — spoofable by display-name impersonation.
- **MeshtasticBridge.kt:125**: `meshtastic:" + (packet.from).toString(16)` — better than name, but `packet.from` is cleartext and trivially spoofed.

Identified the per-bridge work needed:
- **MeshCore**: subscribe to `CONTACTS_START` frames, maintain `Map<senderName, pubkey>` keyring, derive userId as `meshcore:" + sha256(pubkey).take(16).toHex()`. Needs reading `MeshCoreFrames.kt` (and likely the upstream meshcore-open BLE_PROTOCOL.md afresh) to see the contact-frame format.
- **Meshtastic**: add NODEINFO_APP handler, decode `User.public_key` (X25519), build `Map<nodeNum, pubkey>`, derive userId same shape.

**Held back from implementation** because:
1. I'd be writing protocol code against documentation rather than verified frame layouts.
2. The migration question (existing bridged contacts → new userId) needs your call: hard cutover or O41-style identity rotation rebind.
3. Userid byte length (16 hex / 64 bits chosen by me, you may want larger).
4. O48 is pre-ship requirement for O5 (DM bridging) but isn't blocking anything you're working on right now.

Files: `docs/O48_BRIDGE_USERID_AUDIT.md` (new).
Rollback: `rm docs/O48_BRIDGE_USERID_AUDIT.md`.

---

