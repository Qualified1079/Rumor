# Handoff — away-mode privacy/security workshop (2026-07-30)

Away mode ON (`~/.claude/away-mode`). Design/workshop session — **docs only, no
code**. Filed backlog rows O189–O201 from a privacy/security brainstorm with the
user, and created `docs/SECURITY_RESEARCH.md`. All pushed to `main`.

**DO THIS FIRST next session — systematic security review (user priority, 2026-07-30):**
The user flagged this as important and high on the list. Before more feature work,
run the standards-driven review captured in **`docs/SECURITY_RESEARCH.md`** (it
exists as of this session — extend it, don't recreate). Highest-value passes, in
order: (1) the signature-transcript canonicalization class — sweep every
`*SignableBytes` helper AND add a test that FAILS on any new bare-delimiter
transcript, so O144/O156/O157's bug class can't recur a *third* time; (2) a
formal LINDDUN (privacy) + STRIDE (security) threat-model artifact; (3) the
crypto-primitive audit (AES-GCM nonce uniqueness, constant-time tag/MAC compares,
HKDF/domain-tag coverage, secret zeroization — O115/O121(c) started); (4) a
DoS/resource-bound sweep over every untrusted-input path (O166/O172/O185/O176);
(5) plan an external crypto audit before any real deployment. The doc also flags
vetted constructions to evaluate vs the hand-rolled ones (Noise for HELLO, MLS
for Rooms *before* O187 builds them out, HPKE for the sealed envelope, Signal
ratchet for O191).

**Filed this session (docs-only; full bodies in `docs/OPEN_BACKLOG.md`):**
O189 three-mode duress · O190 ephemeral/RAM-only mode · O191 ratcheted DM path
(Double Ratchet — **NOT committed, de-risk in sim first**; would supersede
O38/absorb O53 + give repudiable auth, but forks DMs off the O65 signed wire and
breaks O16 per-sender rate-limiting) · O192 key-retirement beacon · O193
relay-ephemeral DM caching · O194 [DECISION] eclipse = trust-weighted view ·
O195 traffic-analysis resistance (padding + delay + mix; togglable, mode-suggested
not -locked) · O196 [DECISION] reject handheld cover traffic + adaptive fill ·
O197 limited onion routing (known contact-relays only) · O198 breadcrumb
minimization · O199 lock/device-access hardening · O200 notification privacy
controls (UI, in `docs/UI_BACKLOG.md`, folded under O143) · O201 [DECISION+RESEARCH]
post-quantum hybrid for the DM/prekey path.

**Cheapest early win among these:** O198 (breadcrumb minimization) — pure
`:core`/sim, no wire or crypto change, and the result directly informs O53 + O31.
O193/O197/O195 share one dependency: a connect/disconnect/new-peer *churn* sim
harness — build it once, unblock three rows.

**IN PROGRESS — signature-transcript canonicalization sweep (O156, do-first #1):**
Started the enumeration but did NOT finish classifying (session ended). The full
list of `*SignableBytes` / transcript builders to inspect for the O144-class
bare-delimiter splice (adjacent variable-length/free-text fields joined by a
delimiter the field content can itself contain → re-partitionable while the
byte-sequence and thus the signature stay valid):
  - `MessageStore.signableBytes` (`protocol/MessageStore.kt:332`) — DONE (O144, v2
    length-prefixed, `rumor-msg-v2:`). The reference pattern to copy.
  - `keywordFilterListSignableBytes` (`model/KeywordFilter.kt:136`) — KNOWN VULN
    (O156); free-text `name`/`pattern` between bare `|`. FIX FIRST.
  - `roomCreateSignableBytes` / `roomInviteSignableBytes` / `roomActionSignableBytes`
    (`model/Room.kt:142/162/178`) — LATENT per O156/O157 (free-text between bare
    delimiters; unwired today, live the moment O79/O187 land).
  - Still UNCLASSIFIED (next session — read each body, confirm whether every field
    is fixed-shape hex/base64/decimal/enum (splice-safe) or has adjacent free-text
    (vuln)): `messageDeleteSignableBytes` (MessageDelete.kt:66),
    `blocklistSignableBytes`/`blocklistDiffSignableBytes` (Blocklist.kt:59/70),
    `roomPostingCertSignableBytes` (RoomPostingCert.kt:68),
    `bridgeVouchedSignableBytes` (BridgeVouched.kt:73),
    `prekeyPublishSignableBytes` (Prekey.kt:70),
    `multiRecipientEnvelopeSignableBytes` (MultiRecipientEnvelope.kt:122),
    `helloChallengeBytes`/`V2` (GossipPacket.kt:201/229).
  - Also non-signature but domain-tag SHA-256 inputs to eye for the same
    re-partition risk: `RoomRoutingTag` (protocol/RoomRoutingTag.kt — see O157,
    the tag isn't in the signed transcript at all), `Rbsr` fingerprint
    (sync/Rbsr.kt:286).
The deliverable is BOTH the point-fixes AND a test that FAILS on any new
bare-delimiter transcript, so this class can't recur a third time. Then record
the classification table in `docs/SECURITY_RESEARCH.md`.
