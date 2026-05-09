package com.rumor.mesh.data

import androidx.room.TypeConverter
import com.rumor.mesh.core.model.BlocklistMode
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.TransferDirection
import com.rumor.mesh.core.model.TransferStatus

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
