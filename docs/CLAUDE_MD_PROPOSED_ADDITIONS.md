# Proposed additions to CLAUDE.md

Drafted for your review. Each item is written in the existing CLAUDE.md voice so you can paste-or-edit. Numbering picks up where the existing list left off (O78 onwards). Tag conventions follow the existing legend.

Three new rows + two refinements to existing rows.

---

## NEW: O78 — Signed public block reasons with +1/-1 attestations

`[TODO/CODE]` **Public, signed, optional "why" attached to a block — usable as a lightweight community-reporting layer.** A user choosing to block someone may optionally publish a signed reason: `BlockReason { blockedUserId, reasonText, reasonTags[], publishedAtMs }` signed by the blocker's Ed25519 key, gossiped through the mesh as INFRASTRUCTURE traffic. Other users seeing the block reason can **+1** or **-1** it (also signed messages anchored to `(blocker, blocked, reasonHash)` so each signer can only +/- a given reason once). Display surface: when a contact appears, show "blocked by 47 users — top reasons: spamming (38), harassment (12)" or whatever aggregates. Per-user weighting (your own blocks count fully; +1s from contacts count more than +1s from strangers; +1s from blocked-by-you count zero).

**Open problems acknowledged in the row, NOT auto-resolved:**
1. **Spoof via account creation:** a bad actor creates 10,000 identities and -1s every block against themselves. Mitigation candidates: (a) weight by graph distance — strangers' votes near-zero by default; (b) PoW per attestation (cheap on a phone, expensive at scale); (c) co-presence proof (votes count only from identities you've seen on the wire near you, bounds the cheap-Sybil case to physical-deployment cost). None are clean — bias is real either way. Recording it open.
2. **Account-deletion erasure semantics:** if a user runs O69 ("forget me"), should their published BlockReasons be retroactively erased from everyone else's feeds? Two options:
   - **Tombstone broadcast:** O69 emits a signed `BLOCK_REASON_RETRACT(reasonId, retractedBy=oldUserId)`, peers honour and remove. Spoofable by anyone who can sign as oldUserId — but at retraction time the old key is the proof, just like O41 identity rotation continuity, so authentic.
   - **Don't erase:** the reason was signed at the time; it stands. User who wants nothing of theirs left runs O69, knows blocks they published stay.
3. **+1/-1 erasure on attester deletion:** same shape as (2). Lean: handle identically.
4. **No N-of-M agreement to erase others' content** — anything that lets a quorum erase a third party's content is a censorship primitive and explicitly off the table (mirrors O36 reasoning).

**Cross-references:**
- O67 (signed keyword filter lists) — same publish/sign/diff machinery; the BlockReason shape is a sibling, not a fork.
- O68 (UGC reporting for App Store 1.2) — public block reasons + the local report log together get closer to a defensible 1.2 posture. Reviewer can see "users can report (locally), and content gets community-flagged via signed reasons (publicly)." Not a guarantee they accept, but stronger than either alone.
- O27 (deanonymization) — published reasons leak the blocker's identity by design. Make publishing fully opt-in per block, default unpublished. UI must surface this clearly.
- O36 (no global broadcast room) — public block reasons are aimed at the *contact directory*, not at producing a public feed. Worth a note in the row that this is not a town-square primitive.

**Status:** Real design work, not started. Substantial backlog row, not a quick win. The +1/-1 weighting alone is most of an evening's design pass.

---

## NEW: O79 — Public rooms with elected/declared moderators

`[TODO/CODE]` **Closed-membership rooms with an explicit moderation surface, NOT a global town square (O36 still stands).** The room shape:

- **Room creation:** any user creates a room with `{roomId, name, description, createdBy, createdAtMs, signature}` signed by creator's Ed25519 key. RoomId is `SHA-256(name || createdBy || createdAtMs)` for non-collision.
- **Membership:** open (anyone can join), closed-invite, or closed-application (moderator approves). Open is the default; closed rooms get an invite-token signed by a moderator.
- **Posting permission:** member-only, mod-approved-only, or anyone-can-post-but-mod-can-remove. Per-room policy, signed by mods.
- **Moderators:** room creator is mod-by-default. Creator can grant additional mod keys signed by themselves; mods can grant other mods (transitive, but every grant signed and revocable). Alternative path: **member-elected moderators** — members publish signed votes, top-N become mods. Quorum and rotation cadence configurable per room.
- **Moderation actions:** signed `RoomAction { roomId, target, action: REMOVE_MESSAGE | KICK_USER | BAN_USER, reason, modSig }`. Distributed in the room's content stream; peers honour the action if `modSig` verifies against the current mod set.
- **Filtering:** unlike O36's flat global broadcast, room messages are addressed to `roomId`. A node that doesn't follow a room never receives or relays its content. This is the protocol-level "don't like the public room? Don't join it" mechanism.

