# Handoff — away-mode foundational build (2026-07-23 → 07-24)

Away mode ON (`~/.claude/away-mode`). "Most foundational first, no LAN, commit as
you go." Phones are USB-connected (S10e `R58M30FSJKE`, Moto G `ZY22KP7F59`,
OnePlus `ec5b0707`) but NOT on a shared LAN → no cross-phone mesh delivery test;
device work limited to build/install/launch/logcat. All work below is
`:core`/`:simulator`/`:node` + additive nullable wire fields, JVM-tested, pushed
to `main`. Full `:core`+`:simulator`+`:node` suite green; `:app` compiles.

**Shipped this session (each its own commit):**
- **O100 content-addressed chunk substrate** — `TransferMetadata.chunkHashes` +
  `Chunk.contentHash` (additive/nullable, inside the signed payload); `Chunker`
  populates + `verifyChunk()`/`chunkHash()`; `reassemble()` rejects a poisoned
  chunk at its index. Names reserved, wire-format.md §4.4 updated.
- **O100 receipt-time verification** — `TransferAssembler` retains signed
  `chunkHashes` in memory, verifies each inbound chunk before storing (drops +
  attributes a bad one for re-NACK). `TransferChunkVerifyTest` (sim, real
  exchange): clean transfer / poison dropped / mechanism-B repair completes.
- **O38 `PrekeyRotator`** — receiver-side rotation scheduler + local prekey store
  (mint→sign→hold→purge-with-zeroize; FS by structure). `PrekeyRotatorTest` (6)
  incl. the DH-round-trip money test. NOTE: `PrekeyCache` (sender side) + inbound
  verify-and-cache ALREADY existed and are wired — the O38 close-out that remains
  is the cross-layer `composeDirect`-selection + `ThreadViewModel`-decrypt, which
  MUST land atomically (else DMs break) and whose decrypt half is `:app` → wants
  on-device verification, do NOT half-ship.
- **O121(c)** — completed the `DomainTagInvariantTest` guard (3 tags were
  silently unguarded: `rumor-rbsr-fp-v1:`, `rumor-o98-net-v1:`, `-psk-v2:`);
  wire-format.md §6 parity; fixed a stale `SealedSenderTag` KDoc.
- **O121(a)(b), O130(c)(f), O132** — verified already-done in source; rows
  reconciled (were stale). O132 broadcast size cap fully shipped in a2a98bd.
- **O121(e) partial** — `MessageStoreRateLimitTest` (6, O16 token bucket +
  persist=false + ingestOwn) and `SignableBytesTranscriptTest` (5, O144 v2
  transcript injectivity pinned directly). Still open: eviction-over-cap,
  GossipEngine-level coverage.

**Fleet device smoke test (2026-07-24, debug build of this session's tree):**
installed + launched on all three phones via the O138 hands-free debug intent
(`am start -n com.rumor.mesh.debug/com.rumor.mesh.MainActivity`). All clean:
identity auto-unlocked (S10e/Moto also re-wrapped to 600k PBKDF2, O115), MeshService
started, GossipEngine reseeded 21–22 stored messages (proves the additive
TransferMetadata/Chunk wire fields round-trip through Room/serialization on real
Android — they ride inside payload JSON, no schema impact), zero Rumor crashes.
Moto logged `3 Wi-Fi Direct peer(s) visible` + LAN transport `Listening on port
8787` — the fleet discovers each other over P2P, but gossip-session *delivery* is
the O98 hardware-tuning path (needs on-screen involvement; not driven blind here).
So the O38 close-out's DM round-trip still can't be functionally verified.

**Next foundational candidates (no-LAN-safe):** finish O121(e) GossipEngine
coverage; O48 bridge pubkey-hash userIds (:app, bridge-adjacent); the O38
close-out IF device round-trip becomes possible. Pre-existing flake:
`PerPeerRoutingTest` fails ~sometimes under parallel-suite load, passes in
isolation — not mine.

**UNTESTED / not started (scoped, ready to pick up):**
- **Behavioral BRIDGED-never-relay test** — the invariant is currently only
  source-grep-pinned (`SourceInvariantTest`); a behavioral test through the real
  engine would be stronger. Sketch: inject a bridged broadcast on node A via
  `GossipEngine.injectFromPlugin(msg, pluginId)` (LOCAL_BRIDGE source → BRIDGED
  trust), then run a real `SimTransport(A,B).exchange` and assert B never receives
  it (bridged is stored/inboxed locally on A but not relayed to peers), with a
  positive control that a normal broadcast from A DOES reach B. Needs a SimNode
  affordance to reach `injectFromPlugin` (not currently exposed — add one, or
  drive it via the engine directly). Was about to write this when the session
  wrapped; nothing committed for it.
- **O38 close-out** (composeDirect prekey selection + `_ext.pk` marker + `:app`
  ThreadViewModel decrypt) — spans :core+:app, changes the live DM crypto path;
  MUST land atomically and wants a two-node on-device DM round-trip (needs mesh
  delivery, i.e. the O98 path) before being relied on. Rotator + cache are done;
  this is the unverifiable-here integration. Do NOT half-ship.
