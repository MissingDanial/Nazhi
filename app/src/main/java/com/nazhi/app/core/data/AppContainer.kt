package com.nazhi.app.core.data

import android.content.Context
import com.nazhi.app.core.database.NazhiDatabase
import com.nazhi.app.core.network.NazhiBackendClient
import com.nazhi.app.core.repository.LocalNazhiRepository
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.settings.BackendSettingsStore

class AppContainer(context: Context) {
    private val database = NazhiDatabase.create(context)
    val backendSettingsStore = BackendSettingsStore(context)
    val backendClient = NazhiBackendClient(
        configProvider = { backendSettingsStore.current() }
    )

    val repository: NazhiRepository = LocalNazhiRepository(
        noteDao = database.noteDao(),
        knowledgeEntryDao = database.knowledgeEntryDao(),
        knowledgeEntryDraftDao = database.knowledgeEntryDraftDao(),
        reviewSessionDao = database.reviewSessionDao(),
        embeddingDao = database.embeddingDao(),
        chatSessionDao = database.chatSessionDao(),
        chatMessageDao = database.chatMessageDao(),
        chatCitationDao = database.chatCitationDao(),
        backendClient = backendClient
    )
}
