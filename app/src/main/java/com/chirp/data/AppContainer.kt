package com.chirp.data

import android.content.Context
import com.chirp.realtime.OpenAiRealtimeClient
import com.chirp.realtime.TranscriptStore
import com.chirp.realtime.VoiceSessionController
import com.chirp.wear.WearSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val apiKeyStore = ApiKeyStore(appContext)
    val settingsStore = SettingsStore(appContext)
    val transcriptDatabase = TranscriptDatabase.getInstance(appContext)
    val transcriptRepository = TranscriptRepository(transcriptDatabase.transcriptDao())
    val transcriptStore = TranscriptStore(transcriptRepository)

    private val httpClient = OkHttpClient.Builder().build()

    val realtimeClient = OpenAiRealtimeClient(
        context = appContext,
        httpClient = httpClient,
        apiKeyStore = apiKeyStore,
        transcriptStore = transcriptStore,
    )

    val sessionController = VoiceSessionController(
        scope = appScope,
        realtimeClient = realtimeClient,
        settingsStore = settingsStore,
        transcriptStore = transcriptStore,
    )

    val wearSync = WearSync(
        context = appContext,
        scope = appScope,
        sessionController = sessionController,
        transcriptRepository = transcriptRepository,
    )
}
