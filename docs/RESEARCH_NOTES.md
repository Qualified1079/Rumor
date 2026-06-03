# Research notes — open questions from CLAUDE.md

Compiled while user was asleep. Sources cited inline. Findings are summaries — read the source for the real text before relying on them for shipping decisions.

---

## 1. O42 / O66 — Negentropy bound-edge fingerprint stability

**Question:** Is there a known stability concern when range boundaries fall on item keys during bisection? What's the canonical fingerprint formula? What does the wire format actually look like?

**Findings (from Nostr NIP-77 spec and hoytech/negentropy reference):**

- **Fingerprint formula:** `SHA-256( sum_mod_2^256(elementIDs) || varint(count) )`. IDs are interpreted as 32-byte little-endian unsigned ints, summed mod 2^256, concatenated with the element count as a varint, then SHA-256'd. Result is the fingerprint (truncated to a fixed width on the wire — exact width is in the v1 spec doc we couldn't fetch directly, but Nostr NIP-77 confirms SHA-256 is the hash).
- **Three modes per range:** `Skip` (mode 0 — no work needed), `Fingerprint` (mode 1 — compare hashes, recurse if mismatch), `IdList` (mode 2 — terminal, send the actual IDs).
- **Bound encoding:** `<encodedTimestamp varint> <length varint> <idPrefix bytes 0..32>`. Timestamps are deltas from the previous bound (offset starts at 0 each message). `idPrefix` is the **shortest prefix needed to separate the first record of this range from the last record of the previous range** — re-implementers must compute this prefix correctly, not just send the full 32 bytes.
- **The bound-edge concern is real but mitigated by design:** because bounds use *prefixes* rather than full IDs, two peers with the same sorted item lists will compute the same minimal prefix and therefore the same range boundaries. The concern surfaces only when peers disagree on which items exist near a boundary — which is exactly the case RBSR is supposed to detect via fingerprint mismatch. **Conclusion: there's no separate "bound-edge instability" failure mode beyond the normal RBSR recursion case.** Our Kotlin impl needs to verify the prefix-shortening rule matches the reference.
- **Documented gotcha:** "If either of the two sync parties use frame size limits, then discovered differences may be added to the have/need multiple times. Applications that cannot handle duplicates should track the reported items to avoid processing items multiple times." Our `GossipEngine.processIncoming` already dedups via `DuplicateFilter`, so we're fine on the receive side.
- **Timestamp infinity sentinel:** the largest u64 is reserved as "+infinity" for the final upper bound. Confirm our `RbsrWire` uses the same convention.

**Action items:**
- Audit `core/sync/Rbsr.kt` against this spec: confirm SHA-256 over `sum mod 2^256 || varint(count)`; confirm bounds use shortest separating prefix not full ID; confirm timestamp delta encoding; confirm u64-max infinity sentinel.
- This is the audit O42 promote-to-default-on gate calls for.
- Re-fetch `docs/negentropy-protocol-v1.md` directly via git clone if WebFetch keeps 404'ing the path.

**Sources:**
- https://nips.nostr.com/77
- https://github.com/hoytech/negentropy
- arXiv 2212.13567 (Meyer)

---

## 2. O68 — Apple App Store 1.2 UGC reporting (does local-only satisfy?)

**Question:** Will reviewers accept a purely device-local report queue, given there is no server?

**Findings:**

- **Apple 1.2 hard-requires four things** for any app with user-generated content (and Apple has explicitly extended this to "random or anonymous chat" apps in recent guideline revisions):
  1. **Method to filter objectionable material.** (Our O67 keyword filters cover this.)
  2. **Mechanism to report objectionable content/users.** (O68 covers this.)
  3. **Mechanism to block abusive users.** (Already shipped — `BlockManager`.)
  4. **Published contact info for support.**
- **Critical Apple expectation we cannot meet as written:** "Remove any reported content and eject the offending user within a one-day timeframe." This implies a *moderation pipeline*. A purely device-local report log does not let any Rumor operator do this because — by design — there is no operator and no server.
- **Implication:** A local-only report queue is necessary but **not sufficient** for 1.2. Likely outcomes:
  - **Rejection** if framed as "we have a report button that does nothing visible to Apple."
  - **Acceptance possible** if framed as "this is a peer-to-peer protocol with no central operator; reports are recorded locally and inform user-side block/filter decisions; the user, not an operator, is the moderator of their own view." Briar and similar apps have shipped on Apple before — research what posture they took.
