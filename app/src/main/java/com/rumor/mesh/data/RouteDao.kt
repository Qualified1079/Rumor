package com.rumor.mesh.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: RouteEntity)

    // Latency is a noisy signal on BLE/Wi-Fi Direct (it mostly measures
    // discovery timing), so peer preference ranks on encounter recency and
    // session count instead. latencyMs is still stored as a diagnostic.
    @Query("SELECT * FROM routes ORDER BY lastUpdatedMs DESC, sessionCount DESC LIMIT :limit")
    suspend fun getPreferred(limit: Int = 20): List<RouteEntity>

    @Query("SELECT * FROM routes ORDER BY lastUpdatedMs DESC")
    fun observeAll(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE peerId = :peerId LIMIT 1")
    suspend fun getForPeer(peerId: String): RouteEntity?

    @Query("DELETE FROM routes WHERE lastUpdatedMs < :olderThanMs")
    suspend fun pruneStale(olderThanMs: Long)

    @Query("DELETE FROM routes WHERE peerId = :peerId")
    suspend fun delete(peerId: String)
}
