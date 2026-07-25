# Moderation, authority & governance in decentralized / mesh networks — prior-art research

Literature/prior-art survey for Rumor's Rooms authority model. Written to
the O135 "Literature grounding" style: named system → the mechanism in a
few sentences → what's transferable to Rumor → where the analogy breaks,
honestly (mirrors the O27/O60 no-overclaim posture). Sources cited inline
with URLs. Findings are summaries — read the primary source before shipping
a decision on top of one.

Cross-refs in `CLAUDE.md`: O79 (Rooms shape), O89 (posting-cert write
enforcement, largely unwired today), O36 (rooms-on-by-default, global room
text-only), O135 (sybil framing + web-of-trust vouch chain), O78/O67
(subscribable signed lists). Rumor-side docs: `docs/O79_PROTOCOL_SPEC.md`,
`docs/ROOMS_THREAT_MODEL.md`.

---

## 0. The problem, and the Rumor lens

**The problem:** enforceable authority over shared *mutable* state — who
may post, who may kick, who is a moderator — on a network that is
simultaneously (a) **serverless** (no referee to adjudicate), (b)
**eventually-consistent** over gossip (nodes hold only local + partial
topology views, messages arrive out of order and late), (c)
**sybil-cheap** (identities are free Ed25519 keypairs; any identity-keyed
ban is defeated by reminting — O135), and (d) **clockless** (an HLC gives
a causal/total order for *ordering* but not trustworthy wall-time, and
ordering ≠ authority semantics). Every centralized moderation design
assumes away at least three of these four. The interesting prior art is
the handful of systems that assume away none.

**The Rumor lens** — the constraints every "for Rumor" note below is
measured against:

- No central server; no global clock; HLC for causal ordering only.
- Free identities → "make sybils *harmless*, not impossible" (O135).
- Rooms are OPEN (plaintext signed broadcast to a routing tag) or
  ENCRYPTED (multi-recipient envelope, per-message *fixed* membership —
  the MLS-epoch analogue; see §6).
- **Authority is modeled as an authored, signed, append-only op-log kept
  SEPARATE from message ordering — deliberately NOT state-resolved over
  the message DAG.** This is the "Matrix state-resolution inoculation"
  (§1) and is the single most load-bearing design commitment here.
- **Working design direction under evaluation** (confirmed/refined/
  contradicted against the literature in §11):
  1. integer/assignable **authority levels**;
  2. the **capability-attenuation invariant** — you may only act on or
     assign a level *strictly below your own* at that op's causal
     frontier;
  3. **invite-provenance** as the anti-sybil lever — membership = a
     signed invite tree rooted at founders; ban = subtree prune;
     revoke-invite-ability = kill-link;
  4. **headless-by-default** when the owner is absent — never infer
     succession from silence (FLP); explicit owner-signed retirement
     event activates a named succession list;
  5. **bans/kicks/timeouts = one Revoke op** differing only in scope +
     optional expiry.

---

## 1. Matrix — `m.room.power_levels` + state resolution v2

