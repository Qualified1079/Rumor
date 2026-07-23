package com.rumor.mesh.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.rumor.mesh.BuildConfig

/**
 * The single SQLite database backing the Android app. Owns every persisted
 * repository the engine reaches (messages, contacts, routes, blocklists,
 * transfers/chunks, scheduled messages, room subscriptions, keyword filters).
 *
 * `:simulator` parallels this surface with `InMemoryRepos.kt`; both target the
 * same `core/data/…Repository` contracts. When a repository contract gains a
 * method, both impls move together — see CLAUDE.md §DI wiring.
 *
 * **Versioning policy.** Every entity-shape change bumps [version] and gets a
 * one-line history note above. Debug builds use `fallbackToDestructiveMigration`
 * so contributors aren't blocked by a hand-written migration during iteration;
 * **release builds run without it** — a release build hitting a missing
 * migration crashes rather than silently wiping the user's messages.
 *
 * **TypeConverter compatibility.** Enums round-trip through their `.name`
 * (see [Converters]). Renaming a `MessageType` / `ContentType` / `BlocklistMode`
 * / `TransferStatus` / `TransferDirection` value breaks `valueOf` on existing
 * rows — the names are part of the on-disk schema, not just the source.
 */
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
        KeywordFilterListEntity::class,
        FilterSubscriptionEntity::class,
        ScheduledMessageEntity::class,
        RoomSubscriptionEntity::class,
    ],
    // v5: added RouteEntity.failureCount for O3 reliability-aware ranking.
    // v6: added KeywordFilterListEntity + FilterSubscriptionEntity for O67
    //     keyword-filter persistence (JSON-blob storage shape — see
    //     KeywordFilterEntities.kt for rationale).
    // v7: added ScheduledMessageEntity for O22 / G15 schedule persistence.
    // v8: added ContactEntity.lastKnownSupportedFeatures (JSON string)
    //     for O76 capability-negotiation cache.
    // v9: added RoomSubscriptionEntity for O79 room-subscription
    //     persistence (flat field shape — routing material only;
    //     decryption material stays in IdentityManager).
    // v10: added MessageEntity.ext (JSON blob of the O37 `_ext` map) —
    //      dropping it stripped compression-AAD flags from stored DMs,
    //      which then failed AES-GCM tag checks at display time.
    // v11: added ContactEntity.friended (O136 — explicit friend bit the
    //      O135(1) "known peers only" inbox filter keys on).
    // Dev uses fallbackToDestructiveMigration so no migration code needed.
    version = 11,
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
    abstract fun keywordFilterListDao(): KeywordFilterListDao
    abstract fun filterSubscriptionDao(): FilterSubscriptionDao
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun roomSubscriptionDao(): RoomSubscriptionDao

    companion object {
        private const val DB_NAME = "rumor.db"

        fun create(context: Context): RumorDatabase =
            Room.databaseBuilder(context, RumorDatabase::class.java, DB_NAME)
                .apply {
                    // Release builds must have explicit migrations — silently
                    // wiping user data on a schema bump is unacceptable in
                    // production. Dev builds keep the convenience.
                    if (BuildConfig.DEBUG) fallbackToDestructiveMigration()
                }
                .build()
    }
}
