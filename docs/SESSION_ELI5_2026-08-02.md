# Session ELI5 — 2026-08-02 (autonomous away-mode build)

Plain-English log of everything done while you were away. Newest work at the
bottom. Each entry: what it is, why it matters, where the code lives, and how I
proved it works.

> **Signing convention honoured:** every commit this session is signed
> "By Order Of The High Magnate".

---

## TL;DR of what got built

1. **On-receipt delivery ACKs for DMs** (the thing you asked me to start with).
   When someone receives your direct message, their phone now automatically
   sends back a tiny "got it" note, and your phone learns the message was
   *actually delivered to the person* — not just "handed to a nearby phone".
   Validated in the simulator. ✅ shipped + committed.

2. **Fixed a routing bug where "smart-routed" DMs died just as early as dumb
   ones** (O160). A direct message that knows the way to its recipient is
   supposed to be allowed to travel *twice as far* as one that's just blindly
   flooding the mesh — but a counter bug meant both died at the same 15-hop
   limit, so the smart routing bought nothing. Now smart-routed DMs get their
   full 30-hop reach. Pure protocol fix, sim-validated. ✅ shipped.

3. **Fixed a concurrency crash risk in the backbone coordinator** (O171). A
   shared list was being read and written by three background loops at once
   without a lock, which could throw and silently kill the loop that keeps
   persistent links healthy. Now locked. Pure-core fix. ✅ shipped.

*(Session ended by user 2026-08-03; away mode disabled. Next-up that I did NOT
start: O176 unbounded breadcrumb snapshot map, O177 dead prune code — both
bounded pure-core, no approval needed.)*

---

## 1. On-receipt DM delivery ACK (`DIRECT_ACK`)

### ELI5
Imagine you pass a note to a friend across a crowded room by handing it person
to person. Before today, the most your phone could tell you was "the note left
my hand" — you had no idea if it ever reached your friend. Now, the moment your
friend actually gets the note, they send a one-word "received!" slip back along
the chain of people. When it reaches you, your app knows the note was delivered.

### Why it matters
Rumor is built for a world with no internet infrastructure, where phones are
often switched off to save battery (weeks of survival on a solar trickle). In
that world, "delivered or not?" is the single most important question, and the
network can't lean on servers to answer it. This ACK is the foundation the next
two features need:
- **Retry** (stop re-sending a message once you *know* it arrived).
- **Smart storage** (a relay can throw away a stored message once it's confirmed
  delivered, instead of hoarding it for days).

### What "on-receipt" means (a deliberate choice)
The ACK fires when the message *arrives at the recipient's device* — not when
they open/read it. So it's a "delivered" tick, not a "read" tick. This keeps it
purely in the protocol layer (no UI needed) and honest.

### How it's built (for future-me / reviewers)
- New wire type `MessageType.DIRECT_ACK` (`core/.../model/Message.kt`), tiny
  INFRASTRUCTURE-class control message. Carries the acked message's id in
  `payload.content`; addressed back to the original sender.
- Auto-composed in `GossipEngine.processIncoming` when a DIRECT DM addressed to
  *us* lands. New helper `composeDirectAck(...)`.
- The sender learns of delivery via a new flow `GossipEngine.deliveryReceipts`
  (emits the delivered message's id). This is separate from the existing
  `deliveryEvents`, which only meant "a nearby phone accepted the frame" (a
  weaker, per-hop signal that says nothing about the final recipient).
- Routes/relays exactly like a normal DM (breadcrumb next-hop back to sender).

### Safety properties (all enforced, all tested)
- A phone that is only *relaying* your DM (not the recipient) does **not** send
  an ACK — only the true final recipient does.
- An ACK never triggers another ACK, so there's no infinite ack-of-ack loop.
- The acked id and recipient are inside the signed part of the message, so a
  malicious relay can't redirect the ACK or lie about which message it confirms.
- ACKs never show up in your message list — they're silent plumbing.
- If your DM reaches the recipient by several paths at once, they still ACK it
  exactly once.

### Proof it works
`simulator/.../engine/DirectAckDeliveryTest.kt` runs the *real* engine over the
*real* simulated transport:
1. A sends B a DM → B auto-ACKs → the ACK travels back → A gets a delivery
   receipt for that exact message. ✅
2. A relay that isn't the recipient does **not** ACK. ✅
3. A "teeth" check that the test would actually fail if the receipt were missing.

---

## 2. O160 — routed DMs now actually reach farther than flooded ones

### ELI5
Every message has a "hop budget" — how many phone-to-phone relays it's allowed
before it gives up. Rumor has two ways to move a direct message:
- **Flooding**: shout it in every direction hoping it reaches the person (budget:
  15 hops).
- **Routing**: when phones have learned a breadcrumb trail toward the recipient,
  pass it deliberately along that trail (allowed a bigger budget: up to 30 hops,
  because a deliberate path is worth spending more on).

The bug: the code subtracted from the *flood* budget on **every** hop, even
routed ones. So a routed message ran out of its flood budget at 15 hops and died
— never getting to use the extra 15 hops routing was supposed to grant. Smart
routing gave **zero extra reach**. A test even had a fudged assertion quietly
hiding this.

### The fix
Routed hops now spend only the routing counter and leave the flood budget
untouched. A routed DM can travel its full 30 hops; a flooded one still stops at
15. Fixed the fudged test to assert the budgets exactly (it would now catch a
regression instead of hiding it).

### Where / proof
`GossipEngine.relay()` DIRECT branch (`core/.../protocol/GossipEngine.kt`).
`PerPeerRoutingTest` tightened: a routed hop must leave `floodedHops` and
`hopsToLive` exactly unchanged. Full `:core` + `:simulator` suites green.

---

## Things that need YOUR decision (I did NOT do these — flagged and moved on)

*(populated below as I hit them)*

- **App "delivered" checkmark + delivery-state persistence.** The protocol now
  emits delivery receipts, but showing a "delivered ✓✓" in the chat UI and
  remembering it across app restarts needs a **Room database schema bump** (a new
  `deliveredAtMs` column) + Compose UI work. Per our rule I don't ship schema
  migrations without you. Want me to do the schema bump (dev uses destructive
  migration, so it's low-risk pre-release) next session, or hold it?

- **O193/O202 delivery-hardening full build-out (retry-cancel + on-ack
  eviction).** My DIRECT_ACK just *unblocked* O193's on-ack trigger (the backlog
  literally said "not built: no delivery-receipt message type exists"). The next
  steps — (a) sender stops re-offering a DM once it's confirmed delivered, and
  (b) a relay drops its cached copy of a DM when it sees the matching ACK go by —
  both need decisions only you should make:
  - **Sender-side suppression** needs a "delivered" flag on the stored message =
    the same **Room schema bump** as above.
  - **Relay-side on-ack eviction** is per-message, sender-opt-in, **default OFF**
    per O193 (forward-then-forget fights store-and-forward, the mesh's whole
    value). That means new **policy knobs + a UI toggle** and a choice of
    defaults (the sim recommended bundle is *spray-k + on-ack + TTL*, k≈4,
    X≈24h). I don't want to pick your privacy/deliverability defaults for you.
  - **My recommendation:** next session, do the schema bump once and wire BOTH
    the "delivered ✓✓" UI and sender-side offer-suppression on top of it (safe,
    default-on, pure win). Hold relay-eviction until you've set the policy
    defaults. Flagged, moving on.
