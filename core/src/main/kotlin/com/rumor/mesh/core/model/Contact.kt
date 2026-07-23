package com.rumor.mesh.core.model

/**
 * Locally-persisted record of a peer the user has interacted with
 * directly or that the engine has learned about via gossip.
 *
 * One row per userId; routing and trust decisions consult this
 * (auto-relay membership, priority-peer status, verified state).
 * Display names are user-overridable locally (per O21) — the
 * `displayName` here is the peer's self-asserted name; the
 * local-only override (if any) lives in a UI-side preference, not
 * in this record.
 *
 * @property userId Stable cryptographic identity — `SHA-256(publicKey).hex`.
 * @property publicKey Base64 Ed25519 public key the userId hashes from.
 *   Any inbound message claiming this userId must verify against
 *   this key.
 * @property displayName Peer's self-asserted display name. Optional.
 *   See O21 for the local-pinning + emoji-fingerprint approach to
 *   handling display-name churn or collisions.
 * @property isVerified Out-of-band verification gesture — the user
 *   has confirmed (via QR code, voice readback, etc.) that the
 *   pubkey actually corresponds to a real person they trust.
 *   Default false; promoted manually.
 * @property autoRelay The user has marked this contact's messages
 *   as boosted at relay time (extra TTL — they want this peer's
 *   reach extended).
 * @property alwaysSave This contact's messages are exempt from
 *   size-cap eviction in MessageStore (won't be dropped to make
 *   room for newer traffic).
 * @property willingToCache The user has flagged this contact as a
 *   target for "carrier-pigeon" caching — messages addressed to
 *   them get held on this device when offline so they can be handed
 *   over on next contact.
 * @property firstSeenMs Wall-clock epoch ms the engine first
 *   processed a verified message from this userId.
 * @property lastSeenMs Wall-clock epoch ms of the most recent
 *   verified inbound — used by TopologyTracker for liveness
 *   weighting.
 */
data class Contact(
    val userId: String,
    val publicKey: String,
    val displayName: String?,
    val isVerified: Boolean,
    val autoRelay: Boolean,
    val alwaysSave: Boolean,
    val willingToCache: Boolean,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /**
     * Mutual opt-in persistent-connection flag. When true on both sides the
     * transport skips the normal session teardown so the pair stays connected
     * between gossip rounds. See [MessageType.PRIORITY_LINK_REQUEST].
     */
    val isPriorityPeer: Boolean = false,
    /**
     * O76 / capability-negotiation cache. Stores the peer's HELLO
     * `supportedFeatures` from the most recent successful handshake,
     * as a JSON-encoded `List<String>`. Empty string ("[]" or "") when
     * unknown or never handshaken.
     *
     * Read by compose paths that gate on per-feature support (e.g.
     * O76 compression-v1, future compression-v2, future capability
     * extensions). v1 features that are universally supported by every
     * Rumor build don't strictly need this lookup for routing decisions,
     * but populating it now means future v2 negotiation has the cache
     * in place from the first contact, not after a fresh handshake.
     *
     * Refreshed by `GossipSession` on every completed handshake (the
     * peer's latest advertised features overwrite any prior value).
     */
    val lastKnownSupportedFeatures: String = "",
    /**
     * O136 — the explicit "friend" bit. Set by a deliberate user gesture
     * (friend/accept action), NEVER by the auto-`ensureContact` that fires for
     * every exchanged/relayed sender. This is the trust signal the O135(1)
     * "known peers only" inbox filter keys on — a plain contact is worthless
     * for that (sybils auto-become contacts, O134); a *friended* contact is not.
     */
    val friended: Boolean = false,
)
