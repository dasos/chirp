package com.chirp.wear

import android.util.Log
import androidx.core.content.ContextCompat
import com.chirp.core.session.SessionCommand
import com.chirp.core.wear.WearContract
import com.chirp.service.ConversationService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Phone-side receiver (PHASE 2): listens for `/chirp/command` messages from the
 * Wear companion, decodes them with [WearContract] and funnels each into a
 * [ConversationService] action intent. Keeps the single-control-funnel invariant:
 * the watch never touches the controller directly.
 */
class PhoneWearMessageService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "onMessageReceived: path=${messageEvent.path} src=${messageEvent.sourceNodeId}")
        if (messageEvent.path != WearContract.PATH_COMMAND) return
        val command = WearContract.decodeCommand(messageEvent.data)
        Log.d(TAG, "decoded command: $command")
        val intent = command?.let { ConversationService.intentForWearCommand(this, it) }
        if (intent == null) {
            Log.w(TAG, "no service intent for command $command")
            return
        }
        try {
            if (command is SessionCommand.Start) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "started service with action ${intent.action}")
        } catch (e: Exception) {
            Log.e(TAG, "failed to start service", e)
        }
    }

    companion object {
        private const val TAG = "ChirpWearPhone"
    }
}
