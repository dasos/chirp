package com.chirp.data

import android.content.Context
import com.chirp.ask.AskSessionController
import com.chirp.ask.OpenAiAskClient
import com.chirp.ask.PushToTalkRecorder
import com.chirp.ask.SpeechPlayer
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
    val sessionRepository = SessionRepository(transcriptDatabase.sessionDao())

    private val httpClient = OkHttpClient.Builder().build()
    private val sessionTitleGenerator = OpenAiSessionTitleGenerator(httpClient, apiKeyStore)

    val transcriptStore = TranscriptStore(
        repository = transcriptRepository,
        sessionRepository = sessionRepository,
        titleGenerator = sessionTitleGenerator,
    )

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

    private val askClient = OpenAiAskClient(
        context = appContext,
        httpClient = httpClient,
        apiKeyStore = apiKeyStore,
    )
    private val askRecorder = PushToTalkRecorder(appContext)
    private val speechPlayer = SpeechPlayer(appContext, settingsStore)

    val askController = AskSessionController(
        scope = appScope,
        recorder = askRecorder,
        askClient = askClient,
        player = speechPlayer,
        transcriptStore = transcriptStore,
        transcriptRepository = transcriptRepository,
        settingsStore = settingsStore,
    )

    val wearSync = WearSync(
        context = appContext,
        scope = appScope,
        sessionController = sessionController,
        transcriptRepository = transcriptRepository,
    )
}
