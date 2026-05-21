package com.nazhi.app.core.data

import android.content.Context
import com.nazhi.app.core.chat.KnowledgeChatCoordinator
import com.nazhi.app.core.database.NazhiDatabase
import com.nazhi.app.core.knowledge.KnowledgeIngestionCoordinator
import com.nazhi.app.core.network.NazhiBackendClient
import com.nazhi.app.core.repository.LocalNazhiRepository
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.settings.BackendSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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

    val knowledgeIngestionCoordinator = KnowledgeIngestionCoordinator(
        repository = repository,
        scope = applicationScope
    )

    val knowledgeChatCoordinator = KnowledgeChatCoordinator(
        repository = repository,
        scope = applicationScope
    )
}
