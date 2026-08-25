package com.chirp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chirp.MainActivity
import com.chirp.R
import com.chirp.core.session.SessionPhase
import com.chirp.core.session.SessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and updates the persistent conversation notification: current state,
 * the latest partial response, an elapsed-time chronometer while "Thinking…",
 * and Pause/Resume + Stop (+ Stop speaking) action buttons.
 */
@Singleton
class ConversationNotification @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Conversation",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing Chirp voice conversation"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(state: SessionState): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_chirp)
            .setContentTitle(titleFor(state.phase))
            .setContentText(contentFor(state))
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentFor(state)))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Elapsed-time chronometer while waiting on the model.
        if (state.phase == SessionPhase.THINKING && state.thinkingStartedAtMillis != null) {
            builder.setWhen(state.thinkingStartedAtMillis!!)
                .setShowWhen(true)
                .setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }

        if (state.phase == SessionPhase.PAUSED) {
            builder.addAction(
                R.drawable.ic_play,
                "Resume",
                servicePendingIntent(ConversationService.ACTION_RESUME, 1),
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause,
                "Pause",
                servicePendingIntent(ConversationService.ACTION_PAUSE, 2),
            )
        }

        if (state.phase == SessionPhase.SPEAKING) {
            builder.addAction(
                R.drawable.ic_stop,
                "Stop speaking",
                servicePendingIntent(ConversationService.ACTION_STOP_SPEAKING, 3),
            )
        }

        builder.addAction(
            R.drawable.ic_stop,
            "Stop",
            servicePendingIntent(ConversationService.ACTION_STOP, 4),
        )

        return builder.build()
    }

    fun update(notificationId: Int, state: SessionState) {
        val manager = NotificationManagerCompat.from(context)
        // POST_NOTIFICATIONS is requested at startup; guard the call regardless.
        if (manager.areNotificationsEnabled()) {
            runCatching { manager.notify(notificationId, build(state)) }
        }
    }

    private fun titleFor(phase: SessionPhase): String = when (phase) {
        SessionPhase.IDLE -> "Chirp"
        SessionPhase.LISTENING -> "Listening…"
        SessionPhase.THINKING -> "Thinking…"
        SessionPhase.SPEAKING -> "Speaking…"
        SessionPhase.PAUSED -> "Paused"
        SessionPhase.ERROR -> "Connection problem"
    }

    private fun contentFor(state: SessionState): String = when {
        state.errorMessage != null && state.phase == SessionPhase.ERROR -> state.errorMessage!!
        state.phase == SessionPhase.LISTENING && state.partialTranscript.isNotBlank() ->
            "“${state.partialTranscript}”"
        state.partialResponse.isNotBlank() -> state.partialResponse
        state.phase == SessionPhase.PAUSED -> "Tap Resume or the mic to continue."
        else -> "Tap to open the conversation."
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PI_FLAGS)
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ConversationService::class.java).setAction(action)
        return PendingIntent.getService(context, requestCode, intent, PI_FLAGS)
    }

    companion object {
        const val CHANNEL_ID = "chirp_conversation"
        private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