**Mechanism.** Matrix models authority as `m.room.power_levels`: a state
event mapping userId → integer power level, plus integer thresholds for
each action (send this event type, kick, ban, redact, change the levels
themselves). An action is authorized iff the actor's integer ≥ the
threshold, and you can only set another user's level to ≤ your own. This
is a clean, well-specified **integer authority model** and it is directly
the ancestor of Rumor's "assignable levels + assign-only-below-yourself"
direction.
(https://spec.matrix.org/v1.4/rooms/v2/,
https://spec.matrix.org/unstable/rooms/v1/)

The hazard is the *other* half: **state resolution v2**. Matrix stores all
room state (including power levels) as events in a federated DAG. When
partitioned servers reconverge, conflicting state tuples are resolved by
an algorithm over the DAG — reverse-topologically order the "control"
(auth/power) events with tie-breaks (higher power first, then earlier
timestamp, then lexicographic event ID), build a power-level "mainline,"
then order normal state events by which power-level epoch they hang off.
(https://matrix.org/docs/older/stateres-v2/,
https://spec.matrix.org/books/server/state/v2.html)

**What goes wrong — "state reset."** Because authority itself lives in the
DAG and is *recomputed* on merge, a late-arriving or fork-isolated branch
can cause the resolved state to silently revert: bans undone, a room
suddenly re-privileging a removed user, power levels snapping back to an
older value, topic/name resets. The canonical failure: fork A contains
"give Bob power → Bob gives Charlie power → Charlie acts"; a reconverging
fork B never saw the first grant, so on merge Charlie's action fails
authorization and its downstream state evaporates. State resets **still
happen in v2 rooms** years later (synapse#8629, synapse#15987), and the
fixes are ongoing — MSC4297 / "State Res v2.1" / Project Hydra.
(https://github.com/matrix-org/synapse/issues/8629,
https://github.com/matrix-org/synapse/issues/15987,
https://matrix.org/blog/2025/08/project-hydra-improving-state-res/,
https://matrix.org/docs/spec-guides/state-res-2.1/)

**Transferable to Rumor:** the integer-level model and the
"set-only-≤-your-own" rule — take both. They are simple, auditable, and
map onto object-capability attenuation (§7).

**Where it breaks / the inoculation:** *do not put authority in the
message DAG and do not re-resolve it on merge.* The state-reset class is
endemic precisely because authority is derived state that gets
recomputed. Rumor's decision to keep authority as a **separate authored,
signed, append-only op-log** (each op names its author and its causal
frontier, merge is set-union + deterministic replay, never
re-adjudication) is the direct countermeasure — it converts "recompute
who had power at this point in a contested DAG" into "replay this
explicit signed grant/revoke stream." An op is valid or not *at the
moment it was authored against its stated frontier*, and stays valid;
later-arriving ops append, they don't retroactively un-authorize a past
op. This is strictly easier to get right than state-res v2, and Matrix's
decade of state-reset bugs is the empirical argument for the split.
Caveat, stated honestly: the split doesn't make *concurrent contradictory
authority ops* disappear (two mods with equal level, one bans X while the
other unbans X, concurrently) — it just moves that from "silent global
reset" to "a local, deterministic, auditable last-writer/priority tie-break
on two visible signed ops." That residual is real and is an open question
(§11); the win is that it's bounded and inspectable rather than emergent.

---

## 2. Secure Scuttlebutt (SSB) — subjective moderation, no global authority

**Mechanism.** SSB is append-only signed logs replicated along a
follow-graph within N hops. There is **no global state and no central
arbitration**: each peer's view is a pure function of *its own* follows
and blocks. Moderation is **subjective** — "block" and "unfollow" are
personal acts that shape only the blocker's replication set; a block can
also stop replication of the blocked feed to others who route through
you, but there is no network-wide "this user is banned."
(https://conferences.sigcomm.org/acm-icn/2019/proceedings/icn19-19.pdf,
https://jaygraber.medium.com/designing-decentralized-moderation-a76430a8eab)

**Transferable to Rumor:** SSB is the reference design for the *personal*
layer Rumor already has — the O78/O67 subscribable signed blocklists +
keyword filters, and the O135 "hops" follow-frontier. The key insight
Rumor already absorbed (O135 DECISION): **port SSB's FOLLOW/hops semantics
but NOT its forced-transitive BLOCK inheritance** — Rumor keeps
opt-in-subscribe blocklists, which are strictly more controllable.

**Where it breaks:** SSB has *no shared-room authority at all* — that's a
deliberate non-goal, not a gap it solved. It answers "what do *I* see,"
never "who may post in *this shared room*." Rumor's Rooms need the second
thing (a moderated global/public room, O36), and SSB offers no mechanism
for it — the subjective model cannot, by construction, produce a common
enforcement anyone can rely on. So SSB grounds Rumor's *display-filter*
layer and nothing about the *Room-authority* layer. Honest limit: the
critique in the literature (fragmented realities / echo chambers) is a
real property of purely-subjective moderation and applies to Rumor's
personal layer too — it's a tradeoff Rumor accepts for the personal
layer, which is exactly why Rooms need a *separate* objective-ish
authority layer on top.

---

## 3. Cabal — closest deployed prior art (subjective + signed mod log)

**Mechanism.** Cabal is an offline-first p2p group chat (the Cable
protocol: signed documents synced peer-to-peer). Its moderation is
**subjective with three roles — admin / moderator / user (and a
ban/hide primitive)** — but crucially "you are always a moderator from
your own perspective." Any user can grant admin/mod to anyone or ban
anyone; the effects are visible only on their own device *until* someone
else adopts them as an authority. The killer feature is **piggybacking**:
you can designate Joe as a moderator *for you*, and thereafter Joe's
public block/mod actions are automatically applied to your own view. Mod
actions are cryptographically signed documents synced like any other
content.
(https://cabal.chat/help.html,
https://github.com/cabal-club/cable,
https://medevel.com/cabal/)

The formal backbone is cblgh's **TrustNet** (2020 master's thesis):
trust assignments `(source, target, weight)` are combined from a
subjective *trust root* into a transitive trust graph; "ranking
strategies" convert the derived trust into a concrete subset of
authorities whose moderation actions you auto-adopt. This is essentially
Advogato/Appleseed transitive-trust applied to moderation.
(https://cblgh.org/trustnet/, https://cblgh.org/dl/trustnet-cblgh.pdf,
https://github.com/cblgh/trustnet)

**Cabal's own writing on why p2p moderation is hard** converges on
Rumor's O135/O27 conclusions: with no central authority and free
identities you cannot *prevent* bad actors, only bound their reach; and
purely-objective network-wide bans are a censorship primitive, so Cabal
deliberately keeps moderation subjective + delegable rather than
absolute.

**Transferable to Rumor:** (1) signed mod actions synced as ordinary
content = exactly Rumor's `RoomAction` on the gossip layer. (2) The
**piggyback / TrustNet delegation model is the same primitive as Rumor's
O135(4) vouch chain and O78 subscribable blocklists** — "adopt Joe's mod
actions" ≡ "subscribe to Joe's signed blocklist." Rumor should recognize
these as one mechanism (this is O140's unification thesis). (3) Cabal is
the strongest existence proof that a *fully offline* signed-mod-log chat
ships and works.

**Where it breaks:** Cabal is *purely* subjective — even its admin/mod
roles are per-viewer opinions, with no room-wide floor. Rumor's Rooms
want a middle path: an **objective, cryptographically-enforced authority
op-log per room** (the invite-tree + posting certs), *plus* the
subjective piggyback layer on top for personal taste. Cabal gives Rumor
the subjective layer's blueprint and the signed-action wire shape, but
not the objective-room-authority layer — which is where Rumor
deliberately goes further than Cabal (and must therefore own the
concurrent-authority-conflict problem Cabal sidesteps by never having
shared authority).

---

## 4. Aether — elected + impeachable moderators (democratic model)

**Mechanism.** Aether is a p2p Reddit: a network of independent,
self-moderated communities with **auditable moderation and mod
elections**. Every action (posts, votes, moderation) is a cryptographically
"minted" (signed) object; nodes only accept properly-minted actions. Mods
are elected by community vote and can be voted out ("impeached"); the full
moderation history is public and auditable, so a mod who acts badly is
visible and removable.
(https://thenewstack.io/aether-a-decentralized-reddit-with-self-moderation-and-privacy/,
https://ricmac.org/2021/07/26/aether-a-decentralized-reddit-with-self-moderation-and-privacy/,
https://aether.app/blog/)

**Transferable to Rumor:** the **auditable-moderation-log** property — all
mod actions public, signed, replayable — is exactly Rumor's authority
op-log design, and Aether is a deployment proof that it's usable. The
"impeach a bad mod" *goal* maps onto Rumor's Revoke op applied to a mod's
authority level.

**Where it breaks — the important negative result:** *voting/elections
require a sybil-resistant electorate, which Rumor does not have and Aether
only weakly has.* One-identity-one-vote over free Ed25519 keys is trivially
sybil-farmed (O135/Douceur). Aether leans on content-graph plausibility +
public auditability to make farming *visible*, not impossible — an honest
mitigation, not a solution, and it's fragile in a small mesh. **Rumor
should NOT adopt election/voting as the authority-acquisition mechanism.**
Rumor's answer is *provenance, not plebiscite*: authority descends an
invite tree rooted at founders (§7), so "who is a mod" is a chain of signed
delegations you can trace and prune, never a headcount you can flood. Take
Aether's auditable-log and impeach-via-revoke; leave the ballot box.

---

## 5. Nostr — NIP-29 (relay-managed groups) & NIP-72 (moderated communities)

**Mechanism.** Two Nostr approaches, both instructive as the *opposite*
of Rumor's constraint. **NIP-29**: a group *lives on a relay*; the relay
enforces membership, roles, and moderation — admins issue role/kick/delete
events (kind:9000 etc.), a join is a kind:9021 request the relay accepts or
defers, and the relay **rejects unauthorized events at the door**. **NIP-72**:
Reddit-style communities where a designated moderator issues an *approval*
event (kind:4550) and clients display only moderator-approved posts —
a post-hoc allowlist, not door enforcement.
(https://nips.nostr.com/29, https://nips.nostr.com/72,
https://nostrbook.dev/groups)

**Transferable to Rumor:** NIP-72's **moderator-approval-as-a-signed-event**
model is a clean pattern Rumor can borrow for OPEN rooms where door
enforcement is impossible (anyone can compute an OPEN room's routing tag
and post — O89): a mod's signed *approval* referencing a message id, which
honest clients require before *displaying/relaying-into-inbox* — spam still
exists on the wire but doesn't surface. This is the O36 "moderated room is
the spam remedy" made concrete without a relay.

**Where it breaks — the core contrast:** NIP-29's strength *is a relay* —
a single, stable, always-online, trusted-by-the-group server that
enforces at ingest. **Rumor has no relay-as-authority and cannot get
one** (O59/O60: the mesh's value is relaying through strangers you don't
trust; a relay that could reject "unauthorized" content is also a
censorship chokepoint and a single point of failure the threat model —
O55 infrastructure-down — forbids). So NIP-29's enforcement model is
exactly what Rumor *can't* use. The transferable residue is only NIP-72's
signed-approval pattern, and even that is *display-layer* in Rumor (honest
peers can be modified to ignore it; enforcement is "honest majority hides
it," never "it can't propagate"). State this limit plainly in any
Rooms UI copy (per `docs/ROOMS_THREAT_MODEL.md`): in an OPEN room, mod
approval curates *what well-behaved clients show*, not *what exists*.

---

## 6. MLS / TreeKEM (RFC 9420) — epochs as fixed-membership

**Mechanism.** MLS organizes a group as a linear sequence of **epochs**.
Within an epoch, an authenticated, *fixed* membership shares one epoch
secret. Membership changes (Add / Remove / Update) are **Proposals** that
take effect only when a **Commit** advances the group to a new epoch with
fresh entropy distributed via the TreeKEM ratchet tree — and, critically,
**fresh entropy reaches only the new epoch's members, not removed ones**.
Removal is enforced by *key rotation*: the removed member simply lacks the
new epoch's keys. RFC 9420 adds external commits/proposals for
join-by-self.
(https://www.rfc-editor.org/rfc/rfc9420.pdf,
https://datatracker.ietf.org/doc/html/rfc9420,
https://www.usenix.org/system/files/sec23fall-prepub-372-wallez.pdf)

**Transferable to Rumor — this is a strong, exact analogy.** Rumor's
ENCRYPTED room envelope (`docs/O79_PROTOCOL_SPEC.md`) is a **per-message
epoch**: each message picks a fresh content key + fresh ephemeral, wraps
it to exactly the *fixed* recipient set of that message, and "kick = omit
from the next envelope's recipient list" is precisely MLS's
"removal-via-key-rotation, entropy withheld from the removed." Rumor's
model is the *degenerate limit* of MLS where every message is its own
epoch — which buys automatic per-message forward secrecy and, crucially,
**no ratchet-catch-up / missed-rotation state to converge**, a real win on
a lossy DTN gossip network where MLS's linear commit sequence would be a
liability (MLS assumes ordered delivery of commits; a mesh can't cheaply
guarantee that). The "authority to change membership" is decoupled from
the crypto exactly as Rumor wants: MLS says *how* to rotate keys on
membership change, not *who is allowed* to propose it — that's the
op-log's job (§1) / MLS's separate authentication-service assumption.

**Where it breaks:** (1) MLS assumes an ordered commit stream and a
Delivery Service; Rumor deliberately abandons the ordered-epoch chain for
independent per-message envelopes — so Rumor gets FS + partition-tolerance
but loses MLS's *shared* group secret and its efficient O(log n) rekey
(Rumor is O(n) key-wraps per message, acceptable at Room scale, not at
1000s). (2) MLS's TreeKEM efficiently rekeys a *persistent* group; Rumor
has no persistent group key to rekey, so the whole tree apparatus is
unused — Rumor took the *epoch-as-fixed-membership* idea and dropped
TreeKEM. (3) MLS says nothing about *authority* (who may remove whom); do
not look to MLS for the op-log semantics, only for the membership-change
crypto pattern.

---

## 7. Object-capability security + SPKI/SDSI — the formal grounding for attenuation

**Mechanism.** In object-capability (ocap) systems, authority *is* an
unforgeable reference, and the defining law is **attenuation / "no
authority amplification": you can only delegate authority you already
hold, and each delegation may only restate or *weaken* it** — never grant
more than you have (POLA, principle of least authority). **SPKI/SDSI**
formalizes this for keys: a principal (a public key) issues signed
authorization certificates delegating an attribute to another key; the
delegate may re-delegate *only within* what it received, and SPKI supports
an explicit **delegation-depth / delegation-control bit** limiting how far
a capability may be re-passed. **UCAN** is the modern p2p restatement
(signed capability chains rooted at a keypair, each link attenuating).
(https://link.springer.com/chapter/10.1007/11863908_11,
https://arxiv.org/pdf/cs/0208028, https://ucan.xyz/specification/)

**Transferable to Rumor — this is the *formal name and proof* for the
working design's capability-attenuation invariant.** Rumor's "you may only
act on / assign a level strictly below your own" **is** SPKI/SDSI
attenuation with integer levels as the attribute lattice. Rumor's **invite
tree rooted at founders is an SPKI/SDSI delegation chain**: each invite is
a signed cert "I (level L) admit you at level < L," re-delegation is
bounded by your own level, and the founder is the self-signed root of
trust (SDSI's "a key is its own certificate authority" — no external PKI,
exactly Rumor's model). Three concrete things to lift:

1. **Attenuation is the invariant to enforce at every honest verifier**,
   not just at compose. A `RoomAction`/invite is valid iff, at its stated
   causal frontier, the author's level strictly exceeds the level being
   granted/acted-upon. This is checkable locally from the op-log with no
   global state — the property that makes it enforceable on a serverless
   mesh.
2. **Delegation-depth control** (SPKI's bit) is the formal shape of
   Rumor's "revoke-invite-ability / kill-link" — a grant can carry
   "may-not-further-invite," bounding tree growth and blast radius.
3. **The chain IS the membership proof** — "ban = subtree prune" is
   literally "revoke a cert and every cert that chains through it becomes
   unauthorized," which SPKI's chain-validity rule already gives you
   (O135's "block-the-signer severs the subtree" restated in ocap terms).

**Where it breaks / caveats:** (1) classic ocap assumes *unforgeable
references in a single trusted runtime*; Rumor is a hostile open network,
so every cert must be signature-verified at every hop and the "reference"
is a signed statement, not a memory-safe pointer — SPKI/SDSI (key-based,
signature-verified) is the right sub-family for exactly this reason, but
it means attenuation is only as good as the verifier honesty
(`ROOMS_THREAT_MODEL.md` still holds: a modified client ignores its own
checks; enforcement is at *honest* peers). (2) **Revocation is ocap's
classic weak spot** — capabilities are designed to be freely delegable,
which makes "un-delegate" hard; SPKI's answer (short-lived certs +
explicit revocation lists) is exactly Rumor's O89 answer (24h posting
certs + `REVOKE_CERT`), so the hard part is shared and unsolved-in-general
— bounded, not eliminated. (3) attenuation gives no *total order* on
concurrent equal-level actions (the §11 open question).

---

## 8. Byzantine-fault-tolerant CRDTs — Kleppmann's line

**Mechanism.** Kleppmann, *Making CRDTs Byzantine Fault Tolerant*
(PaPoC 2022): a **content-addressed hash graph** (each op references its
causal predecessors by hash, and is signed) retrofits Byzantine tolerance
onto existing CRDTs with only modest changes — it tolerates *any* number
of Byzantine nodes (**immune to sybils**, because convergence depends on
the hash-linked causal structure, not on node count/voting) while
preserving Strong Eventual Consistency. The related **Byzantine Eventual
Consistency** framing and the *extend-only directed posets* work
(arXiv:2304.04318) generalize it.
(https://martin.kleppmann.com/papers/bft-crdt-papoc22.pdf,
https://martin.kleppmann.com/2022/04/05/bft-crdt-papoc.html,
https://arxiv.org/pdf/2304.04318)

Kleppmann et al., *A Highly-Available Move Operation for Replicated Trees*
(IEEE TPDS 2021): a CRDT for **moving nodes within a tree** under arbitrary
concurrent moves without introducing cycles and without coordination,
resolving conflicts by **last-writer-wins on globally-unique operation
timestamps**. It explicitly demonstrates that naive concurrent tree-moves
corrupt state (bugs shown in Google Drive/Dropbox).
(https://martin.kleppmann.com/2021/10/07/crdt-tree-move-operation.html,
https://ieeexplore.ieee.org/document/9563274/)

**Transferable to Rumor — this is the formal backbone for the op-log.**
(1) The BFT-CRDT result is the *theorem* behind Rumor's "signed
append-only op-log converges under malicious actors": **content-addressed
hash-linked + signed ops give sybil-immune Strong Eventual Consistency**,
which is precisely the property the authority log needs and precisely why
it can be sybil-cheap-network-safe. Rumor should make each authority op
**reference its causal predecessors by hash** (not just carry an HLC
stamp) — that's what makes replay deterministic and forgery-of-history
detectable, and it's cheap. (2) The **move-tree CRDT is the exact tool for
the invite/authority tree** when authority can be *reparented* (e.g. a
member re-invited under a different sponsor, or a mod moved under a new
mod) — it's the coordination-free, cycle-free, LWW-timestamp answer, and
it warns that hand-rolling concurrent tree edits corrupts state (do not
improvise this; port the algorithm — matches the prebuilt-first reflex).

**Where it breaks / caveats:** (1) BFT-CRDT guarantees *convergence* (all
honest replicas agree on the same op-set and order), **not
*correctness of policy*** — everyone converging on "Charlie's forged grant
is in the log" still converges; the *authorization check* (attenuation,
§7) is what rejects it, layered on top of the converged log. Convergence
and authority are two separate guarantees; Rumor needs both. (2) The
move-tree CRDT resolves concurrency with **LWW on timestamps** — Rumor has
no trustworthy wall-clock (O95), so it must use the **HLC / op-hash tie-break
as the "globally-unique timestamp,"** which gives a deterministic total
order but *not* a semantically-fair one (the later-by-HLC op wins even if
"morally" the other should) — acceptable and standard, but name the limit.
(3) hash-graph history is *extend-only*; you cannot truly delete an
authority op, only append a revocation — consistent with Rumor's model but
means the log grows (O23 eviction pressure applies).

---

## 9. DTN — buffer management & trust-based forwarding under congestion

**Mechanism.** Delay-tolerant networking (store-carry-forward, Rumor's
own O55 regime) has a deep literature on **buffer-management-under-congestion**:
when a node's buffer overflows, which message does it drop? Policies range
over drop-oldest, drop-largest, TTL/size-aware (Range-Aware Drop),
priority-queue-by-message-class (source/relay/destination queues), and
**trust/reputation-based forwarding** that lowers forwarding probability
for low-reputation or malicious relays.
(https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5811009/,
https://pmc.ncbi.nlm.nih.gov/articles/PMC11323192/,
https://link.springer.com/article/10.1007/s11277-023-10373-9)

**Transferable to Rumor:** this is the literature for the *resource* side
of moderation, which O135(2) and O43/O23/O132 already touch — a sybil
flood is a *congestion* attack, and DTN's answer (partition the buffer by
class, bound each class, evict within the low-trust class by LRU) is
exactly O135's "friended peers get guaranteed slots; everyone else shares
a separate bounded LRU pool." The priority-queue-by-class pattern maps onto
Rumor's traffic classes (INFRASTRUCTURE > REALTIME > BULK) and onto the
friended/seen/unknown tiers.

**Where it breaks:** DTN's *trust-based forwarding* proposals mostly assume
identity-stable reputation, which Rumor rejects (reputation on free
identities is sybil-farmable — O135). Rumor keeps the *buffer-partitioning
and drop-policy* half and discards the *per-identity-reputation-routing*
half. Also: DTN buffer management is about *delivery*, not *authority* — it
bounds sybil *damage*, it does not decide *who may moderate*. Keep it filed
under O135/O23/O132, not under the Rooms authority op-log.

---

## 10. Centralized baselines & other prior art (brief contrasts)

- **Discord / Reddit** — the centralized baseline: integer-ish role
  hierarchies (Discord roles + permission bitmasks; Reddit mod lists) with
  a **server as the always-online referee** that enforces at write time and
  holds the canonical state. Everything hard about Rumor's problem is
  *assumed away* by that referee. Useful only as the "what a server buys
  you" contrast: instant consistent enforcement, trivial revocation,
  sybil-resistance via account cost/phone-verify — none of which Rumor has.
  Discord's *permission bitmask* (capabilities as an explicit
  attenuable set, not just a scalar) is a mild argument that Rumor's
  authority might want **(level, capability-set)** rather than a bare
  integer (§11).
- **PGP web-of-trust** — the original signed-delegation-without-CA graph;
  historically grounds SPKI/SDSI (§7) and O135's vouch chain. Lesson mostly
  cautionary: WoT's usability collapse (nobody curated trust signings) is a
  warning that the invite-tree/vouch UX must be near-zero-effort or it
  won't be used.
- **UCAN / cap'n-proto / Macaroons** — modern attenuable-capability-token
  families (Macaroons add *caveats* = attenuation-by-appending-restrictions,
  verified by HMAC chain). Macaroons' caveat model is a clean template for
  "attach a scope/expiry restriction to an invite" (kick=scope-all,
  timeout=scope+expiry — directly the working design's unified-Revoke idea).
  (https://ucan.xyz/specification/)
- **Farcaster / Lens** — on-chain-anchored social graphs; governance leans
  on a blockchain for sybil-cost + canonical ordering. Explicitly *not*
  applicable — Rumor has no chain and the O55 threat model forbids one
  (needs connectivity + is a global-consensus dependency). Filed only to
  record "considered, rejected: no blockchain."

---

## 11. Synthesis — adopt / avoid / open questions

### Adopt (confirmed by the literature)

1. **Integer authority levels + assign-only-below-yourself** (Matrix §1,
   SPKI/SDSI attenuation §7). Confirmed and *formally grounded*: this is
   textbook ocap attenuation. Keep it.
2. **Authority as a separate signed, hash-linked, append-only op-log —
   NOT state-resolved over the message DAG** (Matrix inoculation §1,
   Kleppmann BFT-CRDT §8). Strongly confirmed: Matrix's decade of
   state-reset bugs is the empirical case *for* the split, and BFT-CRDT is
   the theorem that a signed hash-linked op-log converges sybil-immunely.
   **Add hash-linking of each op to its causal predecessors** (not just an
   HLC stamp) — §8 says that's what makes it BFT.
3. **Invite-provenance / invite-tree as the anti-sybil lever; ban = subtree
   prune; revoke-invite-ability = kill-link** (SPKI/SDSI delegation chains
   + depth-control §7, Cabal/TrustNet §3, O135(4)). Confirmed: this is an
   SPKI/SDSI delegation chain with a depth bit, and it's the *right* answer
   *instead of voting*.
4. **Bans/kicks/timeouts = one Revoke op differing by scope + optional
   expiry** (Macaroons caveats §10, MLS removal §6). Confirmed and
   elegant: this is caveat-attenuation. A kick is Revoke(scope=all,
   expiry=∞); a timeout is Revoke(scope=all, expiry=T); a channel-ban is
   Revoke(scope=#chan). For ENCRYPTED rooms, enforcement rides MLS-style
   membership-omission (§6); for OPEN rooms it rides
   NIP-72-style honest-client display-suppression (§5) — be honest that
   the OPEN-room case is display-layer, not door-enforced.
5. **Headless-by-default when the owner is absent; explicit owner-signed
   retirement activates a named succession list; never infer succession
   from silence** (FLP / failure-detector impossibility). Confirmed by
   first principles: you *cannot* distinguish "owner offline" from "owner
   partitioned" from "owner dead" on an async network (FLP; the
   partition-heal case is exactly Matrix's state-reset trigger). Inferring
   succession from silence would be the FLP violation and would let a
   partition manufacture a fake succession. The explicit owner-signed
   retirement event is the only safe activation — it's an *authored op*,
   so it lives in the op-log and converges like everything else.

### Avoid (contradicted or unsupported by the literature)

- **Elections / voting for authority acquisition** (Aether §4) — CONTRADICTS
  a naive read of "democratic governance is more legitimate." One-key-one-vote
  is sybil-farmable on free identities (Douceur/O135). Use invite-provenance,
  not ballots. (Aether's *auditable log* and *impeach-via-revoke* survive;
  its *electorate* does not.)
- **Relay-as-authority / door enforcement** (Nostr NIP-29 §5) — inapplicable
  by construction; Rumor has no trusted always-online relay and O55/O59/O60
  forbid introducing one.
- **Re-resolving authority over the message DAG** (Matrix state-res §1) —
  the explicit anti-pattern. Do not.
- **Per-identity reputation routing** (some DTN §9) — sybil-farmable; keep
  DTN's buffer-partitioning, drop the reputation-routing.
- **Blockchain-anchored governance** (Farcaster/Lens §10) — connectivity +
  global-consensus dependency the threat model forbids.

### Open questions the design still has to resolve

1. **Concurrent contradictory equal-level authority ops.** Two mods at the
   same level, concurrently: A bans X, B unbans X (or A and B both edit the
   level of X to different values). The op-log converges on *both ops
   existing*; the *effect* needs a deterministic tie-break. Candidates from
   the literature: LWW on HLC/op-hash (Kleppmann move-tree §8 — deterministic
   but not "fair"); last-action-wins + public audit log (the O79 lean);
   higher-level-wins-then-hash. **Recommendation:** deterministic
   HLC-then-op-hash LWW *plus* surface both ops in an audit view (Aether's
   auditability §4) so the tie-break is inspectable, not silent. This is
   the one place Rumor's split (§1) reduces but does not eliminate the
   hazard — name it in the spec.
2. **Invite-tree vs open-join.** The invite-tree (§7) is the anti-sybil
   spine, but O36 wants public rooms *on by default* and a global room
   *anyone* can post to. These are in tension: a global open-join room has
   no invite provenance to prune. **Resolution direction:** two regimes —
   invite-tree rooms (authority = provenance, strong) and open-join rooms
   (authority = NIP-72-style mod-approval display-suppression + O135
   personal filters, weak-but-honest). The global room is explicitly the
   weak regime (O36 already accepts this: text-only, no creator-mod at
   scale). Don't pretend the open-join room gets invite-tree guarantees.
3. **Timeout expiry without a trusted wall-clock.** A Revoke with
   `expiry=T` needs "T has passed" to be agreed — but O95 says wall-time is
   untrustworthy post-collapse. HLC is causal, not wall-clock, so "expire
   after 24h" has no trustworthy reference. **Candidates:** (a) express
   expiry in *causal* terms where possible (expires after N of the mod's
   subsequent ops / at the next posting-cert renewal cadence); (b) accept
   loose local-wall-clock expiry for soft-moderation (timeouts are
   low-stakes; the O89 24h posting-cert already leans on local clock and
   `ROOMS_THREAT_MODEL.md` flags it as an open question); (c) for
   hard-security expiry, prefer explicit `REVOKE_CERT` events over
   time-based expiry. **Recommendation:** causal/renewal-based expiry for
   anything security-load-bearing; local-wall-clock only for cosmetic
   timeouts, documented as best-effort.
4. **Owner succession legitimacy under partition.** The owner-signed
   retirement + named-successor-list (§5, confirmed) still has an edge: if
   the retirement event and a *concurrent* owner action are in different
   partitions, or if the successor list is edited concurrently with
   retirement, whose op wins? Falls back to open-question #1's tie-break,
   but on the highest-stakes op in the system. Consider requiring the
   retirement event to name a *specific* successor + a monotonic
   succession-epoch counter so a replayed/partitioned old retirement can't
   resurrect a stale successor.
5. **Level scalar vs (level, capability-set).** Discord's permission
   bitmask (§10) + Macaroon caveats (§10) suggest authority might want to
   be an attenuable *set* of capabilities, not just a scalar integer —
   e.g. "may kick but may not re-level mods." A bare integer conflates
   orthogonal powers. Open: is the added complexity worth it, or does a
   small fixed ladder of integer levels (member < poster < mod < admin <
   owner) cover the real cases? Lean: start integer (simplest, Matrix-proven),
   leave room in the op schema for a capability-set later (additive, like
   `_ext`).

### The one-line honesty note (per O27/O60)

Rumor's Rooms authority is *stronger than SSB/Cabal* (it has an objective,
cryptographically-attenuated, hash-linked op-log, not only per-viewer
opinion) and *weaker than Matrix/Discord/NIP-29* (no referee, so OPEN-room
"moderation" is honest-client display-suppression, not door enforcement,
and equal-level concurrent conflicts get a deterministic-but-not-fair
tie-break). Never claim door enforcement for OPEN rooms; never claim
sybil-*proof* authority (only sybil-*bounded* via provenance); never claim
the op-log resolves *policy* correctness (it resolves *convergence*;
attenuation resolves authorization; the two are separate guarantees).

