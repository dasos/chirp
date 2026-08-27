package com.chirp.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.chirp.audio.AudioRouteManager
import com.chirp.audio.MediaSessionController
import com.chirp.core.session.SessionPhase
import com.chirp.core.session.SessionController
import com.chirp.speech.AndroidTextToSpeech
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that hosts a conversation session so it survives screen-off
 * and backgrounding while walking. It owns audio focus + Bluetooth SCO routing
 * and the [MediaSessionController] (headset buttons), shows the persistent
 * notification, and forwards control actions to the singleton [SessionController].
 *
 * Every control path — UI, notification buttons, headset media buttons and
 * (Phase 2) the Wear companion — funnels through [onStartCommand] actions, which
 * keeps a single source of truth for session control.
 */
@AndroidEntryPoint
class ConversationService : LifecycleService() {

    @Inject lateinit var controller: SessionController
    @Inject lateinit var audioRouteManager: AudioRouteManager
    @Inject lateinit var mediaSession: MediaSessionController
    @Inject lateinit var tts: AndroidTextToSpeech
    @Inject lateinit var notifications: ConversationNotification

    private var started = false
    private var lastPhase: SessionPhase? = null
    private var lastNotifyAt = 0L
    private var idleTimeoutJob: Job? = null

    private val focusCallback = object : AudioRouteManager.FocusCallback {
        override fun onFocusLost() = controller.pause()
        override fun onTransientLoss() = controller.pause()
        override fun onFocusGained() = controller.resume()
    }

    private val mediaCallback = object : MediaSessionController.Callback {
        override fun onPlay() = controller.resume()
        override fun onPause() = controller.pause()
        override fun onStop() = stopSession()
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createChannel()
        observeState()
        observeScoRouting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> handleStart(
                intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L).takeIf { it >= 0 },
                intent.getStringExtra(EXTRA_TEXT),
            )
            ACTION_TOGGLE_LISTEN -> controller.toggleListen()
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_STOP_SPEAKING -> controller.stopSpeaking()
            ACTION_SUBMIT_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let { controller.submitText(it) }
            ACTION_STOP -> stopSession()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(conversationId: Long?, initialText: String? = null) {
        startForegroundNow()
        if (!started) {
            started = true
            audioRouteManager.startSession(focusCallback)
            mediaSession.activate(mediaCallback)
        }
        controller.start(conversationId)
        // Type-instead-of-speak from an idle screen: start the session, then submit.
        if (!initialText.isNullOrBlank()) controller.submitText(initialText)
    }

    private fun startForegroundNow() {
        val notification = notifications.build(controller.state.value)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun observeState() {
        lifecycleScope.launch {
            controller.state.collect { state ->
                mediaSession.setPlaying(
                    state.phase == SessionPhase.LISTENING ||
                        state.phase == SessionPhase.SPEAKING ||
                        state.phase == SessionPhase.THINKING,
                )

                if (started && !state.active && state.phase == SessionPhase.IDLE) {
                    stopSession()
                    return@collect
                }

                // Auto-stop after a period of idle inactivity.
                if (started && state.active) {
                    val idlePhase = state.phase == SessionPhase.PAUSED || state.phase == SessionPhase.ERROR
                    if (idlePhase && idleTimeoutJob == null) {
                        idleTimeoutJob = lifecycleScope.launch {
                            delay(IDLE_TIMEOUT_MS)
                            if (started) stopSession()
                        }
                    } else if (!idlePhase) {
                        idleTimeoutJob?.cancel()
                        idleTimeoutJob = null
                    }
                }

                // Throttle notification updates (partial text changes rapidly while streaming).
                val now = SystemClock.elapsedRealtime()
                if (state.phase != lastPhase || now - lastNotifyAt > NOTIFY_THROTTLE_MS) {
                    notifications.update(NOTIFICATION_ID, state)
                    lastPhase = state.phase
                    lastNotifyAt = now
                }
            }
        }
    }

    private fun observeScoRouting() {
        lifecycleScope.launch {
            audioRouteManager.scoActive.collect { scoActive ->
                // Route TTS over the SCO voice link when a headset is connected,
                // otherwise play it as media (so it's loud on the phone speaker).
                tts.applyCommunicationRouting(scoActive)
            }
        }
    }

    private fun stopSession() {
        controller.stop()
        audioRouteManager.endSession()
        mediaSession.release()
        started = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        audioRouteManager.endSession()
        mediaSession.release()
        super.onDestroy()
        // Note: the singleton SessionController is intentionally not shut down here
        // so a new session can reuse it; its scope lives for the app's lifetime.
    }

    companion object {
        const val ACTION_START = "com.chirp.action.START"
        const val ACTION_TOGGLE_LISTEN = "com.chirp.action.TOGGLE_LISTEN"
        const val ACTION_PAUSE = "com.chirp.action.PAUSE"
        const val ACTION_RESUME = "com.chirp.action.RESUME"
        const val ACTION_STOP = "com.chirp.action.STOP"
        const val ACTION_STOP_SPEAKING = "com.chirp.action.STOP_SPEAKING"
        const val ACTION_SUBMIT_TEXT = "com.chirp.action.SUBMIT_TEXT"

        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_TEXT = "extra_text"

        private const val NOTIFICATION_ID = 1001
        private const val NOTIFY_THROTTLE_MS = 300L

        /** After the conversation pauses naturally, wait this long before auto-stopping. */
        private const val IDLE_TIMEOUT_MS = 30_000L

        /** Build an intent for a control action; used by the UI/ViewModel. */
        fun intent(context: Context, action: String): Intent =
            Intent(context, ConversationService::class.java).setAction(action)

        fun startIntent(context: Context, conversationId: Long?, initialText: String? = null): Intent =
            intent(context, ACTION_START).apply {
                conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
                initialText?.let { putExtra(EXTRA_TEXT, it) }
            }

        fun submitTextIntent(context: Context, text: String): Intent =
            intent(context, ACTION_SUBMIT_TEXT).putExtra(EXTRA_TEXT, text)
    }
}
