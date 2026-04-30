package com.rumor.mesh.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessageType

@Entity(
    tableName = "messages",
    indices = [
        Index("senderId"),
        Index("recipientId"),
        Index("receivedAtMs"),
        Index("type"),
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val senderPublicKey: String,
    val sequenceNumber: Long,
    val elapsedMs: Long,
    val type: MessageType,
    val ttl: Int,
    val contentType: ContentType?,
    val content: String?,
    val filename: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val encryptedPayload: String?,
    val recipientId: String?,
    val signature: String,
    val receivedAtMs: Long,
    val isRead: Boolean,
    val wasRelayed: Boolean,
)

@Entity(
    tableName = "contacts",
    indices = [Index("userId", unique = true)]
)
data class ContactEntity(
    @PrimaryKey val userId: String,
    val publicKey: String,
    val displayName: String?,
    val isVerified: Boolean,
    val autoRelay: Boolean,
    val alwaysSave: Boolean,
    val willingToCache: Boolean,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

@Entity(
    tableName = "breadcrumbs",
    indices = [Index("targetUserId")]
)
data class BreadcrumbEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val targetUserId: String,
    val arrivedFromPeerId: String,
    val hopCount: Int,
    val recordedAtMs: Long,
)

@Entity(
    tableName = "routes",
    indices = [Index(value = ["peerId"], unique = true)]
)
data class RouteEntity(
    @PrimaryKey val peerId: String,
    val latencyMs: Long,
    val hopCount: Int,
    val lastUpdatedMs: Long,
    val sessionCount: Int,
)
