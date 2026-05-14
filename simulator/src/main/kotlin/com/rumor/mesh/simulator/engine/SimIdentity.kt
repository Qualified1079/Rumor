package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Generates an in-memory Ed25519 identity for a simulated node. */
class SimIdentityProvider(nodeIndex: Int) : IdentityProvider {
    private val keypair = CryptoManager.generateEd25519KeyPair()
    private val userId  = CryptoManager.publicKeyToUserId(keypair.publicKeyBytes)
    private val deviceId = "sim-node-$nodeIndex"

    private val _identity = MutableStateFlow<LocalIdentity?>(
        LocalIdentity(userId, deviceId, keypair.publicKeyBytes, keypair.privateKeyBytes)
    )
    override val identity: StateFlow<LocalIdentity?> = _identity
    override val isUnlocked: Boolean get() = true
}
