package com.chirp.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.chirp.core.speech.TextToSpeechEngine
import com.chirp.core.speech.TtsException
import com.chirp.core.speech.TtsVoice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * On-device TTS via [TextToSpeech]. [speak] suspends until the utterance finishes
 * (so the controller can speak sentence-by-sentence and await each), and
 * cancelling the coroutine stops playback — which is exactly how pause /
 * stop-speaking interrupt the loop.
 *
 * Audio routing: defaults to USAGE_VOICE_COMMUNICATION so speech rides the
 * Bluetooth SCO link alongside the mic; [applyCommunicationRouting] switches to
 * USAGE_MEDIA when no SCO headset is in use (so it plays loudly on the speaker).
 * The foreground service drives this from the [com.chirp.audio.AudioRouteManager].
 */
@Singleton
class AndroidTextToSpeech @Inject constructor(
    @ApplicationContext context: Context,
) : TextToSpeechEngine {

    private val appContext = context.applicationContext

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var preferCommunication = true

    private val pending = ConcurrentHashMap<String, CancellableContinuation<Unit>>()

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { pending.remove(it)?.let { c -> if (c.isActive) c.resume(Unit) } }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { pending.remove(it)?.let { c -> if (c.isActive) c.resume(Unit) } }
        }

        @Deprecated("Deprecated in API 21", ReplaceWith(""))
        override fun onError(utteranceId: String?) {
            failUtterance(utteranceId, "Speech synthesis error")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            failUtterance(utteranceId, "Speech synthesis error ($errorCode)")
        }
    }

    override suspend fun init(): Boolean {
        if (ready) return true
        return suspendCancellableCoroutine { cont ->
            var engineRef: TextToSpeech? = null
            val listener = TextToSpeech.OnInitListener { status ->
                val engine = engineRef
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    engine.setOnUtteranceProgressListener(progressListener)
                    engine.setAudioAttributes(buildAudioAttributes())
                    runCatching { if (engine.voice == null) engine.language = Locale.getDefault() }
                    ready = true
                    if (cont.isActive) cont.resume(true)
                } else {
                    ready = false
                    if (cont.isActive) cont.resume(false)
                }
            }
            val engine = TextToSpeech(appContext, listener)
            engineRef = engine
            tts = engine
        }
    }

    override suspend fun speak(text: String, utteranceId: String) {
        val engine = tts
        if (!ready || engine == null) throw TtsException("Text-to-speech is not initialized")
        if (text.isBlank()) return

        suspendCancellableCoroutine { cont ->
            pending[utteranceId] = cont
            cont.invokeOnCancellation {
                pending.remove(utteranceId)
                runCatching { engine.stop() }
            }
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                pending.remove(utteranceId)
                if (cont.isActive) cont.resumeWithException(TtsException("Failed to enqueue speech"))
            }
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun setSpeed(rate: Float) {
        runCatching { tts?.setSpeechRate(min(3.0f, max(0.25f, rate))) }
    }

    override fun setVoice(voiceId: String?) {
        val engine = tts ?: return
        if (voiceId.isNullOrBlank()) return
        runCatching {
            engine.voices?.firstOrNull { it.name == voiceId }?.let { engine.voice = it }
        }
    }

    override suspend fun availableVoices(): List<TtsVoice> {
        if (!ready) init()
        val engine = tts ?: return emptyList()
        return runCatching {
            engine.voices.orEmpty()
                .map {
                    TtsVoice(
                        id = it.name,
                        displayName = "${it.locale.displayName} (${it.name})",
                        localeTag = it.locale.toLanguageTag(),
                        needsNetwork = it.isNetworkConnectionRequired,
                    )
                }
                .sortedBy { it.displayName }
        }.getOrDefault(emptyList())
    }

    override fun shutdown() {
        pending.values.forEach { if (it.isActive) it.resume(Unit) }
        pending.clear()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    /** Switches TTS output between the SCO voice link and normal media output. */
    fun applyCommunicationRouting(communication: Boolean) {
        if (preferCommunication == communication) return
        preferCommunication = communication
        if (ready) runCatching { tts?.setAudioAttributes(buildAudioAttributes()) }
    }

    private fun failUtterance(utteranceId: String?, message: String) {
        utteranceId?.let { pending.remove(it)?.let { c -> if (c.isActive) c.resumeWithException(TtsException(message)) } }
    }

    private fun buildAudioAttributes(): AudioAttributes {
        val usage = if (preferCommunication) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
