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

2. *(further level-1 items appended below as I work through them)*

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

## Things that need YOUR decision (I did NOT do these — flagged and moved on)

*(populated below as I hit them)*

- **App "delivered" checkmark + delivery-state persistence.** The protocol now
  emits delivery receipts, but showing a "delivered ✓✓" in the chat UI and
  remembering it across app restarts needs a **Room database schema bump** (a new
  `deliveredAtMs` column) + Compose UI work. Per our rule I don't ship schema
  migrations without you. Want me to do the schema bump (dev uses destructive
  migration, so it's low-risk pre-release) next session, or hold it?
