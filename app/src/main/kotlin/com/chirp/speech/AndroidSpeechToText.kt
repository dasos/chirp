package com.chirp.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.chirp.core.speech.SpeechToTextEngine
import com.chirp.core.speech.SttConfig
import com.chirp.core.speech.SttError
import com.chirp.core.speech.SttEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device STT via [SpeechRecognizer]. The recognizer must be created and
 * driven on the main thread, so all engine calls are posted to the main looper;
 * recognition callbacks are forwarded into a [callbackFlow]. Swap this out for a
 * server-side Whisper engine by providing a different [SpeechToTextEngine].
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

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SttEvent.ReadyForSpeech)
            }

            override fun onBeginningOfSpeech() {
                trySend(SttEvent.BeginningOfSpeech)
            }

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SttEvent.RmsChanged(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                trySend(SttEvent.EndOfSpeech)
            }

            override fun onError(error: Int) {
                Log.w(TAG, "onError: code=$error (${errorCodeName(error)})")
                trySend(SttEvent.Error(mapError(error)))
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { trySend(SttEvent.PartialResult(it)) }
            }

            override fun onResults(results: Bundle?) {
                trySend(SttEvent.FinalResult(firstResult(results).orEmpty()))
                close()
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
    }
}
