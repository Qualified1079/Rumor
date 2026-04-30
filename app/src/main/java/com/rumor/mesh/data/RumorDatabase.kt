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
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RumorDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun breadcrumbDao(): BreadcrumbDao
    abstract fun routeDao(): RouteDao

    companion object {
        private const val DB_NAME = "rumor.db"

        fun create(context: Context): RumorDatabase =
            Room.databaseBuilder(context, RumorDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
