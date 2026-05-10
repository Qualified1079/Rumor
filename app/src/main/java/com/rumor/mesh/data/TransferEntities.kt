package com.rumor.mesh.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.TransferDirection
import com.rumor.mesh.core.model.TransferStatus

/** Projection for a per-transfer chunk count, used by the progress UI. */
data class TransferReceivedCount(
    val transferId: String,
    val received: Int,
)

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val transferId: String,
    val direction: TransferDirection,
    val contentType: ContentType,
    val mimeType: String?,
    val title: String?,
    val totalBytes: Long,
    val totalChunks: Int,
    val chunkSize: Int,
    val contentHash: String,
    /** Null for broadcast transfers. */
    val recipientId: String?,
    /** Sender user ID — populated on received transfers. */
    val senderId: String?,
    val startedAtMs: Long,
    val completedAtMs: Long?,
    val status: TransferStatus,
)

@Entity(
    tableName = "chunks",
    primaryKeys = ["transferId", "chunkIndex"],
    foreignKeys = [ForeignKey(
        entity = TransferEntity::class,
        parentColumns = ["transferId"],
        childColumns = ["transferId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("transferId")],
)
data class ChunkEntity(
    val transferId: String,
    val chunkIndex: Int,
    /** Raw bytes for this chunk. Stored until transfer completes (then can be cleared by vacuum). */
    val data: ByteArray,
    val receivedAtMs: Long?,
    val ackedAtMs: Long?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkEntity) return false
        return transferId == other.transferId &&
            chunkIndex == other.chunkIndex &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + data.contentHashCode()
        return result
    }
}
