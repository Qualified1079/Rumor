package com.rumor.mesh.core.model

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
)
