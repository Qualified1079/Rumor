package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.data.RoomSubscriptionMode
import com.rumor.mesh.core.data.RoomSubscriptionRepository
import com.rumor.mesh.core.identity.IdentityProvider
import kotlinx.coroutines.runBlocking

/**
 * O79 [GossipEngine.RoomSubscriptionProvider] over a [RoomSubscriptionRepository].
 * One implementation for every host (O252) — it used to be an inline copy in
 * AppModule and another in SimNode, and `:node` had none.
 *
 * Re-reads the repo on every inbound ROOM_MESSAGE via `runBlocking`; O185 replaces
 * that with a cached snapshot, here, once for all hosts.
 */
class RepoRoomSubscriptionProvider(
    private val repo: RoomSubscriptionRepository,
    private val identityProvider: IdentityProvider,
) : GossipEngine.RoomSubscriptionProvider {

    override fun openRoomIds(): List<String> = runBlocking {
        repo.getAll().filter { it.mode == RoomSubscriptionMode.OPEN }.map { it.roomId }
    }

    override fun encryptedRoomSubscriptions(): List<RoomTagMatcher.EncryptedRoomSubscription> = runBlocking {
        repo.getAll()
            .filter { it.mode == RoomSubscriptionMode.ENCRYPTED }
            .map { RoomTagMatcher.EncryptedRoomSubscription(it.roomId, it.routingKey) }
    }

    /** Derived fresh from the unlocked identity seed; the engine zeroes it after use. Null while locked. */
    override fun localX25519StaticPrivate(): ByteArray? {
        val seed = identityProvider.identity.value?.privateKeyBytes ?: return null
        return CryptoManager.ed25519ToX25519PrivateSeed(seed)
    }
}
