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
}
