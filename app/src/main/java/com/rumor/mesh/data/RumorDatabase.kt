package com.rumor.mesh.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        BreadcrumbEntity::class,
        RouteEntity::class,
        BlockEntryEntity::class,
        SubscribedBlocklistEntity::class,
        BlocklistEntryEntity::class,
        TransferEntity::class,
        ChunkEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RumorDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun breadcrumbDao(): BreadcrumbDao
    abstract fun routeDao(): RouteDao
    abstract fun blockEntryDao(): BlockEntryDao
    abstract fun subscribedBlocklistDao(): SubscribedBlocklistDao
    abstract fun blocklistEntryDao(): BlocklistEntryDao
    abstract fun transferDao(): TransferDao
    abstract fun chunkDao(): ChunkDao

    companion object {
        private const val DB_NAME = "rumor.db"

        fun create(context: Context): RumorDatabase =
            Room.databaseBuilder(context, RumorDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
