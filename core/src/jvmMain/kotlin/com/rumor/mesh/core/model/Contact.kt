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
)