**Critical distinction from O36:** O36 forbids a *protocol-level distinction* where some traffic class is "public" and gets relayed differently from contact traffic. Rooms are NOT that — they're an *addressing layer* on top of contact-graph traffic. The mesh still only relays messages along contact-graph paths; a room is just "this message is also intended for everyone subscribed to `roomId`." Non-subscribers don't see it at all. **A subscribed-non-subscriber observing room traffic on the wire learns the roomId and per-message metadata; this is the room-existence leak, equivalent to the existing DM-recipientId leak in scope.**

**Apple App Store angle (per CLAUDE.md O68):** open rooms with **elected moderators + per-room block / filter / mod-action surface** are exactly the moderation primitive Apple's 1.2 wants to see. The framing for review: "every public space in this app has a moderator who can remove content within 24 hours, and every user can leave or join any room at will." Even without a server, the user-as-moderator framing satisfies the "objectionable content removal" requirement at the room scope.

**Anti-abuse layer integration:**
- Rooms can subscribe to O67 keyword filters as a *room policy* — every member sees the filter applied.
- Rooms can publish their own block list (sibling of personal blocklists, signed by room mods) so a banned user's content is dropped by every member's client.
- @-mention notifications to mods (proposed in your message) become first-class: `MessageType.MENTION` with `mentionedUserId` field, INFRASTRUCTURE-tier traffic, surfaces in the mentioned user's notification feed.

**Tension with existing decisions, recorded explicitly:**
- **O36:** "Rumor has no global 'public chat' / 'town square' room and will never add one." Rooms as proposed here are NOT global town squares — they're per-room opt-in addressing with moderation. The protocol does not have a "join all rooms" or "discover trending rooms" primitive. Each room must be discovered out-of-band (a roomId is shared by an existing member). **If a discovery mechanism is added later, it crosses into town-square territory and O36's rationale applies.**
- **O71:** local-only broadcast feed UI. Rooms are explicitly the *non-local* version of this — content addressed to a roomId reaches every subscriber across hops, not just immediate neighbors. The two coexist; they serve different needs.
- **O27:** new metadata-leak surface — room membership patterns. Once O53 (sealed-sender) lands, room IDs can be similarly delivery-tagged so observers don't learn roomId-recipient mappings.

**Open design questions for the row:**
1. Moderator key rotation when a mod leaves / is removed / gets compromised — borrows from O41 identity rotation shape.
2. Conflict resolution when two mods take contradictory actions (e.g. one approves, one removes) — last-action-wins by timestamp, or quorum-of-mods. Lean: last-action-wins with a public audit log.
3. Room split (fork): when room policy is contested, a moderator can "fork" a room with the same membership-list seed but a new roomId. Mirrors the BTC fork model.
4. Message edit / delete by author within rooms — already covered by O40 relay-deletion-on-ACK pattern; just needs room-policy bit "allow author delete" to be honoured at relay.

**Status:** Significant design work. Real protocol additions. Worth doing IF the App Store posture matters enough to justify the complexity — this is a "yes for the path through Apple review" item, not a "yes for the protocol's own goals."

---

## NEW: O80 — Battery-percentage-triggered mode transitions

`[TODO/CODE]` **Refinement of O57 modes + O62 ModeProfile: allow per-mode and per-transition battery-% thresholds.** User-configurable: "drop from Free to Static at 60% battery", "drop from Static to Mobile at 30% battery", "disable all relay below 15% battery", etc. Three default profiles plus user-custom:

