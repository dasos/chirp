package com.chirp.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.chirp.core.speech.SpeechToTextEngine
import com.chirp.core.speech.SttConfig
import com.chirp.core.speech.SttError
import com.chirp.core.speech.SttEvent
import com.chirp.core.speech.SttTurnWindow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device STT via [SpeechRecognizer]. The recognizer must be created and
 * driven on the main thread, so all engine calls are posted to the main looper;
 * recognition callbacks are forwarded into a [callbackFlow]. Swap this out for a
 * server-side Whisper engine by providing a different [SpeechToTextEngine].
 *
 * # Silence window ownership (Option A — implemented)
 *
 * The system recognizer finalizes sessions at its own internal silence
 * threshold and mostly ignores the `EXTRA_SPEECH_INPUT_*` extras, so the
 * configured `SttConfig.silenceTimeoutMs` is enforced app-side:
 *
 *  - [com.chirp.core.speech.SttTurnWindow] (in :core) owns the turn-level
 *    silence decision: a turn ends only after `silenceTimeoutMs` of silence
 *    since the last detected voice.
 *  - A recognizer session that ends early (NO_MATCH / SPEECH_TIMEOUT / early
 *    onResults) is restarted and its finalized segments are stitched, so a
 *    pause between utterances no longer cuts the turn short.
 *  - The turn emits `FinalResult` (stitched transcript) or `Error(NO_MATCH)`
 *    only when the window actually expires.
 *
 * # Option B — future work (full pipeline ownership)
 *
 * This still leans on the system recognizer as the transcription engine; its
 * endpointing can't be *forced* to hold past its internal limits, and there is
 * a ~100–200ms audio dead-band whenever a session restarts. If a true
 * cross-device silence floor is ever needed, own the mic end-to-end instead:
 * `AudioRecord` + an on-device VAD (e.g. Silero / energy gate) to close the
 * utterance at exactly `silenceTimeoutMs`, then hand the recorded clip to an
 * ASR model (on-device Whisper or a server endpoint). That loses live
 * partial-transcript / waveform streaming and adds model weight, but it is
 * deterministic on every device/OEM.
 */
@Singleton
class AndroidSpeechToText @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechToTextEngine {

    override suspend fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun listen(config: SttConfig): Flow<SttEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(SttEvent.Error(SttError.NOT_AVAILABLE))
            close()
            return@callbackFlow
        }

        Log.d(TAG, "listen: starting speech recognition with silenceTimeout=${config.silenceTimeoutMs}ms")

        val mainHandler = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null

        // The system recognizer ends sessions at its own internal silence
        // threshold (the EXTRA_SPEECH_INPUT_* extras are advisory and often
        // ignored), so the silence window is owned app-side: a session that ends
        // while [SttTurnWindow] still has time left is restarted and its results
        // are stitched, and the turn completes only after silenceTimeoutMs of
        // silence since the last detected voice. All time is wall-clock, so it
        // stays correct even if the recognizer stops delivering callbacks.
        val window = SttTurnWindow(
            startedAt = SystemClock.elapsedRealtime(),
            silenceTimeoutMs = config.silenceTimeoutMs,
        )
        var listening = false // a recognizer session is currently in-flight

        fun restart() {
            mainHandler.post {
                if (!window.expired(SystemClock.elapsedRealtime())) {
                    Log.d(TAG, "re-listening within the silence window")
                    runCatching { recognizer?.startListening(buildIntent(config)) }
                }
            }
        }

        /** Ends the turn with the stitched transcript, or NO_MATCH if nothing was heard. */
        fun finishTurn() {
            val text = window.accumulated.trim()
            if (text.isNotBlank()) {
                trySend(SttEvent.FinalResult(text))
            } else {
                trySend(SttEvent.Error(SttError.NO_MATCH))
            }
            close()
        }

        // Watches the window while a session is in-flight and ends the turn
        // exactly when the configured silence elapsed. Child of the flow scope:
        // auto-cancelled when the flow completes.
        launch {
            while (isActive) {
                if (listening && window.expired(SystemClock.elapsedRealtime())) {
                    Log.d(TAG, "window expired: stopping recognizer")
                    mainHandler.post { runCatching { recognizer?.stopListening() } }
                }
                delay(SILENCE_POLL_MS)
            }
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                trySend(SttEvent.ReadyForSpeech)
            }

            override fun onBeginningOfSpeech() {
                trySend(SttEvent.BeginningOfSpeech)
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (rmsdB >= VOICE_RMS_THRESHOLD_DB) window.onVoice(SystemClock.elapsedRealtime())
                trySend(SttEvent.RmsChanged(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                trySend(SttEvent.EndOfSpeech)
            }

            override fun onError(error: Int) {
                listening = false
                val kind = mapError(error)
                if ((kind == SttError.NO_MATCH) || (kind == SttError.SPEECH_TIMEOUT)) {
                    // Normal no-speech/end-of-utterance outcome: keep the whole
                    // turn alive until our own silence window elapses.
                    val now = SystemClock.elapsedRealtime()
                    Log.d(
                        TAG,
                        "session ended (${errorCodeName(error)}); " +
                            (if (window.expired(now)) "finishing turn" else "re-listening"),
                    )
                    if (window.expired(now)) finishTurn() else restart()
                } else {
                    Log.w(TAG, "onError: code=$error (${errorCodeName(error)})")
                    trySend(SttEvent.Error(kind))
                    close()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { partial ->
                    if (partial.isNotBlank()) {
                        window.onVoice(SystemClock.elapsedRealtime())
                        // Stitched history + the live hypothesis of this session.
                        val display = if (window.accumulated.isBlank()) {
                            partial
                        } else {
                            "${window.accumulated} $partial"
                        }
                        trySend(SttEvent.PartialResult(display))
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val now = SystemClock.elapsedRealtime()
                val text = firstResult(results).orEmpty()
                window.addSegment(text, now) // folds text; blank is a no-op, non-blank counts as voice
                if (window.expired(now)) {
                    finishTurn()
                } else {
                    restart()
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        mainHandler.post {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
                startListening(buildIntent(config))
            }
        }

        awaitClose {
            mainHandler.post {
                recognizer?.let {
                    runCatching { it.stopListening() }
                    runCatching { it.destroy() }
                }
                recognizer = null
            }
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun buildIntent(config: SttConfig): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, config.preferOffline)
            config.languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            // Advisory silence timings (honored by some recognizers).
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                config.silenceTimeoutMs,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                config.silenceTimeoutMs,
            )
        }

    private fun mapError(error: Int): SttError = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> SttError.NO_MATCH
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttError.SPEECH_TIMEOUT
        SpeechRecognizer.ERROR_NETWORK -> SttError.NETWORK
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SttError.NETWORK_TIMEOUT
        SpeechRecognizer.ERROR_AUDIO -> SttError.AUDIO
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttError.PERMISSION
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SttError.BUSY
        SpeechRecognizer.ERROR_CLIENT -> SttError.CLIENT
        SpeechRecognizer.ERROR_SERVER -> SttError.SERVER
        else -> SttError.UNKNOWN
    }

    private fun errorCodeName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMISSION"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        else -> "UNKNOWN($error)"
    }

    companion object {
        private const val TAG = "AndroidSpeechToText"

        /** RMS (dB) below which the input counts as silence for the watchdog. */
        private const val VOICE_RMS_THRESHOLD_DB = 4.0f

        /** How often the watchdog rechecks the silence deadline. */
        private val SILENCE_POLL_MS = 200.milliseconds
    }
}
