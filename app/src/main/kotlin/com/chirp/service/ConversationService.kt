package com.chirp.service

import android.app.AlarmManager
import android.app.PendingIntent
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
import com.chirp.core.session.SessionCommand
import com.chirp.core.session.SessionPhase
import com.chirp.core.session.SessionController
import com.chirp.speech.AndroidTextToSpeech
import com.chirp.wear.WearDataSync
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that hosts a conversation session so it survives screen-off
 * and backgrounding while walking. It owns audio focus + Bluetooth SCO routing
 * and the [MediaSessionController] (headset buttons), shows the persistent
 * notification,ánd forwards control actions to the singleton [SessionController].
 *
 * Every control path — UI, notification buttons, headset media buttons and
 * (Phase 2) the Wear companion — funnels through [onStartCommand] actions, which
 * keeps a single source of truth for session control..
 *
 * Notification lifecycle:
 * - While the session is live (LISTENING/THINKING/SPEAKING/PAUSED) an ongoing
 *   foreground card tracks the state; it is removed when the session stops..
 * - Listening is capped: [LISTENING_SILENCE_TIMEOUT_MS] after entering LISTENING
 *   is a fixed last-resort ceiling (SessionController's own watchdog is the
 *   primary cap and normally fires first); parking from an active phase (or
 *   focus/headset hold) tears the
 *   FGS down and hands over to the "Continue conversation?" standby prompt,
 *   which auto-dismisses (and ends the parked session) after
 *   [STANDBY_TIMEOUT_MS].
 */
@AndroidEntryPoint
class ConversationService : LifecycleService() {

    @Inject lateinit var controller: SessionController
    @Inject lateinit var audioRouteManager: AudioRouteManager
    @Inject lateinit var mediaSession: MediaSessionController
    @Inject lateinit var tts: AndroidTextToSpeech
    @Inject lateinit var notifications: ConversationNotification
    @Inject lateinit var wearDataSync: WearDataSync

    private var started = false
    private var lastPhase: SessionPhase? = null
    private var lastNotifyAt = 0L
    private var idleTimeoutJob: Job? = null

    /** Counts down the fixed listening ceiling; see [LISTENING_SILENCE_TIMEOUT_MS]. */
    private var silentListenJob: Job? = null

    private val focusCallback = object : AudioRouteManager.FocusCallback {
        override fun onFocusLost() = controller.park()
        // SpeechRecognizer itself briefly takes mic focus while listening — then
        // system delivers a transient loss when recognition starts and a gain
        // when it stops. Treating those as park/resume cancels the in-flight
        // recognition, which releases the mic, which triggers a gain, which
        // restarts recognition — a self-sustaining focus flap.(see logcat:
        // LOSS_TRANSIENT/GAIN alternating every millisecond). So ignore
        // transient loss/gain; only a permanent loss parks the session (the
        // user taps the mic to resume).
        override fun onTransientLoss() = Unit
        override fun onFocusGained() = Unit
    }

