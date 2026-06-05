package com.rumor.mesh.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("SELECT * FROM contacts ORDER BY displayName ASC, userId ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE userId = :userId LIMIT 1")
    suspend fun getById(userId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE userId = :userId LIMIT 1")
    fun observeById(userId: String): Flow<ContactEntity?>

    @Query("SELECT publicKey FROM contacts WHERE userId = :userId LIMIT 1")
    suspend fun getPublicKey(userId: String): String?

    @Query("UPDATE contacts SET autoRelay = :enabled WHERE userId = :userId")
    suspend fun setAutoRelay(userId: String, enabled: Boolean)

    @Query("UPDATE contacts SET alwaysSave = :enabled WHERE userId = :userId")
    suspend fun setAlwaysSave(userId: String, enabled: Boolean)

    @Query("UPDATE contacts SET willingToCache = :enabled WHERE userId = :userId")
    suspend fun setWillingToCache(userId: String, enabled: Boolean)

    @Query("UPDATE contacts SET lastSeenMs = :ms WHERE userId = :userId")
    suspend fun updateLastSeen(userId: String, ms: Long)

    @Query("UPDATE contacts SET isVerified = :verified WHERE userId = :userId")
    suspend fun setVerified(userId: String, verified: Boolean)

    @Query("UPDATE contacts SET displayName = :name WHERE userId = :userId")
    suspend fun setDisplayName(userId: String, name: String)

    @Query("DELETE FROM contacts WHERE userId = :userId")
    suspend fun delete(userId: String)

    @Query("SELECT * FROM contacts WHERE autoRelay = 1")
    suspend fun getAutoRelayContacts(): List<ContactEntity>

    @Query("UPDATE contacts SET isPriorityPeer = :enabled WHERE userId = :userId")
    suspend fun setPriorityPeer(userId: String, enabled: Boolean)

    @Query("SELECT * FROM contacts WHERE isPriorityPeer = 1")
    suspend fun getPriorityPeers(): List<ContactEntity>

    @Query("UPDATE contacts SET lastKnownSupportedFeatures = :json WHERE userId = :userId")
    suspend fun setSupportedFeatures(userId: String, json: String)
}
