package com.nazhi.app.core.data

import android.content.Context
import com.nazhi.app.BuildConfig
import com.nazhi.app.core.database.NazhiDatabase
import com.nazhi.app.core.network.NazhiBackendClient
import com.nazhi.app.core.repository.LocalNazhiRepository
import com.nazhi.app.core.repository.NazhiRepository

class AppContainer(context: Context) {
    private val database = NazhiDatabase.create(context)
    private val backendClient = NazhiBackendClient(
        baseUrl = BuildConfig.NAZHI_BACKEND_BASE_URL,
        devToken = BuildConfig.NAZHI_DEV_TOKEN
    )

    val repository: NazhiRepository = LocalNazhiRepository(
        noteDao = database.noteDao(),
        knowledgeEntryDao = database.knowledgeEntryDao(),
        knowledgeEntryDraftDao = database.knowledgeEntryDraftDao(),
        reviewSessionDao = database.reviewSessionDao(),
        embeddingDao = database.embeddingDao(),
        backendClient = backendClient
    )
}
