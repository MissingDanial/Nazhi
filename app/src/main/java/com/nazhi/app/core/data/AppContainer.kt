package com.nazhi.app.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.nazhi.app.core.audio.AudioTaskProcessor
import com.nazhi.app.core.auth.AuthSessionStore
import com.nazhi.app.core.chat.KnowledgeChatCoordinator
import com.nazhi.app.core.database.NazhiDatabase
import com.nazhi.app.core.knowledge.KnowledgeIngestionCoordinator
import com.nazhi.app.core.network.NazhiBackendClient
import com.nazhi.app.core.repository.LocalNazhiRepository
import com.nazhi.app.core.repository.NazhiRepository
import com.nazhi.app.core.settings.BackendSettingsStore
import com.nazhi.app.core.settings.BackupHistoryStore
import com.nazhi.app.core.tasks.PendingTaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.nazhi.app.core.network.TaskBindingKey

class AppContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database = NazhiDatabase.create(context)
    val backendSettingsStore = BackendSettingsStore(context)
    val authSessionStore = AuthSessionStore(context)
    val backupHistoryStore = BackupHistoryStore(context)
    private val authRefreshClient = NazhiBackendClient(
        configProvider = { backendSettingsStore.current() }
    )
    val backendClient = NazhiBackendClient(
        configProvider = { backendSettingsStore.current() },
        accessTokenProvider = {
            authSessionStore.currentValidAccessToken(
                refresh = { refreshToken ->
                    authRefreshClient.refreshAuthSession(refreshToken)
                }
            )
        },
        accessTokenRefresher = { failedToken ->
            authSessionStore.refreshAccessToken(
                failedAccessToken = failedToken,
                refresh = { refreshToken ->
                    authRefreshClient.refreshAuthSession(refreshToken)
                }
            )
        },
        accountIdProvider = { authSessionStore.session.first()?.userId },
        authSessionExpiredHandler = {
            authSessionStore.clearExpiredSession()
        }
    )

    val repository: NazhiRepository = LocalNazhiRepository(
        database = database,
        persistedAiTaskDao = database.persistedAiTaskDao(),
        noteDao = database.noteDao(),
        knowledgeEntryDao = database.knowledgeEntryDao(),
        knowledgeEntryDraftDao = database.knowledgeEntryDraftDao(),
        reviewSessionDao = database.reviewSessionDao(),
        embeddingDao = database.embeddingDao(),
        chatSessionDao = database.chatSessionDao(),
        chatMessageDao = database.chatMessageDao(),
        chatCitationDao = database.chatCitationDao(),
        audioTranscriptionJobDao = database.audioTranscriptionJobDao(),
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

    val audioTaskProcessor = AudioTaskProcessor(repository, backendClient)
    val taskRecoveryCoordinator = TaskRecoveryCoordinator(repository, audioTaskProcessor, applicationScope)
    val pendingTaskStore = PendingTaskStore(database, repository, backendClient, applicationScope)

    init {
        applicationScope.launch {
            combine(backendSettingsStore.settings, authSessionStore.session) { config, session ->
                TaskBindingKey.create(config, session?.userId) to
                    TaskBindingKey.create(config, session?.userId, forAudio = true)
            }.distinctUntilChanged().collect {
                taskRecoveryCoordinator.requestRecovery()
            }
        }
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        // Registration lives with the application container, not an Activity instance.
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var validated = false

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (available && !validated) taskRecoveryCoordinator.requestRecovery()
                validated = available
            }

            override fun onLost(network: Network) {
                validated = false
            }
        })
    }
}
