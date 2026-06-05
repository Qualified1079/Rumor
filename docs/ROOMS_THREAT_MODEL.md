# Rooms — threat model

Rumor has no central enforcer. Anything labelled a "permission" in a
Room has to be enforced cryptographically at every honest peer's
machine independently — or it isn't enforced at all.

This document maps each defense Rooms ship with to (a) what it
defends against, (b) what it does NOT defend against, and (c) the
specific user-visible behaviour a malicious-client attacker can
still get.

## The protocol design rule

Every security-affecting policy MUST be:

1. **Cryptographically expressed** — a property of the bytes on the
   wire (signature, encryption, certificate), not of which code
   path the client app happens to take.
2. **Verified at every honest hop** — not just at the endpoint, so
   modified clients can't propagate via well-behaved relays.
3. **Independent of UI code** — if a user's choice to display or
   hide content is "the enforcement," the enforcement is local-only
   and a modified client trivially ignores it.

If a policy can't be expressed this way, document the limit
honestly and don't pretend it's enforceable.

## What structural enforcement defeats

### A modified client posting to a read-only channel

WRITE permissions are expressed as **posting certificates** — signed
envelopes saying "userId X is authorized to post to channel #C in
room R until time T," signed by an authorized moderator of R.

  - Honest peers verify, for every inbound channel message: the
    attached certificate is mod-signed, unexpired, and names the
    same userId that signed the message.
  - A modified client without a certificate can compose anything;
    every honest peer fails the structural check and drops the
    message before display.
  - The malicious client can show whatever it wants on its own
    screen and hand the bytes to colluding peers, but cannot
    inject the message into the honest mesh.

### A modified client forging a moderator action

`RoomAction` (REMOVE_MESSAGE / KICK_USER / BAN_USER / UNBAN_USER)
is Ed25519-signed by a mod's identity key. Forged actions fail sig
verify at every honest peer. Drop.

### Impersonation of another user

`userId = SHA-256(publicKey)` is a property of the user's key
material. To impersonate, an attacker needs the target's private
key. Modified client doesn't help.

### Flooding past per-sender rate limits

O16 per-sender ingest token bucket is enforced at every honest
peer's `MessageStore.ingest`. A modified client's output is
throttled at every honest hop. They can blast their own machine;
the mesh doesn't carry the volume.

### Reading a channel the attacker wasn't given the key for

READ permissions use per-channel symmetric keys distributed only
to authorized members via per-member key DMs (the O52 pattern
specialized to channel scope). A modified client without the key
sees ciphertext gibberish — there's no client-side decision to
"decide to decrypt." The key material is absent.

## What structural enforcement CANNOT defeat

### An authorized user leaking their own access

If Alice has the channel key, her modified client can hand it to
anyone. Her modified client can screenshot the decrypted text and
post it elsewhere. Once decryption has happened, the content is
plaintext somewhere on Alice's machine. **No protocol can prevent
this.** Discord can't. Signal can't. Anything that puts
plaintext in front of human eyes can't.

  **Mitigation that exists:** periodic key rotation (encryption
  ratchet). When a leak is suspected or a member is removed, mods
  rotate the channel key. Old keys decrypt old messages; new
  messages from the rotation onward are safe from the leak. This
  bounds damage, doesn't prevent the leak itself.

### An authorized user posting offensive content within their permission boundary

Alice has write access to #general. Her modified client (or her
straightforwardly-typing) posts offensive content. The message is
signed by Alice — structurally legitimate. Defense is at the
moderation layer (`RoomAction.REMOVE_MESSAGE`,
`RoomAction.KICK_USER`, `RoomAction.BAN_USER`), not at the wire.
Mods see the offending message and act; their action propagates
and honest peers honor it.

### A modified client ignoring local user-preference filters

User-side blocklists and keyword filters are display filters by
design (the "relay path never sees blocklist" rule in
`CLAUDE.md`). A modified client can show messages from blocked
senders or below the user's filter threshold — but **only on that
user's own screen**. The user running the modified client is the
only one affected. They're not poisoning anyone else's view.

### Collusion among malicious peers

Five users running modified clients can run their own private
rules among themselves. Inside the cabal they can do whatever.
They cannot infect honest peers — cryptographic enforcement holds
at every honest peer. The cabal exists only on their own machines
and any other malicious peers they're directly connected to.

## Revocation

The asymmetric authorization model (certificates issued by mods)
needs explicit revocation. Two layers:

1. **Short-lived certificates.** A posting certificate's `expiresAt`
   is short (default: 24h). Active members get auto-renewed via
   mod signing in the background; non-renewed = effectively
   revoked at expiry. Avoids the "issued one cert ever, can never
   take it back" trap.

2. **Explicit revocation event.** Mods can also issue a signed
   `RoomAction.REVOKE_CERT` (TBD — file as part of the channel-
   perms backlog row) for immediate-effect kicks. Peers honor by
   refusing to verify any further messages signed under the
   revoked certificate.

For read access, revocation = re-key. The departing member
keeps the old key; new messages encrypted under the new key are
unrecoverable to them.

## Honest framing for user-facing copy

Documentation, settings UI, and onboarding flows MUST NOT promise
more than the protocol delivers:

- ✓ "Outsiders cannot read or post in this channel."
- ✓ "Mods can remove messages and remove members."
- ✗ "Members cannot leak channel contents." (They can. The
  encryption protects against outsiders, not against people you've
  given access to.)
- ✗ "Members cannot screenshot." (They can. Anything in front of
  a human eyeball is exfiltrable.)

Signal-style honest disclosure is the bar.

## Open questions

1. **Posting certificate cadence.** 24h auto-renew is a starting
   point; the cost is the mod's online time + bandwidth (one
   signature per active member per day). Worth measuring once
   real-world Room sizes appear.
2. **Ratchet schedule.** Per-Room? Per-channel? Time-based or
   event-based (rotate on KICK)? Affects bandwidth and latency
   for the rotation key DMs.
3. **Mod-of-mods.** Initial mod set is the Room creator. They can
   designate other mods (signed grant). Can those mods then
   designate further mods? Transitive trust is convenient but
   makes compromise of any leaf mod a cascade. Current lean:
   yes-transitive, with explicit revocation, and a UI surface
   showing the full mod chain for accountability.