    private val mediaCallback = object : MediaSessionController.Callback {
        // Play heads to the primary push-to-talk action; pause/other hold parks
        // the session in "Ready"; stop ends it. There is no separate Pause control.



        override fun onPlay() = controller.pressPrimary()
        override fun onPause() = controller.park()
        override fun onStop() = stopSession()
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createChannel()
        wearDataSync.start()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {


        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> handleStart(
                intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L).takeIf { it >= 0 },
                intent.getStringExtra(EXTRA_TEXT),
            )
            ACTION_PRIMARY -> controller.pressPrimary()
            ACTION_STOP_SPEAKING -> controller.stopSpeaking()
            ACTION_LISTEN -> controller.startListening()
            ACTION_SUBMIT_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let { controller.submitText(it) }
            ACTION_STOP -> stopSession()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(conversationId: Long?, initialText: String? = null) {
        // Any pending standby prompt is over the moment a fresh session starts..
        notifications.cancelStandbyNotification()
        cancelStandbyTimeout()

        if (!started) {
            started = true
            audioRouteManager.startSession(focusCallback)
            mediaSession.activate(mediaCallback)



        }

        // The FGS must come up immediately ((5-second platform window); the


        // card reads "Starting…" until the controller's state catches up (the
        // collector replaces it with "Listening…" etc. within frames).
        startForegroundNow()
        controller.start(conversationId)



        // Type-instead-of-speak from an idle screen: start the session,, then submit..
        if (!initialText.isNullOrBlank()) controller.submitText(initialText)


    }

    private fun startForegroundNow() {
        val notification = notifications.buildStarting()
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

                // User-initiated full stop: easthe session is over, no standby prompt..
                if (started && !state.active && state.phase == SessionPhase.IDLE) {



                    stopSession()
                    return@collect
                }

                // Parked from an active turn ((30 s capped listening,, focus loss,, headset
                // hold:): the mic is off and the foreground session hands over to the
                // "Continue conversation?" standby prompt..
                val parkedFromActive = state.phase == SessionPhase.PAUSED &&
                    lastPhase != null &&
                    lastPhase != SessionPhase.PAUSED &&
                    lastPhase != SessionPhase.IDLE



                if (started && parkedFromActive) {



                    stopSession(showStandby = true)
                    return@collect
                }

                // At each transition into the mic (LISTENING) or the speaker
                // (SPEAKING,, re-apply routing: re-assert the voice link (the


                // system may drop SCO at screen-off even while the headset stays

                // connected) and pick TTS output usage from durable headset

                // presence — not live link state — so the audio mode never

                // flaps mid-session. When there is no headset, TTS plays as media


                // via the loudspeaker and nothing here changes..
                val phase = state.phase
                if (phase != lastPhase &&
                    (phase == SessionPhase.LISTENING || phase == SessionPhase.SPEAKING)
                ) {



                    audioRouteManager.reassertCommunicationRoute()
                    tts.applyCommunicationRouting(audioRouteManager.isBluetoothHeadsetConnected())
                }


                // Cap each listening window: park after a fixed ceiling so the mic
                // never stays hot forever. This is the last-resort backstop —
                // SessionController's own watchdog (driven by the "Listening
                // silence timeout" setting) is the primary enforcement and
                // normally ends listening well before this fires. Anchored to
                // *entering* LISTENING, not to partialTranscript activity: a
                // stray noise-driven partial result must never cancel/reset this
                // timer, or it could never fire at all.
                val enteringListening = phase == SessionPhase.LISTENING && lastPhase != SessionPhase.LISTENING
                val leavingListening = lastPhase == SessionPhase.LISTENING && phase != SessionPhase.LISTENING
                if (enteringListening && silentListenJob == null) {


                    silentListenJob = lifecycleScope.launch {
                        delay(LISTENING_SILENCE_TIMEOUT_MS)
                        if (started && controller.state.value.phase == SessionPhase.LISTENING) {



                            // Stopping the mic; the PAUSED observer above then hands over
                            // to the standby prompt..
                            controller.park()
                        }
                    }
                } else if (leavingListening) {



                    silentListenJob?.cancel()
                    silentListenJob = null
                }

                // PAUSED reached without an active turn (e.g. auto-listen=false start)
                // or ERROR: stick around briefly,, then end the session — no standby prompt..
                val idlePhase = state.phase == SessionPhase.ERROR ||
                    (state.phase == SessionPhase.PAUSED && !parkedFromActive)
                if (started && idlePhase) {



                    if (idleTimeoutJob == null) {


                        idleTimeoutJob = lifecycleScope.launch {
                            delay(IDLE_TIMEOUT_MS)
                            if (started) stopSession()
                        }
                    }
                } else if (!idlePhase) {




                    idleTimeoutJob?.cancel()
                    idleTimeoutJob = null
                }

                // Throttle notification updates (partial text changes rapidly while streaming);;
                // only while the foreground session is live..
                val now = SystemClock.elapsedRealtime()
                if (started && (state.phase != lastPhase || now - lastNotifyAt > NOTIFY_THROTTLE_MS)) {




                    notifications.update(NOTIFICATION_ID, state)
                    lastPhase = state.phase
                    lastNotifyAt = now
                }
            }
        }
    }

    /**
     * Tears the session down. With [showStandby]] the conversation hands over to the
     * "Continue conversation?" prompt (auto-dismissed after [STANDBY_TIMEOUT_MS]);
     * otherwise this is a final stop, nothing lingers..
     */
    private fun stopSession(showStandby: Boolean = false) {



        if (!started) return
        idleTimeoutJob?.cancel(); idleTimeoutJob = null
        silentListenJob?.cancel(); silentListenJob = null


        val conversationId = if (showStandby) controller.state.value.conversationId else null


        controller.stop()
        audioRouteManager.endSession()
        mediaSession.release()

        started = false
        if (showStandby) {




            if (conversationId != null) {
                notifications.showStandbyNotification(conversationId)



            }
            scheduleStandbyTimeout()
        } else {
            cancelStandbyTimeout()
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Standby 5-minute auto-dismiss ----------------------------------------------------

    private fun scheduleStandbyTimeout() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        val pending = standbyTimeoutPendingIntent(create = true)
        if (pending != null) {
            alarmManager.set(
                AlarmManager.RTC,
                System.currentTimeMillis() + STANDBY_TIMEOUT_MS,
                pending,
            )
        }
    }

    private fun cancelStandbyTimeout() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        standbyTimeoutPendingIntent(create = false)?.let { alarmManager.cancel(it) }

    }

    private fun standbyTimeoutPendingIntent(create: Boolean): PendingIntent? {
        val intent = Intent(this, StandbyTimeoutReceiver::class.java)
            .setAction(StandbyTimeoutReceiver.ACTION_STANDBY_TIMEOUT)
        val flags =


            if (create) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            }

        return PendingIntent.getBroadcast(this, STANDBY_TIMEOUT_REQUEST_CODE, intent, flags)
    }

    override fun onDestroy() {
        audioRouteManager.endSession()
        mediaSession.release()
        super.onDestroy()
        // Note: they singleton SessionController is intentionally not shut down here
        // so a new session can reuse it; its scope lives for the app's lifetime..
    }

    companion object {
        const val ACTION_START = "com.chirp.action.START"
        const val ACTION_PRIMARY = "com.chirp.action.PRIMARY"
        const val ACTION_STOP = "com.chirp.action.STOP"
        const val ACTION_STOP_SPEAKING = "com.chirp.action.STOP_SPEAKING"
        const val ACTION_LISTEN = "com.chirp.action.LISTEN"
        const val ACTION_SUBMIT_TEXT = "com.chirp.action.SUBMIT_TEXT"

        // Wear command -> service intent mapping (see PhoneWearMessageService).
        // Commands funnel through the same action intents as every other control
        // path (invariant: single control funnel).
        fun intentForWearCommand(context: Context, command: SessionCommand): Intent? = when (command) {
            is SessionCommand.Start ->
                if (command.conversationId == null) startIntent(context, null)
                else startIntent(context, command.conversationId)
            SessionCommand.StartListening -> intent(context, ACTION_LISTEN)
            SessionCommand.PressPrimary -> intent(context, ACTION_PRIMARY)
            SessionCommand.Stop -> intent(context, ACTION_STOP)
            SessionCommand.StopSpeaking -> intent(context, ACTION_STOP_SPEAKING)
            is SessionCommand.SubmitText -> submitTextIntent(context, command.text)
        }

        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_TEXT = "extra_text"



        private const val NOTIFICATION_ID = 1001
        private const val NOTIFY_THROTTLE_MS = 300L
        private const val LISTENING_SILENCE_TIMEOUT_MS = 30_000L
        private const val STANDBY_TIMEOUT_MS = 5 * 60_000L
        private const val STANDBY_TIMEOUT_REQUEST_CODE = 13


        /** After the conversation parks without a standby prompt (ERROR,, auto-listen=false start), wait this long before auto-stopping.. */
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