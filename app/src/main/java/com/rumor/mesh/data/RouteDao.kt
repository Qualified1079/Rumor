package com.rumor.mesh.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: RouteEntity)

    // Primary rank: cumulative bytes transferred (high-throughput peers first).
    // Secondary: session count; tertiary: recency. latencyMs is stored for
    // diagnostics only — noisy on BLE/Wi-Fi Direct (measures discovery timing).
    @Query("SELECT * FROM routes ORDER BY bytesRelayed DESC, sessionCount DESC, lastUpdatedMs DESC LIMIT :limit")
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
