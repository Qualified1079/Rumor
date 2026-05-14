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
)
