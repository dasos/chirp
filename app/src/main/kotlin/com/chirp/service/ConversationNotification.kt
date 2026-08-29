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
 * Builds and updates the conversation notification:
 *
 * - **Active session** (`[build]`/`[buildStarting]`/`[update]`): an ongoing, silent
 *   foreground-service card reflecting the current [SessionPhase] — "Listening…",
 *   "Thinking…", "Speaking…". Removed when the service stops..
 *
 * - **Standby prompt** (`[showStandbyNotification]`): after the session parks (30 s of
 *   silence, focus loss, headset hold), the foreground service tears down and a
 *   regular "Continue conversation?" notification takes its place, with Resume
 *   and End actions. It auto-dismisses (and ends any parked session) after
 *   5 minutes (scheduled via [ConversationService]).
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

    // --- Active session card ------------------------------------------------------------

    /**
     * Card shown while the foreground service comes up, before the controller's
     * post-start state has landed — so the first thing the user sees is never a
     * stale "Tap to open the conversation." card..
     */
    fun buildStarting(): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_chirp)
            .setContentTitle(context.getString(R.string.notification_starting_title))
            .setContentText(context.getString(R.string.notification_starting_content))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notification_starting_content)))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

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

            builder.setWhen(state.thinkingStartedAtMillis!!).setShowWhen(true).setUsesChronometer(true)


        } else {
            builder.setShowWhen(false)
        }

        if (state.phase == SessionPhase.SPEAKING) {


            builder.addAction(
                R.drawable.ic_stop,
                context.getString(R.string.notification_action_stop_speaking),
                servicePendingIntent(ConversationService.ACTION_STOP_SPEAKING, 2),
            )

        }

        builder.addAction(
            R.drawable.ic_stop,
            context.getString(R.string.notification_action_stop),
            servicePendingIntent(ConversationService.ACTION_STOP, 3),
        )

        return builder.build()
    }

    fun update(notificationId: Int, state: SessionState) {
        val manager = NotificationManagerCompat.from(context)
        // POST_NOTIFICATIONS is requested at startup; guard the call regardless..
        if (manager.areNotificationsEnabled()) {
            runCatching { manager.notify(notificationId, build(state)) }
        }
    }

    // --- Standby "Continue conversation?" prompt -----------------------------------------

    fun showStandbyNotification(conversationId: Long?) {
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            runCatching { manager.notify(STANDBY_NOTIFICATION_ID, buildStandbyNotification(conversationId)) }
        }
    }

    fun cancelStandbyNotification() {
        runCatching { NotificationManagerCompat.from(context).cancel(STANDBY_NOTIFICATION_ID) }
    }

    private fun buildStandbyNotification(conversationId: Long?): android.app.Notification {
        val resumeIntent = Intent(context, ConversationService::class.java)
            .setAction(ConversationService.ACTION_START)
        if (conversationId != null) {
            resumeIntent.putExtra(ConversationService.EXTRA_CONVERSATION_ID, conversationId)
        }
        val resumePI = PendingIntent.getForegroundService(
            context, STANDBY_RESUME_REQUEST_CODE, resumeIntent, PI_FLAGS,
        )
        val dismissIntent = Intent(context, StandbyTimeoutReceiver::class.java)
            .setAction(StandbyTimeoutReceiver.ACTION_STANDBY_TIMEOUT)
        val dismissPI = PendingIntent.getBroadcast(
            context, STANDBY_DISMISS_REQUEST_CODE, dismissIntent, PI_FLAGS,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_chirp)
            .setContentTitle(context.getString(R.string.notification_standby_title))
            .setContentText(context.getString(R.string.notification_standby_content))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notification_standby_content)))
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_play, context.getString(R.string.notification_action_resume), resumePI)
            .addAction(R.drawable.ic_stop, context.getString(R.string.notification_action_end), dismissPI)
            .build()
    }

    // --- Shared helpers ------------------------------------------------------------------

    private fun titleFor(phase: SessionPhase): String = when (phase) {
        SessionPhase.IDLE -> context.getString(R.string.notification_idle_title)
        SessionPhase.LISTENING -> context.getString(R.string.notification_listening_title)
        SessionPhase.THINKING -> context.getString(R.string.notification_thinking_title)
        SessionPhase.SPEAKING -> context.getString(R.string.notification_speaking_title)
        SessionPhase.PAUSED -> context.getString(R.string.notification_paused_title)
        SessionPhase.ERROR -> context.getString(R.string.notification_error_title)
    }

    private fun contentFor(state: SessionState): String = when {
        // Errors always surface the human-readable message..
        state.errorMessage != null && state.phase == SessionPhase.ERROR -> state.errorMessage!!
        state.phase == SessionPhase.LISTENING && state.partialTranscript.isNotBlank() ->
            context.getString(R.string.notification_transcript_quoted, state.partialTranscript)
        state.phase == SessionPhase.LISTENING -> context.getString(R.string.notification_listening_content)
        state.partialResponse.isNotBlank() -> state.partialResponse

        state.phase == SessionPhase.THINKING -> context.getString(R.string.notification_thinking_content)
        state.phase == SessionPhase.SPEAKING -> context.getString(R.string.notification_speaking_content)
        state.phase == SessionPhase.PAUSED -> context.getString(R.string.notification_paused_content)
        state.phase == SessionPhase.ERROR -> context.getString(R.string.notification_error_content)
        else -> context.getString(R.string.notification_idle_content)
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
        const val STANDBY_NOTIFICATION_ID = 1002
        private const val STANDBY_RESUME_REQUEST_CODE = 11
        private const val STANDBY_DISMISS_REQUEST_CODE = 12
        private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    }
}