- **Practical recommendation for the App Store ticket:** make the report action visibly do *something* — auto-add the reported user to the local blocklist with a custom reason, plus offer an "export anonymised report bundle" that produces a signed file the user can email to a designated contact (a maintainer email is fine). The mechanism the reviewer sees in the app must produce a visible outcome, even if that outcome is local-only block.
- **Research-not-conclusive:** I couldn't find a primary Apple statement explicitly covering peer-to-peer-no-server apps under 1.2. Need to either (a) check Briar's iOS posture if/when they ship, (b) ask in the Apple Developer Forums pre-submission, or (c) submit and learn.

**Action items:**
- Frame O68 as: local report log + auto-block + signed-bundle export. Do not frame as: "we record reports, nothing else happens."
- Treat the Apple posture as research-incomplete; expect at least one review round-trip on this guideline.

**Sources:**
- https://developer.apple.com/app-store/review/guidelines/
- https://buddyboss.com/docs/app-store-guideline-1-2-safety-user-generated-content/
- Apple Developer Forums thread (https://developer.apple.com/forums/thread/116703)

---

## 3. O33 — Battery measurement reference numbers

**Question:** What does the field literature say about continuous BLE scan + Wi-Fi Direct discovery drain on phones?

**Findings:**

- **`SCAN_MODE_LOW_LATENCY`:** 30–80 mA continuous on a mid-range Android device. One hour ≈ 10–15% of a 3500 mAh battery. **This is the upper bound** — we should never use `LOW_LATENCY` for continuous background scan.
- **`SCAN_MODE_BALANCED`:** roughly half of `LOW_LATENCY` based on third-party measurements.
- **`SCAN_MODE_LOW_POWER`:** measured ~37 mA on the AltBeacon reference test (default battery manager settings) vs ~90 mA foreground unconstrained. ~60% reduction. This is the right default for Mobile-mode.
- **24-hour continuous BLE scan is essentially unachievable on a stock phone** without aggressive duty-cycling and FGS_CONNECTED_DEVICE; even then OEM process-killers (Samsung One UI, Xiaomi MIUI, Huawei EMUI) actively defeat it. PendingIntent-based scan + WorkManager wake-cycles is the pattern that survives.
- **Briar self-reports excessive battery as their #1 complaint** (their issue #44, still open). We will not be magically better.
- **Implication for O33 + O57 modes:**
  - **Mobile-mode default:** `LOW_POWER` + 30s scan / 5min sleep duty cycle. Estimate ~5–8% per hour (≈8 hours on a fresh charge, half-day on 50% start). Honest framing in UI: "Mobile mode is balanced for everyday use; expect to leave the app running 6–10 hours per day on battery."
  - **Static-mode default:** `BALANCED` + longer scan windows, motion-aware throttle. Estimated ~10–15% per hour. Honest framing: "Static mode prefers being plugged in."
  - **Free-mode default:** `BALANCED` continuous, no throttle. ~20% per hour. Honest framing: "Free mode is plug-in-only."
- **All these are honest estimates with ±5% bars until we have real device measurements.** O70 (user-controlled scan interval) should show "≈8%/hr (approximate)" in UI copy until O33 measurement data lands.

**Action items:**
- O62 ModeProfile should capture per-mode `scanMode`, `scanWindowMs`, `scanIntervalMs`, and a `estimatedBatteryPctPerHour: Float` field. UI consumes the estimate to render honest annotations.
- Pre-O33 the estimates are placeholders; the field exists so we don't have to refactor when real numbers arrive.

**Sources:**
- https://altbeacon.github.io/android-beacon-library/battery_manager.html
- https://bleadvertiserapp.medium.com/why-your-ble-app-is-draining-battery-and-the-scan-strategy-that-fixes-it-2a10d904febf
- https://novelbits.io/ble-power-consumption-optimization/
- https://code.briarproject.org/briar/briar/-/issues/44

---

## 4. O74 — Non-Play Android stores (broader list and review climate)

**Question:** Is there a long tail of regional/niche stores worth submitting to?

**Findings — additions to the list in CLAUDE.md:**

- **Aurora Store** — not a store per se; it's an anonymous Play Store client. Anyone using Aurora is reaching Play content; we get there automatically by submitting to Play.
- **Droid-ify / Neo-Store / AuroraDroid** — F-Droid *clients*, not separate stores. Submission to F-Droid covers them.
- **Obtainium** — direct-from-GitHub-releases updater. **Zero submission effort:** just publish signed APKs as GitHub releases and add an "install via Obtainium" link in the README. Strong reach in the GrapheneOS / de-Googled crowd, which is our most aligned user base.
- **IzzyOnDroid** — F-Droid sibling repo; accepts apps F-Droid doesn't. Submission is a GitLab MR adding a manifest entry pointing at GitHub releases. Reaches ~1,300 apps' worth of users F-Droid doesn't.
- **APKMirror** — mirrors-by-curation, not a submit-to store. They pick up popular APKs from Play. Zero submission effort.

**Implication:** The "long tail" of stores in CLAUDE.md is mostly noise; the actual high-leverage non-Play targets are **F-Droid + IzzyOnDroid + GitHub releases (for Obtainium)**. Aurora handles Play indirectly. The regional stores (Huawei AppGallery, Xiaomi GetApps, Samsung Galaxy Store) are real submissions with their own per-store review — those need a separate research pass per-jurisdiction-per-store-per-year and are not worth doing speculatively.

**Action items:**
- Reorder O74 priority list: **F-Droid → IzzyOnDroid → GitHub-releases-with-Obtainium-link**; everything else is exploratory.
- The "Chinese App Store + Tencent MyApp = probably-infeasible" framing in CLAUDE.md is correct — confirmed by general 2026 sentiment, not by a specific source.

**Sources:**
- https://www.howtogeek.com/skip-google-play-store-android-alternatives-that-actually-respect-your-privacy/
- https://factually.co/product-reviews/electronics-tech/safest-f-droid-alternatives-aurora-store-droid-ify-obtainium-2026-79a2b6
- https://discuss.grapheneos.org/d/18868-izzyondroid-repo-vs-github-vs-aurorastore-for-app-downloads

---

## 5. O50 — Bitcoin Core N-of-M co-signed release reference

**Question:** What's the actual mechanism Bitcoin Core uses, for the O50 "high-capability plugins co-signed by ≥2 maintainer keys" design?

**Findings:**

- **Bitcoin Core uses Guix (and historically Gitian) for reproducible builds.** Source → identical binaries across independent builders.
- **Builder-keys are public**, kept in `bitcoin-core/guix.sigs/builder-keys/`. Each release tag accumulates signatures from independent builders.
- **Release threshold: ≥3 matching builder signatures** before code-signing keys produce the final Windows/macOS binaries. Linux binaries are reproducible-and-signed without code-signing keys.
- **Two-stage process:** (a) builders compile from source and publish SHA-256 hashes + signatures; (b) the signed code-signing keys produce the final platform-signed binaries from the agreed-upon reproducible builds.

**Implication for O50:**
- Adopt the same shape for Rumor APK-tier plugins: plugin manifest declares N maintainer pubkeys + threshold M; plugin update requires M-of-N signatures over the bundle SHA-256.
- **Reproducible builds (O49) are a hard prerequisite** — without reproducibility, "two maintainers signed the binary" only proves they signed *something*, not that the something matches the published source.
- **Storage of maintainer keys is the open design question.** Bitcoin's model uses GPG keys held independently by each maintainer; their compromise model is "compromising 3+ independent humans is hard." Our model needs equivalent independence (different orgs, different countries, different threat profiles per maintainer).

**Action items:**
- O50 implementation order: O49 reproducible builds → maintainer key declaration format → signature collection workflow → plugin loader signature verification.
- The Bitcoin Guix sigs repo is the model to mimic; we don't need to invent a new mechanism.

**Sources:**
- https://github.com/bitcoin/bitcoin/blob/master/doc/release-process.md
- https://github.com/bitcoin-core/guix.sigs

---

## 6. O72 — Nostr NIP-17/NIP-44/NIP-59 for Rumor message carriage

**Question:** What's the right Nostr event shape for Rumor messages riding Nostr relays as a fallback transport?

**Findings:**

- **NIP-44 v2** is the current Nostr encryption standard (ChaCha20 + HMAC-SHA256, ECDH on secp256k1). Mature, well-reviewed.
- **NIP-17 + NIP-59 (gift wrap)** is the privacy-preserving DM construction: rumor (unsigned event) → seal (NIP-44 encrypted, signed by sender) → gift wrap (NIP-44 encrypted to recipient, signed by ephemeral key). **The point of gift-wrapping is that the relay never learns the sender's identity** — only the recipient does, after unwrapping. **This is exactly the metadata model Rumor wants** for cross-mesh DMs.
- **Implication for O72:**
  - Rumor messages carried over Nostr should be **wrapped in NIP-59 gift wraps**, not raw events. Each Rumor DM becomes a gift-wrapped event addressed to the recipient's Nostr key (which is a separate identity from their Rumor key — see below).
  - **Identity mapping problem:** Rumor uses Ed25519 + X25519 (NaCl). Nostr uses secp256k1 (Bitcoin curve). The curves don't share keys. Each Rumor user who wants Nostr-relay fallback needs to publish a *signed mapping*: "my Rumor userId X corresponds to my Nostr pubkey Y." Mapping is a Rumor-signed message gossiped over the mesh.
  - **Outer event kind:** suggest a custom NIP kind in the experimental range (e.g. `kind: 30077` or whatever isn't taken) so existing Nostr clients ignore Rumor traffic. **Do not reuse `kind: 1` (text note)** — that pollutes Nostr feeds with binary Rumor blobs.
  - **Tor or I2P underneath is non-negotiable** for the metadata-leak posture this is supposed to provide.

**Action items:**
- Pre-O72 research: NIP-17/44/59 reference impls in `nostr-tools`/`rust-nostr` for the gift-wrap construction.
- Decide Nostr-event `kind` number before any wire code. Reserve it in `docs/RENAMED_FIELDS_NEVER_REUSE.md`.

**Sources:**
- https://nips.nostr.com/17
- https://nips.nostr.com/44
- https://nips.nostr.com/59
- https://github.com/nostr-protocol/nips/blob/master/17.md

---

## 7. O73 — mDNS/Bonjour Android↔iOS interop

**Question:** Is mDNS/NSD viable as a same-LAN transport for Android↔iOS?

**Findings:**

- **Android NSD = iOS NSNetService** speak the same wire protocol (mDNS / DNS-SD). Genuine interop, no shim layer needed.
- **Android-only quirk:** the `local.` resolver only became available app-side around Android 12+ (`NsdManager.resolveService` works on older but mDNS hostname resolution is patchy). For Android <12, our app must implement its own resolver (or include a small bundled mDNS lib).
- **iOS NSNetService** has been deprecated in favor of **`NWBrowser`/`NWListener`** (Network framework). For an iOS port, use Network framework, not NSNetService.
- **Common gotcha:** captive-portal / guest-network "client isolation" mode blocks all client-to-client traffic including multicast. **mDNS transport must gracefully fall back to BLE in this case** rather than appear broken.
- **Privacy gotcha:** every peer publishes a service record on the LAN. Anyone scanning sees them. **mDNS leakage profile is worse than BLE adv** (more bytes leaked, longer-lived records). Should be off by default; opt-in per-network.

**Implication for O73:**
- Viable, real interop path.
- Should be opt-in per Wi-Fi network ("trust this network for mDNS discovery").
- Code path: `TransportConfig.mdnsTransport: Boolean` defaulting `false`; user toggles per saved Wi-Fi SSID.

**Sources:**
- https://www.esper.io/blog/android-dessert-bites-26-mdns-local-47912385
- https://developer.android.com/develop/connectivity/wifi/use-nsd
- https://jaanus.com/implementing-bonjour-across-ios-and-android/

---

## Questions that did NOT resolve and need user input later

1. **Apple posture on 1.2 for serverless-by-design apps** — no primary source available; we will learn on submission.
2. **Field-measured battery numbers on representative Android devices** — no public dataset; needs O33 measurement campaign.
3. **Sybil-attack cost estimates for BLE adjacency observation** — no clean academic source; existing $1000 figure in O27 is hand-waved and probably low.
4. **Regional non-Play Android store review climates** — varies by store-by-jurisdiction-by-year; only researchable on submission attempt.

These are not resolvable from the web at this hour; recording them so they don't block other work.
