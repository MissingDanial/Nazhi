package com.nazhi.app.core.database

import androidx.room.TypeConverter
import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.NoteStatus
import com.nazhi.app.core.model.SourceType

class NazhiTypeConverters {
    @TypeConverter
    fun sourceTypeToString(value: SourceType): String = value.name

    @TypeConverter
    fun stringToSourceType(value: String): SourceType = SourceType.valueOf(value)

    @TypeConverter
    fun noteStatusToString(value: NoteStatus): String = value.name

    @TypeConverter
    fun stringToNoteStatus(value: String): NoteStatus = NoteStatus.valueOf(value)

    @TypeConverter
    fun intentTypeToString(value: IntentType): String = value.name

    @TypeConverter
    fun stringToIntentType(value: String): IntentType = IntentType.valueOf(value)
}
