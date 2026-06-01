package com.rumor.mesh.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Query("""
        SELECT * FROM messages
        WHERE type = 'BROADCAST'
        ORDER BY receivedAtMs DESC
        LIMIT :limit
    """)
    fun observeBroadcasts(limit: Int = 200): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages
        WHERE (senderId = :userId AND recipientId = :peerId)
           OR (senderId = :peerId AND recipientId = :userId)
        ORDER BY sequenceNumber ASC
        LIMIT :limit
    """)
    fun observeThread(userId: String, peerId: String, limit: Int = 500): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE recipientId = :userId AND isRead = 0")
    fun observeUnread(userId: String): Flow<List<MessageEntity>>

    /**
     * All DMs the local user is involved in (sent or received), newest first.
     * Used by the messages screen to assemble per-peer thread previews in memory.
     *
     * Orders by MIN(sentAtMs, receivedAtMs) so a malicious sender can't pin
     * their DM to the top of the list by forging a future sentAtMs. For
     * legitimate offline-then-delivered DMs from days ago, MIN keeps the
     * older value (correct). For forged future timestamps, MIN clamps to
     * receivedAtMs (when we actually saw it).
     */
    @Query("""
        SELECT * FROM messages
        WHERE type = 'DIRECT' AND (senderId = :userId OR recipientId = :userId)
        ORDER BY MIN(sentAtMs, receivedAtMs) DESC
        LIMIT :limit
    """)
    fun observeAllDirect(userId: String, limit: Int = 500): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE messages SET wasRelayed = 1 WHERE id = :id")
    suspend fun markRelayed(id: String)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    /** Size-based eviction — drop oldest non-always-save messages when over capacity. */
    @Query("""
        DELETE FROM messages WHERE id IN (
            SELECT m.id FROM messages m
            LEFT JOIN contacts c ON m.senderId = c.userId
            WHERE c.alwaysSave = 0 OR c.alwaysSave IS NULL
            ORDER BY m.receivedAtMs ASC
            LIMIT :count
        )
    """)
    suspend fun evictOldest(count: Int)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)
}
