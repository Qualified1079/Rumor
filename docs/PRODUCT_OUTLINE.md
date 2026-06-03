# Rumor — product end-goal outline

The whole picture in one place. Edit / strike-through / "no, scrap this" — easier to course-correct here than across the backlog rows.

Each section is short on purpose. If a section feels under-specified, that's where we should focus.

---

## 1. What Rumor is in one sentence

An offline-first peer-to-peer mesh messenger designed for **neighborhood-scale community communication** that keeps working when the internet doesn't.

## 2. Who it's for

- **Pre-collapse spread:** neighborhood users, off-grid communities, privacy-minded people, communities organizing locally without an app-store-mediated service in the middle.
- **Post-collapse:** primary local communication when internet is down — storm, war, infrastructure attack, prolonged grid-down, mass depopulation. Users wipe phones to LineageOS / Linux long-term.

## 3. Form factors

Each is a real shipping target, not a hypothetical.

- **Android phone (primary):** carry-around participant + opportunistic relay
- **Computer (Mac, Linux, Windows):** always-on neighborhood backbone — plug it in and forget it
- **iOS phone (thin client):** active-foreground participant only, no 24-hour relay (iOS won't allow it)
- **MCU relay (ESP32 / RP2040 / nRF52, lowest priority):** tiny dedicated infrastructure, optional

## 4. Distribution channels

- F-Droid (FOSS posture, primary identity)
- Google Play (mass Android reach)
- GitHub releases + Obtainium (de-Googled crowd)
- IzzyOnDroid (F-Droid sibling, more permissive)
- iOS App Store (eventually, thin client)
- Direct desktop installers: `.dmg` / `.deb` / `.AppImage` / `.msi`
- Mesh itself (post-install plugin + model distribution)

## 5. Network model — what travels how

- **No central server.** Ever. No fallback, no "recovery mode," no Firebase, no Play Services.
- **No accounts.** Identity = Ed25519 keypair, generated on-device, locally-managed.
- **Transports:**
  - BLE on phones (always)
  - Wi-Fi Direct on Android (when available)
  - mDNS over Wi-Fi on every platform (cross-platform path)
  - Bluetooth on desktops (bluez / IOBluetooth / WinRT)
  - USB CDC for direct device-to-device (future)
  - Optional Nostr-over-Tor bridge for cross-region, opt-in only
- **Routing:** gossip with breadcrumb-aware DM routing. Each hop signs nothing; the originator signs the message. Dedup at every relay.
- **Plugins can add transports** (O54 transport-plugin tier) — packet radio, satellite modems, OpenWrt routers, etc.

## 6. Communication primitives

Distinct surfaces, not "everything is a chat":

- **Broadcast:** propagates as flood across the mesh. From your contacts (and from anyone in radio range if you opt into the local feed).
- **Direct message:** E2E encrypted (X25519 ECDH + AES-256-GCM + Ed25519 outer sig). Routed via breadcrumbs.
- **Rooms:** addressed-to-roomId. Four membership modes:
  - **Open:** anyone joins by roomId, plaintext messages
  - **Invite:** signed RoomInvite DM carries the key
  - **Password:** key derived from `Argon2id(password, roomId)`, shareable on a flyer
  - **Closed:** explicit member list, per-member encrypted keys
- **THE town square:** the singular global-feed Room (if exposed). Text-only forever — no creator, no robust moderation possible at global scale. Unmoderated by design, clearly labeled in UI. **Every other Room: media policy is the creator's call.**
- **File transfer:** chunked, hashed, can be paused/resumed/cancelled.

## 7. Plugin model

Three security tiers, ascending in capability and risk:

- **LOCAL_ONLY:** in-process, no network access, no outbound messaging, no DM envelopes. Loader denies dangerous APIs. Use case: on-device image classifiers (NSFW / gore), display transforms.
- **DEX (.rumor bundle):** in-process, read-only / display-layer APIs only. Drop-in mods.
- **APK-tier (bound-service):** dangerous capabilities (compose outbound, mutate contacts, register DM envelopes, open network). Runs as a separate Android process with its own permissions.

**Mesh is the distribution channel.** Plugin authors publish signed metadata; reviews are signed messages anchored to `(pluginId, bundleHash)`; bundles travel P2P via chunked transfer. No central repository.

## 8. Bridge plugins

Existing: Meshtastic and MeshCore (BLE only, broadcast-only). Both bridge plaintext from foreign LoRa networks into Rumor as `BRIDGED` trust → no re-relay.

Planned: USB transport, DM bridging (Architecture B = envelope passthrough, true E2E across bridge), multi-channel selection UI, device picker, bridge-vouched cross-mesh propagation (O17 `BRIDGE_VOUCHED` already shipped).

## 9. Moderation + abuse handling

**Layered, no central authority anywhere:**

- **Per-user local block** (display filter; never affects relay)
- **Signed keyword-filter lists** (text only) — sharable, mergeable, with per-userId exception lists for community-internal language, auto-intake from trusted publishers
- **On-device image classifiers** (NSFW / gore) as LOCAL_ONLY plugins — models distributed P2P post-install
- **Per-Room moderator removals / bans** — creator-mod or elected-mod
- **Local report log** + **@-mention mods within moderated Rooms** (the actual report path)
- **Signed public block-reasons + ±1 attestations** — visual rating only, never mechanical
- **THE town square: text-only, unmoderated** by design

Relay path is **content-agnostic forever** — Rumor never decides what to relay based on content. Your device's resources serve the mesh, not your social preferences.

## 10. Identity + time

- **userId = `SHA-256(pubkey)`** — cryptographic binding, sender can't claim a userId that doesn't hash from their pubkey
- **Display:** Diceware fingerprint (default) + emoji fingerprint (option) + on-device nickname (always available, overrides displayName locally)
- **Identity rotation: deprecated** — compromise-takeover risk too high; old key gets stolen, attacker rotates to their own key, all your contacts auto-rebind to attacker. Wire type retained for back-compat, new senders shouldn't compose.
- **Identity retirement (O69 forget-me):** signed `IDENTITY_RETIRED` broadcast on wipe — safe because no successor key
- **No shared wall clock assumption.** `displayTimeMs = min(sentAtMs, receivedAtMs)`. Sender-asserted time is untrusted.
- **Optional median-clock consensus** (O82, Bitcoin-style network-adjusted-time) for code that wants shared time — opt-in, may live as its own app.

## 11. Privacy posture — honest baselines

What we defend / don't defend, no overclaim:

- **Match Briar on:** content confidentiality, sender authenticity, key continuity at the cryptographic layer
- **Fall below Briar on:** observer-in-the-mesh privacy — the relay mesh IS the value proposition, so social-graph inference from relay patterns is fundamentally available. Documented honestly.
- **Don't match Tor on anonymity.** No mixnet, no onion routing. Local-range observers with phones in scanning mode log `(time, location, userId)` tuples; crypto can't fix that — radio identifiers are radio identifiers.
- **DM crypto:** X25519 + AES-256-GCM + Ed25519 outer sig. Receiver-side forward secrecy (O38) planned. Sealed-sender (O53) planned.
- **No reputation system.** Accepted as a property, not a problem to solve.

## 12. Crypto-as-payments

- **Not in Rumor core, ever.** Separate app or APK-tier plugin.
- Rumor exposes carrier primitives (sign, opaque-blob DM, mesh routing); a future "mesh crypto" app builds payment semantics on top.
- Keeps App Store 5.3.4 / FinCEN MSB exposure off the carrier.
- Useful post-collapse but explicitly orthogonal.

## 13. App Store posture (forward-looking)

- **Apple 1.2 (objectionable content):** combined posture — local block + signed block reasons + per-Room mod removal + shipped-default content classifiers. Lead with the combination on first review.
- **Apple 5.1.1 (account deletion):** O69 forget-me flow, signed retirement broadcast.
- **iOS = thin client only.** Apple's background-execution rules don't allow 24-hour relay. Frame honestly in the App Store description.
- **F-Droid:** primary posture, no Play Services / Firebase / GMS / Maps. LineageOS-compatible always.

## 14. Threat model framing

**Pre-collapse:** privacy-respecting offline-capable messenger for neighborhood communities. Threat: passive surveillance, app-store-mediated censorship, network-level blocking, local-range observers. We defend against the first three; document the fourth.

**Long-term collapse (the primary design target):** prolonged infrastructure loss. Threats: physical-position adversaries, sybil-equipped harassers, identity-impersonation, mesh poisoning. We defend against the first three; document the fourth (crowd-density-scale public square poisoning is real — that's why THE town square is text-only).

**Not in scope:** anonymous-against-state-actor. Use Tor for that. We're honest.

## 15. What's NOT Rumor

Defining the negatives sharpens the positives:

- Not a Signal replacement. Different threat model, different network assumptions.
- Not a Twitter / social-media replacement. No reputation system, no global trending, no algorithmic feed.
- Not a payment rail. Crypto lives in a separate app.
- Not a Tor / anonymity tool. Documented honestly.
- Not a city-scale public-protest tool. Crowd-density poisoning is real; we accept neighborhood-scale.
- Not a "build the next Discord on top of mesh." Closed-membership group chats exist as Rooms; we're not chasing feature parity.
- Not an enterprise tool. No accounts, no MDM, no SSO.

## 16. Strategic positioning

- **"Neighborhood app"** is the marketing line for spread
- **"Plug your spare Mac in and it becomes the local backbone"** is the desktop value prop
- **"Works without internet"** is the headline feature
- **"Your data is your data, on your device, full stop"** is the privacy promise
- **F-Droid + GitHub** is the spiritually-aligned distribution path; Play Store + App Store are reach plays

---

## Where to focus next

If you agree on all 16 sections above, here's where the energy returns the most progress. Numbers are rough, "session" ≈ one focused work block.

| Area | Sessions | Why |
|---|---|---|
| **Desktop packaging (Arc 1)** | 1–2 | Pre-collapse spread story is desktop-first; Compose Multiplatform Desktop is stable; doesn't need Phase 1c |
| **mDNS transport (Arc 2)** | 2–3 | Cross-platform interop (phones ↔ desktops on same Wi-Fi); first real-network demo |
| **Rooms protocol (O79)** | 3–5 | Largest open row; defines several future surfaces |
| **Phase 1c shims** | 2–3 | Unlocks iOS + native Mac/Linux/Windows binaries + MCU relay |
| **O64 explicit clamp + O85 two-tier dedup + O80 ModeProfile** | 1 each | Small, additive, ship-and-forget |
| **O41/G9 deprecation in code** | 1 | Stop composing rotation messages, add receive nag |
| **macOS / Windows packaging tasks once Arc 1 lands** | 1–2 | Per-OS installer recipes |
| **O72 Nostr-over-Tor bridge** | 4–6 | Separate threat model; design-first |

---

## Where we are right now (snapshot)

- `:core` = 35 of ~54 files in commonMain; 19 in jvmMain
- 3 Phase 1c shims shipped (Sha256, Base64Codec, Uuid)
- :core / :simulator / :app tests all green
- 72 open backlog rows (8 PART · 13 DECISION · 51 TODO)
- 22 completed gaps tracked (G1–G22)
- Branch: `claude/practical-archimedes-wmySm`, 20+ commits ahead of `main`

---

**Action requested:** read the 16 sections. Strike anything wrong. Tell me which next-focus area to start on, OR tell me you want a deeper outline pass on a specific section before any more code.
