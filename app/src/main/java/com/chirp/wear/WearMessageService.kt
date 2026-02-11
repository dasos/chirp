package com.chirp.wear

import android.content.Intent
import com.chirp.service.VoiceSessionService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearMessageService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearPaths.START -> {
                val intent = Intent(this, VoiceSessionService::class.java).apply {
                    action = VoiceSessionService.ACTION_START
                }
                startForegroundService(intent)
            }
            WearPaths.STOP -> {
                val intent = Intent(this, VoiceSessionService::class.java).apply {
                    action = VoiceSessionService.ACTION_STOP
                }
                startService(intent)
            }
        }
    }
}
