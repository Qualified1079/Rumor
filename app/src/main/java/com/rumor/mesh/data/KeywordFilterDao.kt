package com.rumor.mesh.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface KeywordFilterListDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: KeywordFilterListEntity)

    @Query("DELETE FROM keyword_filter_lists WHERE publisherId = :publisherId")
    suspend fun delete(publisherId: String)

    @Query("SELECT * FROM keyword_filter_lists WHERE publisherId = :publisherId")
    suspend fun get(publisherId: String): KeywordFilterListEntity?

    @Query("SELECT * FROM keyword_filter_lists")
    suspend fun getAll(): List<KeywordFilterListEntity>
}

@Dao
interface FilterSubscriptionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FilterSubscriptionEntity)

    @Query("DELETE FROM filter_subscriptions WHERE publisherId = :publisherId")
    suspend fun delete(publisherId: String)

    @Query("SELECT * FROM filter_subscriptions WHERE publisherId = :publisherId")
    suspend fun get(publisherId: String): FilterSubscriptionEntity?

    @Query("SELECT * FROM filter_subscriptions")
    suspend fun getAll(): List<FilterSubscriptionEntity>
}
