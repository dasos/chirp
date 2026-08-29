package com.chirp.wear

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
        if (messageEvent.path != WearContract.PATH_COMMAND) return
        val command = WearContract.decodeCommand(messageEvent.data) ?: return
        val intent = ConversationService.intentForWearCommand(this, command) ?: return
        startService(intent)
    }
}