package com.rumor.mesh.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rumor.mesh.core.model.BlocklistMode

/**
 * Local block placed by the user. Source of truth for inbox filtering.
 * Never consulted by the relay path.
 *
 * [expiresAtMs] is null for a permanent block. Expired entries are pruned by
 * [BlockEntryDao.pruneExpired] on a periodic timer.
 */
@Entity(
    tableName = "block_entries",
    indices = [Index(value = ["userId"], unique = true)]
)
data class BlockEntryEntity(
    @PrimaryKey val userId: String,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
    val reason: String?,
)

/**
 * A blocklist published by some other node that the local user has subscribed to.
 * One row per subscribed publisher.
 */
@Entity(
    tableName = "subscribed_blocklists",
    indices = [Index(value = ["publisherId"], unique = true)]
)
data class SubscribedBlocklistEntity(
    @PrimaryKey val publisherId: String,
    val publisherPublicKey: String,
    val mode: BlocklistMode,
    val currentVersion: Long,
    val subscribedAtMs: Long,
    val lastAppliedAtMs: Long,
)

/**
 * Flat (publisher, blockedUser) rows. The effective subscribed-block set is the
 * union of these rows across all subscriptions, computed at query time.
 */
@Entity(
    tableName = "blocklist_entries",
    primaryKeys = ["publisherId", "blockedUserId"],
    indices = [Index("blockedUserId")]
)
data class BlocklistEntryEntity(
    val publisherId: String,
    val blockedUserId: String,
)
