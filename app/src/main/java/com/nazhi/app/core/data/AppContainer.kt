package com.nazhi.app.core.data

import android.content.Context
import com.nazhi.app.core.database.NazhiDatabase
import com.nazhi.app.core.repository.LocalNazhiRepository
import com.nazhi.app.core.repository.NazhiRepository

class AppContainer(context: Context) {
    private val database = NazhiDatabase.create(context)

    val repository: NazhiRepository = LocalNazhiRepository(
        noteDao = database.noteDao(),
        knowledgeEntryDao = database.knowledgeEntryDao(),
        reviewSessionDao = database.reviewSessionDao()
    )
}
