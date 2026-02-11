package com.chirp.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class WearListenerService : WearableListenerService() {
    private lateinit var store: WearStateStore

    override fun onCreate() {
        super.onCreate()
        store = WearStateStore(this)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearPaths.STATUS -> {
                val payload = JSONObject(String(messageEvent.data))
                val status = payload.optString("status", "Idle")
                val live = payload.optBoolean("live", false)
                store.updateStatus(status, live)
            }
            WearPaths.TRANSCRIPT -> {
                val payload = JSONObject(String(messageEvent.data))
                val text = payload.optString("text", "")
                store.updateTranscript(text)
            }
        }
    }
}
