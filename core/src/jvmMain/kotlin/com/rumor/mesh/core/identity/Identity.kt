package com.rumor.mesh.core.identity

import kotlinx.coroutines.flow.StateFlow

data class LocalIdentity(
    val userId: String,
    val deviceId: String,
    val publicKeyBytes: ByteArray,
    val privateKeyBytes: ByteArray,
)

interface IdentityProvider {
    val identity: StateFlow<LocalIdentity?>
    val isUnlocked: Boolean
}
