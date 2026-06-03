package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.Contact
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    suspend fun upsert(contact: Contact)
    fun observeAll(): Flow<List<Contact>>
    suspend fun getById(userId: String): Contact?
    fun observeById(userId: String): Flow<Contact?>
    suspend fun getPublicKey(userId: String): String?
    suspend fun setAutoRelay(userId: String, enabled: Boolean)
    suspend fun setAlwaysSave(userId: String, enabled: Boolean)
    suspend fun setWillingToCache(userId: String, enabled: Boolean)
    suspend fun updateLastSeen(userId: String, ms: Long)
    suspend fun setVerified(userId: String, verified: Boolean)
    suspend fun setDisplayName(userId: String, name: String)
    suspend fun delete(userId: String)
    suspend fun getAutoRelayContacts(): List<Contact>
    suspend fun setPriorityPeer(userId: String, enabled: Boolean)
    suspend fun getPriorityPeers(): List<Contact>
    /**
     * Atomically rebind a contact's identity to a new userId / publicKey pair
     * after an [com.rumor.mesh.core.model.MessageType.IDENTITY_ROTATION] has
     * verified. Preserves all per-contact state (display name, autorelay,
     * priority-peer, etc.). Returns true if the contact existed and was
     * rebound; false if no contact with [oldUserId] was on file. See O41.
     */
    suspend fun rebindIdentity(oldUserId: String, newUserId: String, newPublicKey: String): Boolean
}
