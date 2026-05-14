package com.rumor.mesh.core.model

data class BlockEntry(
    val userId: String,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
    val reason: String?,
)

data class SubscribedBlocklist(
    val publisherId: String,
    val publisherPublicKey: String,
    val mode: BlocklistMode,
    val currentVersion: Long,
    val subscribedAtMs: Long,
    val lastAppliedAtMs: Long,
)

data class BlocklistEntry(
    val publisherId: String,
    val blockedUserId: String,
)
