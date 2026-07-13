package com.rumor.mesh.data

import androidx.room.*

@Dao
interface BreadcrumbDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(crumb: BreadcrumbEntity)

    @Query("""
        SELECT * FROM breadcrumbs
        WHERE targetUserId = :targetId
        ORDER BY recordedAtMs DESC
        LIMIT 1
    """)
    suspend fun getLatest(targetId: String): BreadcrumbEntity?

    @Query("""
        SELECT * FROM breadcrumbs
        WHERE targetUserId = :targetId
        ORDER BY recordedAtMs DESC
        LIMIT :limit
    """)
    suspend fun getRecent(targetId: String, limit: Int): List<BreadcrumbEntity>

    /** Keep only the 5 freshest crumbs per target; delete the rest. */
    @Query("""
        DELETE FROM breadcrumbs WHERE rowId NOT IN (
            SELECT rowId FROM breadcrumbs
            WHERE targetUserId = :targetId
            ORDER BY recordedAtMs DESC
            LIMIT 5
        ) AND targetUserId = :targetId
    """)
    suspend fun pruneForTarget(targetId: String)

    @Query("DELETE FROM breadcrumbs WHERE recordedAtMs < :olderThanMs")
    suspend fun pruneOld(olderThanMs: Long)
}
