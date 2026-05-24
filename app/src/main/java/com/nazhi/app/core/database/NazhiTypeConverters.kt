package com.nazhi.app.core.database

import androidx.room.TypeConverter
import com.nazhi.app.core.model.AudioTranscriptionJobStatus
import com.nazhi.app.core.model.ChatMessageStatus
import com.nazhi.app.core.model.ChatRole
import com.nazhi.app.core.model.IntentType
import com.nazhi.app.core.model.KnowledgeDraftStatus
import com.nazhi.app.core.model.KnowledgeIndexStatus
import com.nazhi.app.core.model.NoteStatus
import com.nazhi.app.core.model.SourceType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NazhiTypeConverters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun chatRoleToString(value: ChatRole): String = value.name

    @TypeConverter
    fun stringToChatRole(value: String): ChatRole {
        return runCatching { ChatRole.valueOf(value) }
            .getOrDefault(ChatRole.USER)
    }

    @TypeConverter
    fun chatMessageStatusToString(value: ChatMessageStatus): String = value.name

    @TypeConverter
    fun stringToChatMessageStatus(value: String): ChatMessageStatus {
        return runCatching { ChatMessageStatus.valueOf(value) }
            .getOrDefault(ChatMessageStatus.DONE)
    }

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

    @TypeConverter
    fun knowledgeIndexStatusToString(value: KnowledgeIndexStatus): String = value.name

    @TypeConverter
    fun stringToKnowledgeIndexStatus(value: String): KnowledgeIndexStatus {
        return runCatching { KnowledgeIndexStatus.valueOf(value) }
            .getOrDefault(KnowledgeIndexStatus.PENDING)
    }

    @TypeConverter
    fun knowledgeDraftStatusToString(value: KnowledgeDraftStatus): String = value.name

    @TypeConverter
    fun stringToKnowledgeDraftStatus(value: String): KnowledgeDraftStatus {
        return runCatching { KnowledgeDraftStatus.valueOf(value) }
            .getOrDefault(KnowledgeDraftStatus.PENDING)
    }

    @TypeConverter
    fun audioTranscriptionJobStatusToString(value: AudioTranscriptionJobStatus): String = value.name

    @TypeConverter
    fun stringToAudioTranscriptionJobStatus(value: String): AudioTranscriptionJobStatus {
        return runCatching { AudioTranscriptionJobStatus.valueOf(value) }
            .getOrDefault(AudioTranscriptionJobStatus.PENDING)
    }

    @TypeConverter
    fun stringListToJson(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun jsonToStringList(value: String): List<String> {
        if (value.isBlank()) {
            return emptyList()
        }
        return runCatching { json.decodeFromString<List<String>>(value) }
            .getOrDefault(emptyList())
    }
}
