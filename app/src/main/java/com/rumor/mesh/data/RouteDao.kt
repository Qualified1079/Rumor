package com.rumor.mesh.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: RouteEntity)

    @Query("SELECT * FROM routes ORDER BY latencyMs ASC LIMIT :limit")
    suspend fun getFastest(limit: Int = 20): List<RouteEntity>

    @Query("SELECT * FROM routes ORDER BY latencyMs ASC")
    fun observeAll(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE peerId = :peerId LIMIT 1")
    suspend fun getForPeer(peerId: String): RouteEntity?

    @Query("DELETE FROM routes WHERE lastUpdatedMs < :olderThanMs")
    suspend fun pruneStale(olderThanMs: Long)

    @Query("DELETE FROM routes WHERE peerId = :peerId")
    suspend fun delete(peerId: String)
}
