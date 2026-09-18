package com.chirp.speech

import android.os.SystemClock
import android.util.Log
import com.chirp.audio.AudioRouteManager
import com.chirp.core.speech.PcmClip
import com.chirp.core.speech.SpeechToTextEngine
import com.chirp.core.speech.SttConfig
import com.chirp.core.speech.SttError
import com.chirp.core.speech.SttEvent
import com.chirp.core.speech.Transcriber
import com.chirp.core.speech.TranscriptionException
import com.chirp.core.speech.UtteranceAssembler
import com.chirp.core.speech.Vad
import com.chirp.core.util.DispatcherProvider
import com.chirp.speech.mic.MicCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SpeechToTextEngine] that owns the microphone end to end: `AudioRecord` →
 * Silero VAD → [Transcriber].
 *
 * This replaces the previous `SpeechRecognizer` implementation, for two reasons
 * that turn out to be the same reason. The platform recognizer plays an
 * unsuppressable earcon on every `startListening()` — the sound lives in a
 * Play-updatable app, not the framework, so no API can silence it — and it
 * finalizes sessions at its own internal silence threshold rather than the
 * configured one. The old engine had to restart it repeatedly within a single
 * turn and stitch the fragments together, which is exactly what turned one beep
 * into several.
 *
 * Owning the microphone fixes both: nothing here plays a sound, and
 * [UtteranceAssembler] ends the turn at exactly `silenceTimeoutMs` after the
 * last speech frame.
 *
 * The trade-off is that transcription happens after speech ends rather than
 * arriving as the user talks, so there are no live partial results. The turn
 * emits [SttEvent.EndOfSpeech] when the upload starts, so the UI can explain
 * the pause rather than looking stalled.
 */
@Singleton
class PipelineSpeechToText @Inject constructor(
    private val vad: Vad,
    private val transcriber: Transcriber,
    private val audioRouteManager: AudioRouteManager,
    private val dispatchers: DispatcherProvider,
) : SpeechToTextEngine {

    /**
     * Serializes turns. `SessionController` only ever collects one listening
     * flow at a time, but the VAD carries recurrent state and the microphone is
     * exclusive, so overlapping collections must never interleave.
     */
    private val turnLock = Mutex()

    override suspend fun isAvailable(): Boolean = true

    override fun listen(config: SttConfig): Flow<SttEvent> = flow {
        turnLock.withLock { runTurn(config) }
    }.flowOn(dispatchers.io)

    private suspend fun FlowCollector<SttEvent>.runTurn(config: SttConfig) {
        Log.d(TAG, "listen: silenceTimeout=${config.silenceTimeoutMs}ms")

        // The route manager has already selected the SCO device for this phase,
        // so the source choice just has to match it.
        val mic = MicCapture(
            preferCommunicationSource = audioRouteManager.isBluetoothHeadsetConnected(),
        )

        try {
            mic.start()
        } catch (e: MicCapture.MicUnavailableException) {
            Log.w(TAG, "microphone unavailable", e)
            emit(SttEvent.Error(SttError.AUDIO))
            return
        }

        vad.reset()
        emit(SttEvent.ReadyForSpeech)

        val assembler = UtteranceAssembler(
            startedAtMs = SystemClock.elapsedRealtime(),
            silenceTimeoutMs = config.silenceTimeoutMs,
            frameDurationMs = Vad.FRAME_DURATION_MS,
        )

        // Frames captured for this utterance. Until speech is detected this is
        // trimmed to a short pre-roll, so the leading syllable survives the VAD's
        // reaction time instead of being clipped off the front.
        val captured = ArrayDeque<ShortArray>()
        var announcedSpeech = false
        var outcome = UtteranceAssembler.Decision.NO_SPEECH
        var frameCount = 0L

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                frameCount++

                val frame = try {
                    mic.readFrame()
                } catch (e: MicCapture.MicUnavailableException) {
                    Log.w(TAG, "microphone read failed", e)
                    emit(SttEvent.Error(SttError.AUDIO))
                    return
                }
                if (frame == null) break // mic closed underneath us

                val rms = MicCapture.rmsDb(frame)
                emit(SttEvent.RmsChanged(rms))

                val isSpeech = vad.isSpeech(frame)

                // Throttled diagnostic: raw level + VAD probability at ~3.1 Hz.
                if (frameCount % LD == 0L || frameCount == 1L) {
                    Log.d(
                        TAG,
                        "frame=$frameCount rms=$rms " +
                            "vadProbability=${vad.lastProbability} speech=$isSpeech",
                    )
                }

                captured.addLast(frame)

                val decision = assembler.onFrame(isSpeech, SystemClock.elapsedRealtime())

                if (assembler.hasSpeech) {
                    if (!announcedSpeech) {
                        announcedSpeech = true
                        emit(SttEvent.BeginningOfSpeech)
                    }
                } else {
                    while (captured.size > PRE_ROLL_FRAMES) captured.removeFirst()
                }

                if (decision != UtteranceAssembler.Decision.CONTINUE) {
                    outcome = decision
                    Log.d(
                        TAG,
                        "capture finished: decision=$decision frames=$frameCount " +
                            "speechMs=${assembler.speechMs} capturedFrames=${captured.size}",
                    )
                    break
                }
            }
        } finally {
            // Release the mic before the network call: transcription can take a
            // second or more, and holding the recorder open through it keeps the
            // SCO link busy for no reason.
            mic.close()
        }

        if (outcome != UtteranceAssembler.Decision.FINISH) {
            Log.d(TAG, "no speech this turn (speechMs=${assembler.speechMs})")
            emit(SttEvent.Error(SttError.NO_MATCH))
            return
        }

        emit(SttEvent.EndOfSpeech)

        val clip = PcmClip(flatten(captured), Vad.SAMPLE_RATE_HZ)
        Log.d(TAG, "transcribing ${clip.durationMs}ms of audio")

        val text = try {
            transcriber.transcribe(clip, config.languageTag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranscriptionException) {
            Log.w(TAG, "transcription failed: ${e.message}")
            emit(SttEvent.Error(e.error))
            return
        }

        if (text.isBlank()) {
            emit(SttEvent.Error(SttError.NO_MATCH))
        } else {
            emit(SttEvent.FinalResult(text))
        }
    }

    private fun flatten(frames: Collection<ShortArray>): ShortArray {
        val out = ShortArray(frames.sumOf { it.size })
        var offset = 0
        for (frame in frames) {
            frame.copyInto(out, offset)
            offset += frame.size
        }
        return out
    }

    private companion object {
        const val TAG = "PipelineSpeechToText"

        /** ~320ms of lead-in retained before speech is confirmed. */
        const val PRE_ROLL_FRAMES = 10

        /** Log every Nth frame's raw level (+ VAD verdict) at ~3.1 Hz. */
        const val LD = 10L
    }
}
