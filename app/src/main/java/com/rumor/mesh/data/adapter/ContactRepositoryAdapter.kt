package com.rumor.mesh.data.adapter

import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.model.Contact
import com.rumor.mesh.data.ContactDao
import com.rumor.mesh.data.ContactEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ContactRepositoryAdapter(private val dao: ContactDao) : ContactRepository {

    override suspend fun upsert(contact: Contact) = dao.upsert(contact.toEntity())
    override fun observeAll(): Flow<List<Contact>> = dao.observeAll().map { it.map(ContactEntity::toModel) }
    override suspend fun getById(userId: String): Contact? = dao.getById(userId)?.toModel()
    override fun observeById(userId: String): Flow<Contact?> = dao.observeById(userId).map { it?.toModel() }
    override suspend fun getPublicKey(userId: String): String? = dao.getPublicKey(userId)
    override suspend fun setAutoRelay(userId: String, enabled: Boolean) = dao.setAutoRelay(userId, enabled)
    override suspend fun setAlwaysSave(userId: String, enabled: Boolean) = dao.setAlwaysSave(userId, enabled)
    override suspend fun setWillingToCache(userId: String, enabled: Boolean) = dao.setWillingToCache(userId, enabled)
    override suspend fun updateLastSeen(userId: String, ms: Long) = dao.updateLastSeen(userId, ms)
    override suspend fun setVerified(userId: String, verified: Boolean) = dao.setVerified(userId, verified)
    override suspend fun setDisplayName(userId: String, name: String) = dao.setDisplayName(userId, name)
    override suspend fun delete(userId: String) = dao.delete(userId)
    override suspend fun getAutoRelayContacts(): List<Contact> = dao.getAutoRelayContacts().map(ContactEntity::toModel)
    override suspend fun setPriorityPeer(userId: String, enabled: Boolean) = dao.setPriorityPeer(userId, enabled)
    override suspend fun getPriorityPeers(): List<Contact> = dao.getPriorityPeers().map(ContactEntity::toModel)
    override suspend fun setFriended(userId: String, enabled: Boolean) = dao.setFriended(userId, enabled)
    override suspend fun isFriended(userId: String): Boolean = dao.isFriended(userId) == true
    override suspend fun setSupportedFeatures(userId: String, jsonEncodedFeatures: String) =
        dao.setSupportedFeatures(userId, jsonEncodedFeatures)
}

private fun Contact.toEntity() = ContactEntity(
    userId, publicKey, displayName, isVerified, autoRelay, alwaysSave, willingToCache, firstSeenMs, lastSeenMs,
    isPriorityPeer, lastKnownSupportedFeatures, friended,
)

private fun ContactEntity.toModel() = Contact(
    userId, publicKey, displayName, isVerified, autoRelay, alwaysSave, willingToCache, firstSeenMs, lastSeenMs,
    isPriorityPeer, lastKnownSupportedFeatures, friended,
)
