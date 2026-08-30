package com.chirp.wear

import android.content.Context
import com.chirp.core.session.SessionController
import com.chirp.core.session.SessionState
import com.chirp.core.util.DispatcherProvider
import com.chirp.core.wear.WearContract
import com.chirp.data.settings.SettingsRepository
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side publisher (PHASE 2). Observes the singleton [SessionController]'s
 * state and the [SettingsRepository] and mirrors them to the watch as a `/chirp/state`
 * DataItem so the Wear companion renders the session and can honor the
 * "start listening on new conversation" setting. Started/stopped by
 * [com.chirp.service.ConversationService]; idle publication is harmless because
 * writes are debounced by DataClient to changes only.
 */
@Singleton
class WearDataSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: SessionController,
    settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
) {
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private var job: Job? = null

    private val settings = settingsRepository.settings

    fun start() {
        if (job != null) return
        job = scope.launch {
            combine(controller.state, settings) { state, s ->
                state to s.startListeningOnNewConversation
            }.collect { (state, startListening) ->
                publish(state, startListening)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun publish(state: SessionState, startListeningOnNewConversation: Boolean) {
        val bytes = WearContract.encodeState(state, startListeningOnNewConversation)
        val request = PutDataRequest.create(WearContract.PATH_STATE)
            .setData(bytes)
        dataClient.putDataItem(request).addOnFailureListener {
            // No Throwable: rely on Data Layer retrying/re-syncing; treat as silence.
        }
    }
}