package com.chirp.wear.data

import android.content.Context
import com.chirp.core.session.SessionState
import com.chirp.core.wear.WearContract
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Watch-side listener for the phone's `/chirp/state` DataItem. Exposes the
 * decoded [SessionState] as a [StateFlow] plus the phone's synced
 * "start listening on new conversation" preference (from
 * [com.chirp.core.wear.WearContract.StatePayload]).
 */
class WearStateRepository(context: Context) : DataClient.OnDataChangedListener {

    private val dataClient: DataClient = Wearable.getDataClient(context)

    private val _state = MutableStateFlow<SessionState?>(null)
    val state: StateFlow<SessionState?> = _state

    private val _startListeningPref = MutableStateFlow(false)
    val startListeningOnNewConversation: StateFlow<Boolean> = _startListeningPref

    fun start() {
        dataClient.addListener(this)
    }

    fun stop() {
        dataClient.removeListener(this)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != WearContract.PATH_STATE) continue
            apply(event.dataItem)
        }
    }

    private fun apply(item: DataItem) {
        val payload = WearContract.decodeStatePayload(item.data) ?: return
        _state.value = WearContract.toSessionState(payload)
        _startListeningPref.value = payload.startListeningOnNewConversation
    }
}