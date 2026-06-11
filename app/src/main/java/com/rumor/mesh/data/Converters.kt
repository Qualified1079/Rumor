package com.rumor.mesh.data

import androidx.room.TypeConverter
import com.rumor.mesh.core.model.BlocklistMode
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.TransferDirection
import com.rumor.mesh.core.model.TransferStatus

/**
 * Room TypeConverters for the core enums that appear in entity fields.
 *
 * Each enum round-trips through `.name` / `valueOf(...)` — readable in raw
 * SQLite dumps and stable across reordering, but **value-name-sensitive**:
 * renaming `MessageType.BROADCAST` to anything else breaks `valueOf` on every
 * previously-stored row. Treat enum value names here as on-disk schema, same
 * as the wire-format strings tracked in `docs/RENAMED_FIELDS_NEVER_REUSE.md`.
 */
class Converters {
    @TypeConverter fun fromMessageType(v: MessageType): String = v.name
    @TypeConverter fun toMessageType(v: String): MessageType = MessageType.valueOf(v)

    @TypeConverter fun fromContentType(v: ContentType?): String? = v?.name
    @TypeConverter fun toContentType(v: String?): ContentType? = v?.let { ContentType.valueOf(it) }

    @TypeConverter fun fromBlocklistMode(v: BlocklistMode): String = v.name
    @TypeConverter fun toBlocklistMode(v: String): BlocklistMode = BlocklistMode.valueOf(v)

    @TypeConverter fun fromTransferStatus(v: TransferStatus): String = v.name
    @TypeConverter fun toTransferStatus(v: String): TransferStatus = TransferStatus.valueOf(v)

    @TypeConverter fun fromTransferDirection(v: TransferDirection): String = v.name
    @TypeConverter fun toTransferDirection(v: String): TransferDirection = TransferDirection.valueOf(v)
}
