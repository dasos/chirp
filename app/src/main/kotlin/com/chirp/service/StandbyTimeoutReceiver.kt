package com.chirp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chirp.core.session.SessionController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles the "Continue conversation?" standby end: the user's **End** action and the
 * 5-minute auto-dismiss (both reuse the same action). It cancels the standby
 * notification and ensures no parked session state lingers in the singleton
 * controller — so the app never behaves as if a conversation were alive after
 * the prompt is gone..
 */
@AndroidEntryPoint
class StandbyTimeoutReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: SessionController
    @Inject lateinit var notifications: ConversationNotification

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STANDBY_TIMEOUT) return

        notifications.cancelStandbyNotification()
        // No-op if there is no parked session to end..
        controller.stop()
    }

    companion object {
        const val ACTION_STANDBY_TIMEOUT = "com.chirp.action.STANDBY_TIMEOUT"
    }
}