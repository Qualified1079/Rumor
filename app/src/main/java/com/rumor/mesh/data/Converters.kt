package com.rumor.mesh.data

import androidx.room.TypeConverter
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessageType

class Converters {
    @TypeConverter fun fromMessageType(v: MessageType): String = v.name
    @TypeConverter fun toMessageType(v: String): MessageType = MessageType.valueOf(v)

    @TypeConverter fun fromContentType(v: ContentType?): String? = v?.name
    @TypeConverter fun toContentType(v: String?): ContentType? = v?.let { ContentType.valueOf(it) }
}