- **Conservative:** Free→Static at 75%, Static→Mobile at 40%, Mobile relay-only-when-plugged below 20%.
- **Aggressive:** Free→Static at 30%, Static→Mobile at 10%, Mobile keeps relaying to 5%.
- **Plugged-or-bust:** Free only when plugged, Static only when plugged, Mobile otherwise. No battery thresholds.

Hooks into Android `BatteryManager.BATTERY_PROPERTY_CAPACITY` via a periodic check (every 60s is plenty; battery changes slowly). On threshold cross, fire the existing mode-change pulse (per G12) so peers update routing weight immediately rather than waiting for stale signals to decay.

**Open design choices:**
1. Whether to allow user-defined thresholds beyond the three defaults (lean: yes, with sensible bounds).
2. Whether plugged-state combines with battery-% (e.g. "stay in Free mode when plugged AND above 20%") or whether plugged dominates entirely (current implicit). Lean: plugged dominates — a phone on charge is doing what the user wants.
3. Hysteresis: avoid mode flapping at the threshold. Lean: 5% hysteresis (drop down at threshold, return up at threshold+5%).
4. Whether to surface the threshold-cross to the user (toast / silent / settings-only log). Lean: silent by default; settings shows last-N transitions for diagnosis.

**Status:** Pure code. Lands in the same place as O62 ModeProfile. Maybe 2-3 hours of work, much less if O62 isn't fleshed out yet (they should land together).

**Cross-references:**
- O33 battery measurements — the threshold *values* should be informed by measured drain per mode.
- O57 modes + G12 self-presence pulses — uses the existing pulse mechanism.
- O62 ModeProfile — adds `batteryThresholdPercent: Int?` and `belowThresholdMode: UserMode?` fields per profile.

---

## REFINEMENT: O68 — App Store 1.2 reporting (update with O78/O79 context)

Suggest replacing the existing O68 row's "Need-to-verify-later" sentence with:

> **Need-to-verify-later: whether Apple reviewers accept (a) a purely local report queue plus (b) public signed block reasons (O78) plus (c) per-room moderator removals within rooms (O79) as collectively satisfying 1.2's "objectionable content removal" requirement.** Local-only reporting alone is likely insufficient (per docs/RESEARCH_NOTES.md §2); the combined posture is materially stronger. Concrete strategy: signal block reasons + room moderation in the App Store description as "community moderation primitives", treat local-only reporting as the "I want this off my own device" path.

---

## REFINEMENT: O67 — Keyword filters (NSFW/gore as ships-with default)

Suggest extending O67's "Ships with a built-in default slur list" sentence to:

> Ships with built-in default filter lists: (a) **slurs** (English first; localisation gap flagged honestly); (b) **NSFW / sexually explicit content keywords** (default WARN, not BLOCK, on first install — user opts in to BLOCK during onboarding); (c) **graphic violence / gore keywords** (default WARN, not BLOCK). User can promote/demote each on first launch and any time after. Custom lists subscribable as with any other O67 filter. Apple review angle: shipping default content-warning filters out-of-the-box is one of the cheapest moves toward 1.2 compliance.

---

## NOTES ON THE THREE NEW ROWS

**Item count update if all three are accepted:** 67 → 70 open rows. Counts in the existing CLAUDE.md "Open items" intro should bump.

**Priority assessment:**
- O78 (signed block reasons + voting): genuinely new design, significant work, real protocol surface. Worth its own design pass before implementation. Slot somewhere after O68/O67 in priority.
- O79 (rooms with mods): largest of the three. Justified primarily by App Store posture. If iOS / App Store goes from "considered" to "scheduled," this jumps to high priority; otherwise it's a long-cooking design item.
- O80 (battery-% mode transitions): smallest, can land alongside O62 in a single session. Highest immediate value/effort ratio.

**What I'd suggest doing:**
1. Skim each draft for voice and accuracy.
2. Yay/nay each independently — they don't have to all land together.
3. The O67 NSFW addition is the cheapest and clearest — I'd land that alone immediately.
4. O78 and O79 each deserve their own conversation before they ship as committed work.

---

## Files

This proposal: `docs/CLAUDE_MD_PROPOSED_ADDITIONS.md` (new, this doc).
No CLAUDE.md edits yet. Rollback: `rm docs/CLAUDE_MD_PROPOSED_ADDITIONS.md`.
